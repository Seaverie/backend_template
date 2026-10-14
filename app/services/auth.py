"""Tenant-user and platform-admin authentication.

Refresh-token rotation with reuse detection: every refresh token is single-use. Presenting a refresh
token that was already used means it was copied, so the whole session (every token descended from
the login) is revoked.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select, update

from app.cache.redis import Cache, safe
from app.core.config import Settings
from app.core.errors import Unauthorized
from app.core.logging import get_logger
from app.db.session import Database
from app.models import AppUser, AuthRefreshToken, AuthSession, PlatformAdmin, PlatformSession
from app.security.passwords import burn_verification_time, hash_password, verify_password
from app.security.tokens import TokenClaims, TokenService
from app.services.audit import AuditContext, record
from app.services.tenants import TenantService

log = get_logger("auth")
_INVALID = "Invalid credentials."


@dataclass(frozen=True, slots=True)
class TokenPair:
    access_token: str
    refresh_token: str | None
    expires_in: int
    token_type: str = "Bearer"  # noqa: S105 - token scheme name, not a secret


def _now() -> datetime:
    return datetime.now(UTC)


class AuthService:
    def __init__(
        self, settings: Settings, db: Database, cache: Cache, tokens: TokenService, tenants: TenantService
    ) -> None:
        self._s = settings
        self._db = db
        self._cache = cache
        self._tokens = tokens
        self._tenants = tenants

    # -- tenant users ---------------------------------------------------------------------------

    def _issue_pair(
        self, tenant_id: uuid.UUID, user_id: uuid.UUID, sid: uuid.UUID, refresh_jti: uuid.UUID
    ) -> TokenPair:
        access, _ = self._tokens.issue(
            typ="access", aud="tenant", sub=user_id, sid=sid, tid=tenant_id, ttl_s=self._s.access_ttl_s
        )
        refresh, _ = self._tokens.issue(
            typ="refresh",
            aud="tenant",
            sub=user_id,
            sid=sid,
            tid=tenant_id,
            ttl_s=self._s.refresh_ttl_s,
            jti=refresh_jti,
        )
        return TokenPair(access, refresh, self._s.access_ttl_s)

    async def login(
        self,
        *,
        tenant_slug: str,
        email: str,
        password: str,
        ip: str | None,
        user_agent: str | None,
        request_id: str | None,
    ) -> TokenPair:
        tenant = await self._tenants.get_by_slug(tenant_slug)
        if tenant is None or not tenant.active:
            await burn_verification_time(password)
            raise Unauthorized(_INVALID)

        async with self._db.tenant_session(tenant.id) as s:
            user = (
                await s.execute(
                    select(AppUser).where(func.lower(AppUser.email) == email.lower()).with_for_update()
                )
            ).scalar_one_or_none()
            if user is None:
                await burn_verification_time(password)
                raise Unauthorized(_INVALID)
            now = _now()
            if user.locked_until and user.locked_until > now:
                await burn_verification_time(password)
                raise Unauthorized(_INVALID)
            valid, needs_rehash = await verify_password(user.password_hash, password)
            ctx = AuditContext("user", user.id, request_id, ip)
            if not valid or not user.is_active:
                user.failed_logins += 1
                if user.failed_logins >= self._s.max_failed_logins:
                    user.locked_until = now + timedelta(seconds=self._s.lockout_s)
                    user.failed_logins = 0
                    await record(
                        s,
                        ctx,
                        tenant_id=tenant.id,
                        action="user.locked",
                        resource="users",
                        resource_id=str(user.id),
                    )
                await record(
                    s,
                    ctx,
                    tenant_id=tenant.id,
                    action="auth.login_failed",
                    resource="users",
                    resource_id=str(user.id),
                )
                await s.commit()
                raise Unauthorized(_INVALID)

            user.failed_logins = 0
            user.locked_until = None
            user.last_login_at = now
            if needs_rehash:
                user.password_hash = await hash_password(password)
            session_row = AuthSession(
                tenant_id=tenant.id,
                user_id=user.id,
                expires_at=now + timedelta(seconds=self._s.refresh_ttl_s),
                user_agent=(user_agent or "")[:512] or None,
                ip=ip,
            )
            s.add(session_row)
            await s.flush()
            jti = uuid.uuid4()
            s.add(
                AuthRefreshToken(
                    id=jti,
                    tenant_id=tenant.id,
                    session_id=session_row.id,
                    expires_at=now + timedelta(seconds=self._s.refresh_ttl_s),
                )
            )
            await record(
                s, ctx, tenant_id=tenant.id, action="auth.login", resource="users", resource_id=str(user.id)
            )
            await s.commit()
            return self._issue_pair(tenant.id, user.id, session_row.id, jti)

    async def refresh(self, refresh_token: str, *, ip: str | None, request_id: str | None) -> TokenPair:
        claims = self._tokens.verify(refresh_token, expected_typ="refresh", expected_aud="tenant")
        if claims.tid is None:
            raise Unauthorized("Invalid token.")
        tenant = await self._tenants.get(claims.tid)
        if tenant is None or not tenant.active:
            raise Unauthorized("Invalid token.")
        reuse_detected = False
        async with self._db.tenant_session(claims.tid, user_id=claims.sub) as s:
            row = (
                await s.execute(
                    select(AuthRefreshToken).where(AuthRefreshToken.id == claims.jti).with_for_update()
                )
            ).scalar_one_or_none()
            if row is None or row.session_id != claims.sid:
                raise Unauthorized("Invalid token.")
            sess = (
                await s.execute(select(AuthSession).where(AuthSession.id == claims.sid).with_for_update())
            ).scalar_one()
            now = _now()
            ctx = AuditContext("user", claims.sub, request_id, ip)
            if sess.revoked_at is not None or sess.expires_at <= now or row.expires_at <= now:
                raise Unauthorized("Invalid token.")
            if row.used_at is not None:
                sess.revoked_at = now
                sess.revoked_reason = "refresh_reuse"
                await record(
                    s,
                    ctx,
                    tenant_id=claims.tid,
                    action="auth.refresh_reuse_detected",
                    resource="sessions",
                    resource_id=str(sess.id),
                )
                await s.commit()
                reuse_detected = True
            else:
                user_active = (
                    await s.execute(select(AppUser.is_active).where(AppUser.id == claims.sub))
                ).scalar_one_or_none()
                if not user_active:
                    raise Unauthorized("Invalid token.")
                new_jti = uuid.uuid4()
                row.used_at = now
                row.replaced_by = new_jti
                s.add(
                    AuthRefreshToken(
                        id=new_jti,
                        tenant_id=claims.tid,
                        session_id=sess.id,
                        expires_at=min(sess.expires_at, now + timedelta(seconds=self._s.refresh_ttl_s)),
                    )
                )
                await s.commit()
                return self._issue_pair(claims.tid, claims.sub, sess.id, new_jti)
        if reuse_detected:
            await self._mark_revoked(claims.sid)
        raise Unauthorized("Invalid token.")

    async def logout(
        self, *, tenant_id: uuid.UUID, user_id: uuid.UUID, sid: uuid.UUID, ctx: AuditContext
    ) -> None:
        async with self._db.tenant_session(tenant_id, user_id=user_id) as s:
            await s.execute(
                update(AuthSession)
                .where(AuthSession.id == sid, AuthSession.revoked_at.is_(None))
                .values(revoked_at=_now(), revoked_reason="logout")
            )
            await record(
                s, ctx, tenant_id=tenant_id, action="auth.logout", resource="sessions", resource_id=str(sid)
            )
            await s.commit()
        await self._mark_revoked(sid)

    async def _mark_revoked(self, sid: uuid.UUID) -> None:
        # Marker outlives any access token minted for this session (access TTL < marker TTL).
        await safe(
            self._cache.client.set(
                self._cache.keys.session_revoked(str(sid)), b"1", ex=self._s.session_revocation_ttl_s
            ),
            op="session.revoke",
        )

    async def session_is_valid(self, claims: TokenClaims) -> bool:
        """Revocation check for access tokens.

        Redis holds a revocation marker written at logout/reuse detection. If Redis is unreachable we
        fall back to the database (an indexed primary-key lookup), so an outage never accepts a
        revoked session.
        """
        ok, marker = await safe(
            self._cache.client.get(self._cache.keys.session_revoked(str(claims.sid))), op="session.check"
        )
        if ok:
            return marker is None
        if claims.tid is None:
            return False
        async with self._db.tenant_session(claims.tid, user_id=claims.sub) as s:
            row = (
                await s.execute(
                    select(AuthSession.revoked_at, AuthSession.expires_at).where(AuthSession.id == claims.sid)
                )
            ).one_or_none()
        return row is not None and row.revoked_at is None and row.expires_at > _now()

    # -- platform admins ------------------------------------------------------------------------

    async def platform_login(
        self, *, email: str, password: str, ip: str | None, request_id: str | None
    ) -> TokenPair:
        async with self._db.platform_session(uuid.UUID(int=0)) as s:
            admin = (
                await s.execute(
                    select(PlatformAdmin)
                    .where(func.lower(PlatformAdmin.email) == email.lower())
                    .with_for_update()
                )
            ).scalar_one_or_none()
            if admin is None:
                await burn_verification_time(password)
                raise Unauthorized(_INVALID)
            now = _now()
            if admin.locked_until and admin.locked_until > now:
                await burn_verification_time(password)
                raise Unauthorized(_INVALID)
            valid, _ = await verify_password(admin.password_hash, password)
            ctx = AuditContext("platform", admin.id, request_id, ip)
            if not valid or not admin.is_active:
                admin.failed_logins += 1
                if admin.failed_logins >= self._s.max_failed_logins:
                    admin.locked_until = now + timedelta(seconds=self._s.lockout_s)
                    admin.failed_logins = 0
                await record(
                    s,
                    ctx,
                    tenant_id=None,
                    action="platform.login_failed",
                    resource="platform_admins",
                    resource_id=str(admin.id),
                )
                await s.commit()
                raise Unauthorized(_INVALID)
            admin.failed_logins = 0
            admin.last_login_at = now
            ps = PlatformSession(
                admin_id=admin.id, expires_at=now + timedelta(seconds=self._s.platform_access_ttl_s)
            )
            s.add(ps)
            await s.flush()
            await record(
                s,
                ctx,
                tenant_id=None,
                action="platform.login",
                resource="platform_admins",
                resource_id=str(admin.id),
            )
            await s.commit()
        access, _ = self._tokens.issue(
            typ="access",
            aud="platform",
            sub=admin.id,
            sid=ps.id,
            tid=None,
            ttl_s=self._s.platform_access_ttl_s,
        )
        return TokenPair(access, None, self._s.platform_access_ttl_s)

    async def platform_session_is_valid(self, claims: TokenClaims) -> bool:
        # Platform traffic is low-volume and high-privilege: always check the database.
        async with self._db.platform_session(claims.sub) as s:
            row = (
                await s.execute(
                    select(PlatformSession.revoked_at, PlatformSession.expires_at, PlatformAdmin.is_active)
                    .join(PlatformAdmin, PlatformAdmin.id == PlatformSession.admin_id)
                    .where(PlatformSession.id == claims.sid, PlatformSession.admin_id == claims.sub)
                )
            ).one_or_none()
        return row is not None and row.revoked_at is None and row.expires_at > _now() and bool(row.is_active)

    async def platform_logout(self, claims: TokenClaims) -> None:
        async with self._db.platform_session(claims.sub) as s:
            await s.execute(
                update(PlatformSession).where(PlatformSession.id == claims.sid).values(revoked_at=_now())
            )
            await s.commit()
