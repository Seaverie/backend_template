"""Operator CLI.

    python -m app.cli create-platform-admin --email ops@example.com
    python -m app.cli seed

``create-platform-admin`` reads the password from TC_BOOTSTRAP_PASSWORD or prompts for it without
echo. ``seed`` creates the first platform admin from TC_SEED_ADMIN_EMAIL / TC_SEED_ADMIN_PASSWORD
(docker-compose runs it after migrations) and does nothing when they are unset or the admin exists.
Passwords are never accepted as command-line arguments, because arguments leak into shell history
and process listings, and only their Argon2 hash is stored.
"""

from __future__ import annotations

import argparse
import asyncio
import getpass
import os
import sys

from sqlalchemy import select, text

from app.core.config import get_settings
from app.db.session import Database
from app.models import PlatformAdmin
from app.security.passwords import hash_password, validate_password_policy


async def _create_admin(email: str, password: str, *, skip_existing: bool = False) -> bool:
    """Insert a platform admin. Returns False (and changes nothing) if ``skip_existing`` and the email
    is already registered; an existing admin's password is never overwritten."""
    db = Database(get_settings())
    try:
        # Runs with the owner (DDL) role; platform_admins is RLS-protected, so enter platform context.
        async with db.ddl_session() as s:
            await s.execute(text("SELECT set_config('app.is_platform', 'on', true)"))
            # Serialise concurrent seeders so two containers cannot race on the same email.
            await s.execute(text("SELECT pg_advisory_xact_lock(hashtext('tenantcore.seed_admin'))"))
            if skip_existing:
                found = await s.execute(select(PlatformAdmin.id).where(PlatformAdmin.email == email.lower()))
                if found.first() is not None:
                    return False
            s.add(PlatformAdmin(email=email.lower(), password_hash=await hash_password(password)))
            await s.commit()
            return True
    finally:
        await db.dispose()


PLACEHOLDERS = ("CHANGE_ME",)


def seed(env: dict[str, str]) -> int:
    email = env.get("TC_SEED_ADMIN_EMAIL", "").strip()
    password = env.get("TC_SEED_ADMIN_PASSWORD", "")
    if not email and not password:
        print("seed: TC_SEED_ADMIN_EMAIL/TC_SEED_ADMIN_PASSWORD not set; nothing to do")
        return 0
    if not email or not password:
        print("seed: set both TC_SEED_ADMIN_EMAIL and TC_SEED_ADMIN_PASSWORD", file=sys.stderr)
        return 2
    if "@" not in email:
        print("seed: TC_SEED_ADMIN_EMAIL is not an email address", file=sys.stderr)
        return 2
    if any(p in password for p in PLACEHOLDERS):
        print("seed: replace the CHANGE_ME placeholder in TC_SEED_ADMIN_PASSWORD", file=sys.stderr)
        return 2
    try:
        validate_password_policy(password)
    except ValueError as e:
        print(f"seed: TC_SEED_ADMIN_PASSWORD rejected: {e}", file=sys.stderr)
        return 2
    created = asyncio.run(_create_admin(email, password, skip_existing=True))
    outcome = "created" if created else "already exists; left unchanged"
    print(f"seed: platform admin {email.lower()} {outcome}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="tenantcore")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("create-platform-admin")
    p.add_argument("--email", required=True)
    sub.add_parser("seed", help="create the first platform admin from TC_SEED_ADMIN_* env vars")
    args = parser.parse_args(argv)

    if args.cmd == "seed":
        return seed(dict(os.environ))

    if args.cmd == "create-platform-admin":
        password = os.environ.get("TC_BOOTSTRAP_PASSWORD") or getpass.getpass("Password: ")
        try:
            validate_password_policy(password)
        except ValueError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        asyncio.run(_create_admin(args.email, password))
        print("platform admin created")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
