"""Firebase Cloud Messaging (HTTP v1) sender.

Authentication uses a Google service account: a short-lived RS256 JWT assertion is exchanged at
Google's token endpoint for an OAuth2 access token, cached until shortly before expiry. Both hosts go
through the SSRF-safe client with a fixed allowlist.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass
from typing import Any

import jwt

from app.integrations.http import OutboundPolicy, SafeHttpClient

TOKEN_URL = "https://oauth2.googleapis.com/token"  # noqa: S105 - endpoint URL, not a secret
SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
POLICY = OutboundPolicy(frozenset({"oauth2.googleapis.com", "fcm.googleapis.com"}))


class FcmError(Exception):
    def __init__(self, message: str, *, permanent: bool = False, unregistered: bool = False) -> None:
        super().__init__(message)
        self.permanent = permanent
        self.unregistered = unregistered


@dataclass(frozen=True, slots=True)
class PushMessage:
    token: str
    title: str
    body: str
    data: dict[str, str]


class FcmSender:
    def __init__(self, http: SafeHttpClient, service_account_json: str, project_id: str | None) -> None:
        sa = json.loads(service_account_json)
        if sa.get("type") != "service_account" or "private_key" not in sa or "client_email" not in sa:
            raise ValueError("invalid service account JSON")
        self._http = http
        self._email = str(sa["client_email"])
        self._key = str(sa["private_key"])
        self._project = project_id or str(sa["project_id"])
        self._token: tuple[str, float] | None = None

    async def _access_token(self) -> str:
        if self._token and self._token[1] > time.time() + 60:
            return self._token[0]
        now = int(time.time())
        assertion = jwt.encode(
            {"iss": self._email, "scope": SCOPE, "aud": TOKEN_URL, "iat": now, "exp": now + 3600},
            self._key,
            algorithm="RS256",
        )
        r = await self._http.request(
            "POST",
            TOKEN_URL,
            policy=POLICY,
            data={"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion},
        )
        if r.status_code != 200:
            raise FcmError(f"token endpoint returned {r.status_code}")
        body = r.json()
        self._token = (str(body["access_token"]), now + int(body.get("expires_in", 3600)))
        return self._token[0]

    async def send(self, msg: PushMessage) -> str:
        token = await self._access_token()
        payload: dict[str, Any] = {
            "message": {
                "token": msg.token,
                "notification": {"title": msg.title, "body": msg.body},
                "data": msg.data,
            }
        }
        r = await self._http.request(
            "POST",
            f"https://fcm.googleapis.com/v1/projects/{self._project}/messages:send",
            policy=POLICY,
            json_body=payload,
            headers={"Authorization": f"Bearer {token}"},
        )
        if r.status_code == 200:
            return str(r.json().get("name", ""))
        status = ""
        try:
            details = r.json().get("error", {}).get("details", [])
            status = next((d.get("errorCode", "") for d in details if "errorCode" in d), "")
        except (ValueError, AttributeError):
            pass
        if r.status_code == 404 or status == "UNREGISTERED":
            raise FcmError("token unregistered", permanent=True, unregistered=True)
        if r.status_code in (400, 403) or status in ("INVALID_ARGUMENT", "SENDER_ID_MISMATCH"):
            raise FcmError(
                f"rejected ({r.status_code} {status})",
                permanent=True,
                unregistered=status == "INVALID_ARGUMENT",
            )
        if r.status_code == 401:
            self._token = None
        raise FcmError(f"transient failure ({r.status_code})")
