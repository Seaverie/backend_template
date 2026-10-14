"""SQL identifier validation.

Every identifier that reaches DDL or a dynamic query passes through here *and* is quoted by SQLAlchemy's
identifier preparer. The regex is the spec's ``^[a-zA-Z0-9_]{1,64}$`` tightened so names start with a
lowercase letter and are lowercase only. Mixed case would require quoting everywhere and invites
look-alike collisions. Reserved words and system-column names are rejected.
"""

from __future__ import annotations

import re

from sqlalchemy.dialects import postgresql

SPEC_IDENTIFIER_RE = re.compile(r"^[a-zA-Z0-9_]{1,64}$")
IDENTIFIER_RE = re.compile(r"^[a-z][a-z0-9_]{0,62}$")  # 63 = PostgreSQL NAMEDATALEN - 1

SYSTEM_COLUMNS = frozenset({"id", "tenant_id", "created_at", "updated_at", "created_by", "updated_by"})
_RESERVED = frozenset(postgresql.base.RESERVED_WORDS) | frozenset(
    {"oid", "tableoid", "xmin", "xmax", "cmin", "cmax", "ctid", "search_vector"}
)


_PREPARER = postgresql.dialect().identifier_preparer  # type: ignore[no-untyped-call]


class InvalidIdentifier(ValueError):
    pass


def validate_identifier(name: str, *, allow_system: bool = False) -> str:
    if (
        not isinstance(name, str)
        or not SPEC_IDENTIFIER_RE.fullmatch(name)
        or not IDENTIFIER_RE.fullmatch(name)
    ):
        raise InvalidIdentifier("identifier must match ^[a-z][a-z0-9_]{0,62}$")
    if name in _RESERVED:
        raise InvalidIdentifier("identifier is a reserved word")
    if name.startswith("pg_") or "__" in name:
        raise InvalidIdentifier("identifier uses a reserved prefix or pattern")
    if not allow_system and name in SYSTEM_COLUMNS:
        raise InvalidIdentifier("identifier collides with a system column")
    return name


def quote(name: str) -> str:
    """Quote an already-validated identifier for PostgreSQL."""
    validate_identifier(name, allow_system=True)
    return _PREPARER.quote_identifier(name)
