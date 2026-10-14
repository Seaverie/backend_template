"""`python -m app.cli seed`: creates the first platform admin from env vars, idempotently."""

from __future__ import annotations

import asyncio
import os
import subprocess
import sys
import uuid
from pathlib import Path

import pytest
from httpx import AsyncClient
from sqlalchemy import select, text

from app.cli import seed
from app.container import Container
from app.models import PlatformAdmin
from tests.integration.conftest import PASSWORD, P

ROOT = Path(__file__).resolve().parents[2]


def _run_seed(env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-m", "app.cli", "seed"],
        cwd=ROOT,
        env={**os.environ, **env},
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )


async def test_seed_creates_admin_once_and_it_can_sign_in(client: AsyncClient, container: Container) -> None:
    email = f"Seed-{uuid.uuid4().hex[:8]}@Example.com"
    env = {"TC_SEED_ADMIN_EMAIL": email, "TC_SEED_ADMIN_PASSWORD": PASSWORD}
    first = await asyncio.to_thread(_run_seed, env)
    assert first.returncode == 0, first.stderr
    assert "created" in first.stdout

    r = await client.post(f"{P}/platform/auth/login", json={"email": email.lower(), "password": PASSWORD})
    assert r.status_code == 200, r.text

    # Rerunning (every `docker compose up`) changes nothing, even with a different password.
    again = await asyncio.to_thread(_run_seed, {**env, "TC_SEED_ADMIN_PASSWORD": "Another-Passw0rd-123"})
    assert again.returncode == 0 and "already exists" in again.stdout, again.stdout + again.stderr
    r = await client.post(f"{P}/platform/auth/login", json={"email": email.lower(), "password": PASSWORD})
    assert r.status_code == 200

    async with container.db.ddl_session() as s:
        await s.execute(text("SELECT set_config('app.is_platform', 'on', true)"))
        stmt = select(PlatformAdmin).where(PlatformAdmin.email == email.lower())
        rows = (await s.execute(stmt)).scalars().all()
    assert len(rows) == 1
    assert rows[0].password_hash.startswith("$argon2") and PASSWORD not in rows[0].password_hash


@pytest.mark.parametrize(
    ("env", "code", "message"),
    [
        ({}, 0, "nothing to do"),
        ({"TC_SEED_ADMIN_EMAIL": "a@example.com"}, 2, "set both"),
        ({"TC_SEED_ADMIN_EMAIL": "a@example.com", "TC_SEED_ADMIN_PASSWORD": "CHANGE_ME"}, 2, "placeholder"),
        ({"TC_SEED_ADMIN_EMAIL": "a@example.com", "TC_SEED_ADMIN_PASSWORD": "short"}, 2, "rejected"),
        ({"TC_SEED_ADMIN_EMAIL": "not-an-email", "TC_SEED_ADMIN_PASSWORD": PASSWORD}, 2, "not an email"),
    ],
)
def test_seed_validates_input_before_touching_the_database(
    env: dict[str, str], code: int, message: str, capsys: pytest.CaptureFixture[str]
) -> None:
    assert seed(env) == code
    out = capsys.readouterr()
    assert message in out.out + out.err
