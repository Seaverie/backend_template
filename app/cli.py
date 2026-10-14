"""Operator CLI.

    python -m app.cli create-platform-admin --email ops@example.com

The password is read from the TC_BOOTSTRAP_PASSWORD environment variable or prompted for without
echo. It is never accepted as a command-line argument, because arguments leak into shell history
and process listings.
"""

from __future__ import annotations

import argparse
import asyncio
import getpass
import os
import sys

from sqlalchemy import text

from app.core.config import get_settings
from app.db.session import Database
from app.models import PlatformAdmin
from app.security.passwords import hash_password, validate_password_policy


async def _create_admin(email: str, password: str) -> None:
    db = Database(get_settings())
    try:
        # Runs with the owner (DDL) role; platform_admins is RLS-protected, so enter platform context.
        async with db.ddl_session() as s:
            await s.execute(text("SELECT set_config('app.is_platform', 'on', true)"))
            s.add(PlatformAdmin(email=email.lower(), password_hash=await hash_password(password)))
            await s.commit()
    finally:
        await db.dispose()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="tenantcore")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("create-platform-admin")
    p.add_argument("--email", required=True)
    args = parser.parse_args(argv)

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
