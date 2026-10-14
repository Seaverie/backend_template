from __future__ import annotations

import uuid
from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.core.config import get_settings
from app.core.errors import Forbidden, ValidationFailed
from app.dynamic.query import parse_list_query, validate_write
from app.dynamic.schema import (
    FieldMeta,
    FieldSpec,
    TableMeta,
    TableSpec,
    create_table_statements,
    physical_name,
)
from app.models import Action, CrudMode, FieldType, RowScope
from app.services.authz import FULL_GRANT, Grant, PermissionMatrix, Principal, scope_allows
from app.services.ratelimit import Bucket, _LocalGcra
from app.services.rbac import PermissionSpec, ensure_can_delegate

META = TableMeta(
    uuid.uuid4(),
    "products",
    "dt_products",
    CrudMode.full,
    False,
    (
        FieldMeta("name", FieldType.string, nullable=False, max_length=50, is_searchable=True),
        FieldMeta("price", FieldType.numeric, precision=10, scale=2),
        FieldMeta("qty", FieldType.integer),
        FieldMeta("active", FieldType.boolean),
        FieldMeta("status", FieldType.enum, enum_values=("draft", "live")),
        FieldMeta("cost", FieldType.numeric, precision=10, scale=2, is_sensitive=True),
        FieldMeta("attrs", FieldType.json),
    ),
)
S = get_settings()


# -- table specs / DDL --------------------------------------------------------------------------------


def test_spec_rejects_injection_in_names() -> None:
    with pytest.raises(ValidationError):
        TableSpec(name='x"; drop table app_users; --', fields=[FieldSpec(name="a", type=FieldType.text)])
    with pytest.raises(ValidationError):
        FieldSpec(name="a b", type=FieldType.text)
    with pytest.raises(ValidationError):
        FieldSpec(name="tenant_id", type=FieldType.uuid)  # system column


def test_spec_rejects_injection_in_enum_values() -> None:
    with pytest.raises(ValidationError):
        FieldSpec(name="s", type=FieldType.enum, enum_values=["ok", "x') OR 1=1; --"])


def test_spec_rejects_unknown_keys() -> None:
    with pytest.raises(ValidationError):
        TableSpec.model_validate({"name": "t", "fields": [{"name": "a", "type": "text", "sql": "anything"}]})
    with pytest.raises(ValidationError):
        FieldSpec.model_validate({"name": "a", "type": "money"})  # type not allowlisted


def test_generated_ddl_has_rls_and_tenant_scoped_constraints() -> None:
    spec = TableSpec(
        name="orders",
        fields=[
            FieldSpec(name="code", type=FieldType.string, unique=True),
            FieldSpec(name="customer", type=FieldType.reference, ref_table="customers"),
            FieldSpec(name="state", type=FieldType.enum, enum_values=["new", "paid"]),
        ],
    )
    meta = TableMeta(
        uuid.uuid4(),
        "orders",
        physical_name("orders"),
        CrudMode.full,
        False,
        tuple(FieldMeta.from_spec(f) for f in spec.fields),
    )
    sql = "\n".join(create_table_statements(meta))
    assert "UNIQUE (tenant_id, code)" in sql  # uniqueness per tenant, not global
    assert "FOREIGN KEY(tenant_id, customer) REFERENCES tenant_data.dt_customers (tenant_id, id)" in sql
    assert "FORCE ROW LEVEL SECURITY" in sql
    assert "USING (tenant_id = public.app_current_tenant_id())" in sql
    assert "CHECK (state IN ('new', 'paid'))" in sql


# -- list query parsing -------------------------------------------------------------------------------


def test_parse_valid_query() -> None:
    q = parse_list_query(
        [
            ("filter[price][gte]", "10.50"),
            ("filter[status][in]", "draft,live"),
            ("sort", "-price,name"),
            ("limit", "10"),
            ("q", "chair"),
        ],
        META,
        FULL_GRANT,
        S,
    )
    assert q.filters[0].value == Decimal("10.50")
    assert q.filters[1].value == ["draft", "live"]
    assert q.sort == [("price", True), ("name", False)]
    assert q.limit == 10 and q.search == "chair"


@pytest.mark.parametrize(
    "params",
    [
        [("filter[name][regex]", "x")],  # operator not allowlisted
        [("filter[price][like]", "1")],  # operator not valid for numeric
        [("filter[nope][eq]", "1")],  # unknown field
        [("filter[tenant_id][eq]", str(uuid.uuid4()))],  # tenant_id is never filterable
        [("filter[qty][eq]", "1; DROP TABLE x")],  # type coercion fails
        [("filter[status][eq]", "deleted")],  # not an enum member
        [("sort", "name;DROP TABLE x")],
        [("sort", "attrs")],  # json not sortable
        [("limit", "100000")],
        [("offset", "-1")],
        [("tenant_id", str(uuid.uuid4()))],  # unknown parameter, including tenant overrides
        [("limit", "5"), ("limit", "6")],
        [("expand", "name")],  # not a reference
    ],
)
def test_parse_rejects(params: list[tuple[str, str]]) -> None:
    with pytest.raises(ValidationFailed):
        parse_list_query(params, META, FULL_GRANT, S)


def test_field_level_read_restrictions_apply_to_filters_sort_and_projection() -> None:
    g = Grant(RowScope.all, frozenset({"name"}), None)
    for params in ([("filter[cost][gt]", "1")], [("sort", "cost")], [("fields", "name,cost")]):
        with pytest.raises(ValidationFailed):
            parse_list_query(params, META, g, S)
    q = parse_list_query([("fields", "id,name")], META, g, S)
    assert q.fields == ["id", "name"]


def test_search_requires_reading_all_searchable_fields() -> None:
    g = Grant(RowScope.all, frozenset({"price"}), None)
    with pytest.raises(Forbidden):
        parse_list_query([("q", "x")], META, g, S)


# -- write validation (mass assignment) ---------------------------------------------------------------


def test_write_rejects_system_and_unknown_fields() -> None:
    for body in (
        {"name": "x", "tenant_id": str(uuid.uuid4())},
        {"name": "x", "created_by": "y"},
        {"name": "x", "id": "z"},
        {"name": "x", "is_admin": True},
    ):
        with pytest.raises(ValidationFailed):
            validate_write(META, FULL_GRANT, body, partial=False)


def test_write_enforces_field_permissions() -> None:
    g = Grant(RowScope.all, None, frozenset({"name", "price"}))
    assert validate_write(META, g, {"name": "chair", "price": "9.99"}, partial=False) == {
        "name": "chair",
        "price": Decimal("9.99"),
    }
    with pytest.raises(Forbidden):
        validate_write(META, g, {"name": "chair", "cost": "1.00"}, partial=False)


def test_write_type_validation() -> None:
    bad = [
        {"name": "x" * 51},
        {"name": "x", "qty": "5"},
        {"name": "x", "qty": 2**40},
        {"name": "x", "active": 1},
        {"name": "x", "price": "1.234"},
        {"name": "x", "price": "NaN"},
        {"name": None},
        {"name": "x", "status": "z"},
    ]
    for body in bad:
        with pytest.raises(ValidationFailed):
            validate_write(META, FULL_GRANT, body, partial=False)
    with pytest.raises(ValidationFailed):
        validate_write(META, FULL_GRANT, {"price": "1.00"}, partial=False)  # required 'name' missing
    assert validate_write(META, FULL_GRANT, {"price": "1.00"}, partial=True) == {"price": Decimal("1.00")}
    with pytest.raises(ValidationFailed):
        validate_write(META, FULL_GRANT, ["not", "an", "object"], partial=False)


# -- authorization ------------------------------------------------------------------------------------


def _principal(perms: PermissionMatrix, scopes: frozenset[str] | None = None) -> Principal:
    return Principal(
        "api_key" if scopes else "user", uuid.uuid4(), uuid.uuid4(), uuid.uuid4(), None, perms, scopes
    )


def test_scope_matching() -> None:
    sc = frozenset({"data.products:read", "data.*:create", "users:*"})
    assert scope_allows(sc, "data.products", Action.read)
    assert not scope_allows(sc, "data.products", Action.delete)
    assert scope_allows(sc, "data.orders", Action.create)
    assert scope_allows(sc, "users", Action.manage)
    assert not scope_allows(sc, "roles", Action.manage)


def test_api_key_never_exceeds_creator() -> None:
    creator = PermissionMatrix(True, False, {"data.products|read|all": FULL_GRANT})
    p = _principal(creator, frozenset({"data.*:*"}))
    assert p.grant("data.products", Action.read) is not None
    assert p.grant("data.products", Action.delete) is None  # scope allows, creator does not


def test_inactive_user_has_no_permissions() -> None:
    m = PermissionMatrix(False, True, {})
    assert m.grant("data.products", Action.read) is None


def test_all_scope_grant_is_not_widened_by_own_scope_fields() -> None:
    m = PermissionMatrix(
        True,
        False,
        {
            "data.products|read|all": Grant(RowScope.all, frozenset({"name"}), None),
            "data.products|read|own": Grant(RowScope.own, None, None),
        },
    )
    g = m.grant("data.products", Action.read)
    assert g is not None and g.row_scope == RowScope.all and g.readable == frozenset({"name"})


def test_matrix_json_roundtrip() -> None:
    m = PermissionMatrix(True, False, {"data.x|read|own": Grant(RowScope.own, frozenset({"a"}), None)})
    assert PermissionMatrix.from_json(m.to_json()) == m


def test_delegation_cannot_escalate() -> None:
    holder = _principal(
        PermissionMatrix(
            True, False, {"data.products|read|own": Grant(RowScope.own, frozenset({"name"}), None)}
        )
    )
    ensure_can_delegate(holder, PermissionSpec("data.products", Action.read, RowScope.own, ["name"], None))
    for spec in (
        PermissionSpec("data.products", Action.read, RowScope.all, ["name"], None),  # wider rows
        PermissionSpec("data.products", Action.read, RowScope.own, ["name", "cost"], None),  # more fields
        PermissionSpec("data.products", Action.read, RowScope.own, None, None),  # all fields
        PermissionSpec("data.products", Action.delete, RowScope.own, None, None),  # action not held
    ):
        with pytest.raises(Forbidden):
            ensure_can_delegate(holder, spec)


# -- local rate-limit fallback -----------------------------------------------------------------------


def test_local_gcra_enforces_divided_limit_atomically() -> None:
    lim = _LocalGcra()
    buckets = [Bucket("t", 40), Bucket("p", 1000)]
    allowed = sum(lim.check(buckets, divisor=4).allowed for _ in range(20))
    assert allowed == 10  # 40 / 4
    # A rejection on one bucket must not charge the others.
    lim2 = _LocalGcra()
    lim2.check([Bucket("a", 1)], 1)
    before = dict(lim2._tat)
    assert not lim2.check([Bucket("b", 100), Bucket("a", 1)], 1).allowed
    assert "b" not in lim2._tat and lim2._tat == before
