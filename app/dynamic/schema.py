"""Dynamic table definitions: validated specs, runtime metadata, SQLAlchemy table construction, and
DDL generation.

DDL is never assembled from strings supplied by clients. A request is parsed into ``TableSpec``
(allowlisted types, validated identifiers, bounded sizes) and turned into SQLAlchemy ``Table`` objects
whose DDL is compiled by the PostgreSQL dialect, which quotes every identifier. The only hand-written
statements are the RLS/GRANT templates below, which interpolate identifiers only after
``identifiers.quote`` has validated and quoted them.
"""

from __future__ import annotations

import hashlib
import re
import uuid
from dataclasses import dataclass
from typing import Any

import sqlalchemy as sa
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy.dialects import postgresql
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.schema import CreateIndex, CreateTable

from app.models import DYNAMIC_SCHEMA, CrudMode, FieldType
from app.security.identifiers import InvalidIdentifier, quote, validate_identifier

_ENUM_VALUE_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
MAX_FIELDS = 100
TEXT_TYPES = frozenset({FieldType.string, FieldType.text, FieldType.enum})
_PG = postgresql.dialect()  # type: ignore[no-untyped-call]


# --------------------------------------------------------------------------------------------------
# Request specs
# --------------------------------------------------------------------------------------------------


class FieldSpec(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    type: FieldType
    nullable: bool = True
    unique: bool = False
    indexed: bool = False
    searchable: bool = False
    sensitive: bool = False
    max_length: int | None = Field(None, ge=1, le=10_000)
    precision: int | None = Field(None, ge=1, le=38)
    scale: int | None = Field(None, ge=0, le=38)
    enum_values: list[str] | None = Field(None, min_length=1, max_length=100)
    ref_table: str | None = None

    @field_validator("name")
    @classmethod
    def _name(cls, v: str) -> str:
        try:
            return validate_identifier(v)
        except InvalidIdentifier as e:
            raise ValueError(str(e)) from e

    @field_validator("ref_table")
    @classmethod
    def _ref(cls, v: str | None) -> str | None:
        if v is None:
            return v
        try:
            return validate_identifier(v)
        except InvalidIdentifier as e:
            raise ValueError(str(e)) from e

    @model_validator(mode="after")
    def _type_rules(self) -> FieldSpec:
        t = self.type
        if t == FieldType.string:
            self.max_length = self.max_length or 255
        elif self.max_length is not None:
            raise ValueError("max_length applies only to string fields")
        if t == FieldType.numeric:
            self.precision = self.precision or 18
            self.scale = 2 if self.scale is None else self.scale
            if self.scale > self.precision:
                raise ValueError("scale must not exceed precision")
        elif self.precision is not None or self.scale is not None:
            raise ValueError("precision/scale apply only to numeric fields")
        if t == FieldType.enum:
            if not self.enum_values:
                raise ValueError("enum fields require enum_values")
            if any(not _ENUM_VALUE_RE.fullmatch(v) for v in self.enum_values):
                raise ValueError("enum values must match ^[A-Za-z0-9_-]{1,64}$")
            if len(set(self.enum_values)) != len(self.enum_values):
                raise ValueError("enum values must be unique")
        elif self.enum_values is not None:
            raise ValueError("enum_values apply only to enum fields")
        if (t == FieldType.reference) != (self.ref_table is not None):
            raise ValueError("ref_table is required for, and only for, reference fields")
        if self.searchable and t not in TEXT_TYPES:
            raise ValueError("only string, text and enum fields can be searchable")
        if self.unique and t in (FieldType.json, FieldType.text):
            raise ValueError("json and text fields cannot be unique")
        if self.indexed and t == FieldType.json:
            raise ValueError("json fields cannot use a btree index")
        return self


class TableSpec(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(..., max_length=60)  # "dt_" + name must fit in 63 bytes
    label: str | None = Field(None, max_length=200)
    crud_mode: CrudMode = CrudMode.full
    require_idempotency: bool = False
    fields: list[FieldSpec] = Field(..., min_length=1, max_length=MAX_FIELDS)

    @field_validator("name")
    @classmethod
    def _name(cls, v: str) -> str:
        try:
            return validate_identifier(v)
        except InvalidIdentifier as e:
            raise ValueError(str(e)) from e

    @model_validator(mode="after")
    def _unique_fields(self) -> TableSpec:
        names = [f.name for f in self.fields]
        if len(set(names)) != len(names):
            raise ValueError("field names must be unique")
        return self


# --------------------------------------------------------------------------------------------------
# Runtime metadata (cached in Redis as JSON)
# --------------------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class FieldMeta:
    name: str
    type: FieldType
    nullable: bool = True
    is_unique: bool = False
    is_indexed: bool = False
    is_searchable: bool = False
    is_sensitive: bool = False
    max_length: int | None = None
    precision: int | None = None
    scale: int | None = None
    enum_values: tuple[str, ...] | None = None
    ref_table: str | None = None

    @classmethod
    def from_spec(cls, f: FieldSpec) -> FieldMeta:
        return cls(
            f.name,
            f.type,
            f.nullable,
            f.unique,
            f.indexed,
            f.searchable,
            f.sensitive,
            f.max_length,
            f.precision,
            f.scale,
            tuple(f.enum_values) if f.enum_values else None,
            f.ref_table,
        )


@dataclass(frozen=True, slots=True)
class TableMeta:
    id: uuid.UUID
    name: str
    physical_name: str
    crud_mode: CrudMode
    require_idempotency: bool
    fields: tuple[FieldMeta, ...]

    @property
    def field_map(self) -> dict[str, FieldMeta]:
        return {f.name: f for f in self.fields}

    @property
    def searchable(self) -> tuple[str, ...]:
        return tuple(f.name for f in self.fields if f.is_searchable)

    @property
    def resource(self) -> str:
        return f"data.{self.name}"

    def to_json(self) -> dict[str, Any]:
        return {
            "id": str(self.id),
            "name": self.name,
            "physical_name": self.physical_name,
            "crud_mode": self.crud_mode.value,
            "require_idempotency": self.require_idempotency,
            "fields": [
                {
                    "name": f.name,
                    "type": f.type.value,
                    "nullable": f.nullable,
                    "is_unique": f.is_unique,
                    "is_indexed": f.is_indexed,
                    "is_searchable": f.is_searchable,
                    "is_sensitive": f.is_sensitive,
                    "max_length": f.max_length,
                    "precision": f.precision,
                    "scale": f.scale,
                    "enum_values": list(f.enum_values) if f.enum_values else None,
                    "ref_table": f.ref_table,
                }
                for f in self.fields
            ],
        }

    @classmethod
    def from_json(cls, d: dict[str, Any]) -> TableMeta:
        return cls(
            uuid.UUID(d["id"]),
            d["name"],
            d["physical_name"],
            CrudMode(d["crud_mode"]),
            bool(d["require_idempotency"]),
            tuple(
                FieldMeta(
                    f["name"],
                    FieldType(f["type"]),
                    f["nullable"],
                    f["is_unique"],
                    f["is_indexed"],
                    f["is_searchable"],
                    f["is_sensitive"],
                    f["max_length"],
                    f["precision"],
                    f["scale"],
                    tuple(f["enum_values"]) if f["enum_values"] else None,
                    f["ref_table"],
                )
                for f in d["fields"]
            ),
        )


def physical_name(name: str) -> str:
    return f"dt_{validate_identifier(name)}"


# --------------------------------------------------------------------------------------------------
# SQLAlchemy construction
# --------------------------------------------------------------------------------------------------


def _sa_type(f: FieldMeta) -> sa.types.TypeEngine[Any]:
    match f.type:
        case FieldType.string:
            return sa.String(f.max_length or 255)
        case FieldType.text:
            return sa.Text()
        case FieldType.integer:
            return sa.Integer()
        case FieldType.bigint:
            return sa.BigInteger()
        case FieldType.numeric:
            return sa.Numeric(f.precision or 18, f.scale if f.scale is not None else 2, asdecimal=True)
        case FieldType.boolean:
            return sa.Boolean()
        case FieldType.date:
            return sa.Date()
        case FieldType.timestamp:
            return sa.DateTime(timezone=True)
        case FieldType.uuid | FieldType.reference:
            return UUID(as_uuid=True)
        case FieldType.json:
            return JSONB()
        case FieldType.enum:
            return sa.String(64)
    raise ValueError(f"unsupported type {f.type}")  # pragma: no cover


def system_columns() -> list[sa.Column[Any]]:
    return [
        sa.Column("id", UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("tenant_id", UUID(as_uuid=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("created_by", UUID(as_uuid=True), nullable=True),
        sa.Column("updated_by", UUID(as_uuid=True), nullable=True),
    ]


def search_vector(table: sa.Table, field_names: tuple[str, ...]) -> sa.ColumnElement[Any]:
    """Expression used by both the GIN index and search queries; they must match exactly."""
    parts: list[sa.ColumnElement[Any]] = []
    for i, name in enumerate(field_names):
        if i:
            parts.append(sa.literal_column("' '"))
        parts.append(sa.func.coalesce(table.c[name], sa.literal_column("''")))
    concatenated = parts[0]
    for p in parts[1:]:
        concatenated = concatenated.op("||")(p)
    return sa.func.to_tsvector(sa.literal_column("'simple'::regconfig"), concatenated)


def build_table(
    meta: TableMeta, md: sa.MetaData | None = None, *, with_ddl_objects: bool = False
) -> sa.Table:
    """Build the SQLAlchemy Table for a dynamic table.

    ``with_ddl_objects`` adds constraints and indexes (needed only when emitting CREATE TABLE).
    """
    md = md if md is not None else sa.MetaData()
    cols: list[Any] = system_columns()
    extra: list[Any] = []
    for f in meta.fields:
        cols.append(sa.Column(f.name, _sa_type(f), nullable=f.nullable))
    if with_ddl_objects:
        pn = meta.physical_name
        if "public.app_tenants" not in md.tables:
            sa.Table(
                "app_tenants", md, sa.Column("id", UUID(as_uuid=True), primary_key=True), schema="public"
            )
        extra.append(
            sa.ForeignKeyConstraint(
                ["tenant_id"], ["public.app_tenants.id"], ondelete="RESTRICT", name=f"fk_{pn}_tenant"[:63]
            )
        )
        extra.append(sa.UniqueConstraint("tenant_id", "id", name=f"uq_{pn}_tenant_id_id"[:63]))
        for f in meta.fields:
            if f.type == FieldType.enum and f.enum_values:
                extra.append(
                    sa.CheckConstraint(
                        sa.column(f.name).in_(list(f.enum_values)), name=f"ck_{pn}_{f.name}_enum"[:63]
                    )
                )
            if f.is_unique:
                extra.append(sa.UniqueConstraint("tenant_id", f.name, name=f"uq_{pn}_{f.name}"[:63]))
            if f.type == FieldType.reference and f.ref_table:
                ref = physical_name(f.ref_table)
                # Composite FK: a reference can only point at a row of the *same tenant*.
                extra.append(
                    sa.ForeignKeyConstraint(
                        ["tenant_id", f.name],
                        [f"{DYNAMIC_SCHEMA}.{ref}.tenant_id", f"{DYNAMIC_SCHEMA}.{ref}.id"],
                        ondelete="RESTRICT",
                        name=f"fk_{pn}_{f.name}"[:63],
                    )
                )
    table = sa.Table(meta.physical_name, md, *cols, *extra, schema=DYNAMIC_SCHEMA)
    if with_ddl_objects:
        pn = meta.physical_name
        sa.Index(f"ix_{pn}_tenant_created"[:63], table.c.tenant_id, table.c.created_at.desc())
        for f in meta.fields:
            if (f.is_indexed or f.type == FieldType.reference) and not f.is_unique:
                sa.Index(f"ix_{pn}_{f.name}"[:63], table.c.tenant_id, table.c[f.name])
        if meta.searchable:
            # The expression starts with a literal, so SQLAlchemy cannot infer the table; bind it explicitly.
            sa.Index(
                f"ix_{pn}_search"[:63],
                search_vector(table, meta.searchable),
                postgresql_using="gin",
                _table=table,
            )
    return table


def _ref_stub(md: sa.MetaData, ref_table: str) -> None:
    pn = physical_name(ref_table)
    key = f"{DYNAMIC_SCHEMA}.{pn}"
    if key not in md.tables:
        sa.Table(
            pn,
            md,
            sa.Column("id", UUID(as_uuid=True), primary_key=True),
            sa.Column("tenant_id", UUID(as_uuid=True)),
            schema=DYNAMIC_SCHEMA,
        )


def create_table_statements(meta: TableMeta, app_role: str) -> list[str]:
    md = sa.MetaData()
    for f in meta.fields:
        if f.ref_table:
            _ref_stub(md, f.ref_table)
    table = build_table(meta, md, with_ddl_objects=True)
    stmts = [str(CreateTable(table).compile(dialect=_PG)).strip()]
    stmts += [
        str(CreateIndex(ix).compile(dialect=_PG)).strip()
        for ix in sorted(table.indexes, key=lambda i: str(i.name))
    ]
    qualified = f"{quote(DYNAMIC_SCHEMA)}.{quote(meta.physical_name)}"
    role = quote(app_role)
    stmts += [
        f"ALTER TABLE {qualified} ENABLE ROW LEVEL SECURITY",
        f"ALTER TABLE {qualified} FORCE ROW LEVEL SECURITY",
        f"CREATE POLICY tenant_isolation ON {qualified} FOR ALL TO PUBLIC "
        "USING (tenant_id = public.app_current_tenant_id()) "
        "WITH CHECK (tenant_id = public.app_current_tenant_id())",
        f"GRANT SELECT, INSERT, UPDATE, DELETE ON {qualified} TO {role}",
    ]
    return stmts


def checksum(statements: list[str]) -> str:
    return hashlib.sha256("\n;\n".join(statements).encode()).hexdigest()
