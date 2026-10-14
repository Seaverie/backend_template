"""Parsing and validation of dynamic CRUD inputs.

Nothing here builds SQL text. Query parameters are parsed into small typed structures that hold only
allowlisted field names, allowlisted operators, and values coerced to the field's Python type. The
CRUD layer turns those into SQLAlchemy expressions with bound parameters.
"""

from __future__ import annotations

import datetime as dt
import decimal
import re
import uuid
from dataclasses import dataclass, field
from functools import lru_cache
from typing import Annotated, Any, Literal

from pydantic import Field as PField
from pydantic import StrictBool, StrictInt, StringConstraints, TypeAdapter
from pydantic import ValidationError as PydanticValidationError

from app.core.config import Settings
from app.core.errors import Forbidden, ValidationFailed
from app.dynamic.schema import FieldMeta, TableMeta
from app.models import FieldType
from app.services.authz import Grant

_FILTER_RE = re.compile(r"^filter\[([a-z][a-z0-9_]{0,62})\]\[([a-z]{2,6})\]$")
_ALLOWED_PARAMS = frozenset({"limit", "offset", "sort", "q", "fields", "expand"})
MAX_IN_VALUES = 100
MAX_SORT_KEYS = 3
MAX_EXPAND = 5
MAX_SEARCH_LEN = 200

INT32 = (-(2**31), 2**31 - 1)
INT64 = (-(2**63), 2**63 - 1)

# System columns exposed to clients: readable and filterable/sortable, never writable.
SYSTEM_FIELDS: dict[str, FieldMeta] = {
    "id": FieldMeta("id", FieldType.uuid, nullable=False),
    "created_at": FieldMeta("created_at", FieldType.timestamp, nullable=False),
    "updated_at": FieldMeta("updated_at", FieldType.timestamp, nullable=False),
    "created_by": FieldMeta("created_by", FieldType.uuid),
}

_OPS: dict[FieldType, frozenset[str]] = {
    FieldType.string: frozenset({"eq", "ne", "in", "like", "isnull"}),
    FieldType.text: frozenset({"eq", "ne", "like", "isnull"}),
    FieldType.enum: frozenset({"eq", "ne", "in", "isnull"}),
    FieldType.integer: frozenset({"eq", "ne", "gt", "gte", "lt", "lte", "in", "isnull"}),
    FieldType.bigint: frozenset({"eq", "ne", "gt", "gte", "lt", "lte", "in", "isnull"}),
    FieldType.numeric: frozenset({"eq", "ne", "gt", "gte", "lt", "lte", "in", "isnull"}),
    FieldType.date: frozenset({"eq", "ne", "gt", "gte", "lt", "lte", "isnull"}),
    FieldType.timestamp: frozenset({"eq", "ne", "gt", "gte", "lt", "lte", "isnull"}),
    FieldType.boolean: frozenset({"eq", "ne", "isnull"}),
    FieldType.uuid: frozenset({"eq", "ne", "in", "isnull"}),
    FieldType.reference: frozenset({"eq", "ne", "in", "isnull"}),
    FieldType.json: frozenset({"isnull"}),
}
NON_SORTABLE = frozenset({FieldType.json, FieldType.text})


def _annotation(f: FieldMeta, *, strict: bool) -> Any:
    match f.type:
        case FieldType.string:
            return Annotated[str, StringConstraints(max_length=f.max_length or 255)]
        case FieldType.text:
            return Annotated[str, StringConstraints(max_length=1_000_000)]
        case FieldType.enum:
            return Literal[tuple(f.enum_values or ())]  # type: ignore[misc]
        case FieldType.integer:
            return Annotated[StrictInt if strict else int, PField(ge=INT32[0], le=INT32[1])]
        case FieldType.bigint:
            return Annotated[StrictInt if strict else int, PField(ge=INT64[0], le=INT64[1])]
        case FieldType.numeric:
            p, s = f.precision or 18, f.scale if f.scale is not None else 2
            return Annotated[decimal.Decimal, PField(max_digits=p, decimal_places=s, allow_inf_nan=False)]
        case FieldType.boolean:
            return StrictBool if strict else bool
        case FieldType.date:
            return dt.date
        case FieldType.timestamp:
            return dt.datetime
        case FieldType.uuid | FieldType.reference:
            return uuid.UUID
        case FieldType.json:
            return Any
    raise ValueError(f"unsupported type {f.type}")  # pragma: no cover


@lru_cache(maxsize=4096)
def _adapter(f: FieldMeta, strict: bool) -> TypeAdapter[Any]:
    return TypeAdapter(_annotation(f, strict=strict))


def coerce(f: FieldMeta, value: Any, *, from_query: bool) -> Any:
    if value is None:
        if not f.nullable:
            raise ValidationFailed(details=[{"field": f.name, "msg": "may not be null"}])
        return None
    try:
        out = _adapter(f, not from_query).validate_python(value)
    except PydanticValidationError as e:
        msgs = [err.get("msg") for err in e.errors()][:3]
        raise ValidationFailed(details=[{"field": f.name, "msg": "; ".join(str(m) for m in msgs)}]) from e
    if f.type == FieldType.timestamp and isinstance(out, dt.datetime) and out.tzinfo is None:
        raise ValidationFailed(details=[{"field": f.name, "msg": "timestamp must include a timezone offset"}])
    return out


@dataclass(frozen=True, slots=True)
class Filter:
    field: str
    op: str
    value: Any


@dataclass(slots=True)
class ListQuery:
    filters: list[Filter] = field(default_factory=list)
    sort: list[tuple[str, bool]] = field(default_factory=list)  # (field, descending)
    limit: int = 50
    offset: int = 0
    search: str | None = None
    fields: list[str] | None = None
    expand: list[str] = field(default_factory=list)


def readable_fields(meta: TableMeta, grant: Grant) -> dict[str, FieldMeta]:
    out = dict(SYSTEM_FIELDS)
    for f in meta.fields:
        if grant.can_read(f.name):
            out[f.name] = f
    return out


def _int_param(raw: str, name: str, lo: int, hi: int) -> int:
    try:
        v = int(raw)
    except ValueError as e:
        raise ValidationFailed(details=[{"param": name, "msg": "must be an integer"}]) from e
    if not lo <= v <= hi:
        raise ValidationFailed(details=[{"param": name, "msg": f"must be between {lo} and {hi}"}])
    return v


def parse_list_query(
    params: list[tuple[str, str]], meta: TableMeta, grant: Grant, settings: Settings
) -> ListQuery:
    readable = readable_fields(meta, grant)
    q = ListQuery(limit=settings.default_page_size)
    seen: set[str] = set()
    for key, raw in params:
        if key in _ALLOWED_PARAMS:
            if key in seen:
                raise ValidationFailed(details=[{"param": key, "msg": "may only be given once"}])
            seen.add(key)
        if key == "limit":
            q.limit = _int_param(raw, key, 1, settings.max_page_size)
        elif key == "offset":
            q.offset = _int_param(raw, key, 0, settings.max_offset)
        elif key == "sort":
            parts = [p for p in raw.split(",") if p]
            if not 1 <= len(parts) <= MAX_SORT_KEYS:
                raise ValidationFailed(details=[{"param": "sort", "msg": f"1-{MAX_SORT_KEYS} sort keys"}])
            for p in parts:
                desc, name = p.startswith("-"), p.lstrip("-")
                fm = readable.get(name)
                if fm is None or fm.type in NON_SORTABLE:
                    raise ValidationFailed(
                        details=[{"param": "sort", "msg": "unknown or non-sortable field"}]
                    )
                q.sort.append((name, desc))
        elif key == "q":
            if not meta.searchable:
                raise ValidationFailed(details=[{"param": "q", "msg": "table has no searchable fields"}])
            if not all(grant.can_read(n) for n in meta.searchable):
                raise Forbidden("Search requires read access to all searchable fields.")
            if not 1 <= len(raw) <= MAX_SEARCH_LEN:
                raise ValidationFailed(details=[{"param": "q", "msg": f"1-{MAX_SEARCH_LEN} characters"}])
            q.search = raw
        elif key == "fields":
            names = [n for n in raw.split(",") if n]
            unknown = [n for n in names if n not in readable]
            if unknown or not names:
                raise ValidationFailed(details=[{"param": "fields", "msg": "unknown or unreadable field"}])
            q.fields = list(dict.fromkeys(names))
        elif key == "expand":
            names = [n for n in raw.split(",") if n]
            if len(names) > MAX_EXPAND:
                raise ValidationFailed(details=[{"param": "expand", "msg": f"at most {MAX_EXPAND} fields"}])
            for n in names:
                fm = readable.get(n)
                if fm is None or fm.type != FieldType.reference:
                    raise ValidationFailed(
                        details=[{"param": "expand", "msg": "only readable reference fields"}]
                    )
            q.expand = list(dict.fromkeys(names))
        elif m := _FILTER_RE.fullmatch(key):
            name, op = m.group(1), m.group(2)
            fm = readable.get(name)
            if fm is None:
                raise ValidationFailed(details=[{"param": key, "msg": "unknown or unreadable field"}])
            if op not in _OPS[fm.type]:
                raise ValidationFailed(
                    details=[{"param": key, "msg": "operator not allowed for this field type"}]
                )
            if len(q.filters) >= settings.max_filters:
                raise ValidationFailed(details=[{"param": key, "msg": "too many filters"}])
            q.filters.append(Filter(name, op, _filter_value(fm, op, raw)))
        else:
            raise ValidationFailed(details=[{"param": key[:80], "msg": "unknown query parameter"}])
    return q


def _filter_value(fm: FieldMeta, op: str, raw: str) -> Any:
    lax = FieldMeta(
        fm.name,
        fm.type,
        True,
        max_length=fm.max_length,
        precision=fm.precision,
        scale=fm.scale,
        enum_values=fm.enum_values,
    )
    if op == "isnull":
        if raw not in ("true", "false"):
            raise ValidationFailed(details=[{"field": fm.name, "msg": "isnull expects true or false"}])
        return raw == "true"
    if op == "in":
        values = raw.split(",")
        if not 1 <= len(values) <= MAX_IN_VALUES:
            raise ValidationFailed(details=[{"field": fm.name, "msg": f"1-{MAX_IN_VALUES} values"}])
        return [coerce(lax, v, from_query=True) for v in values]
    if op == "like":
        if not 1 <= len(raw) <= 200:
            raise ValidationFailed(details=[{"field": fm.name, "msg": "1-200 characters"}])
        return raw
    if fm.type == FieldType.boolean and raw not in ("true", "false"):
        raise ValidationFailed(details=[{"field": fm.name, "msg": "expects true or false"}])
    return coerce(lax, raw, from_query=True)


def validate_write(meta: TableMeta, grant: Grant, body: Any, *, partial: bool) -> dict[str, Any]:
    """Validate a create/update body. Rejects unknown keys, system columns, and unwritable fields
    (mass-assignment protection), enforces required fields on create, and coerces types."""
    if not isinstance(body, dict):
        raise ValidationFailed("Request body must be a JSON object.")
    fmap = meta.field_map
    unknown = sorted(k for k in body if k not in fmap)
    if unknown:
        raise ValidationFailed("Unknown or read-only fields.", details={"fields": unknown[:20]})
    forbidden = sorted(k for k in body if not grant.can_write(k))
    if forbidden:
        raise Forbidden("You may not write some of the submitted fields.")
    if not partial:
        missing = sorted(f.name for f in meta.fields if not f.nullable and f.name not in body)
        if missing:
            raise ValidationFailed("Required fields are missing.", details={"fields": missing})
    elif not body:
        raise ValidationFailed("No fields to update.")
    return {k: coerce(fmap[k], v, from_query=False) for k, v in body.items()}
