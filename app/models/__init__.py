from app.models.api_keys import TenantApiKey
from app.models.audit import SystemAuditLog
from app.models.base import DYNAMIC_SCHEMA, Base
from app.models.identity import AppUser, AuthRefreshToken, AuthSession
from app.models.metadata import AppField, AppTable, CrudMode, FieldType, SchemaMigration, SchemaState
from app.models.rbac import Action, AppPermission, AppRole, AppRolePermission, AppUserRole, RowScope
from app.models.tenant import AppTenant, IsolationMode, PlatformAdmin, PlatformSession, TenantStatus

# Tenant-owned tables in the public schema that MUST have RLS enabled and forced.
# tests/integration/test_rls.py asserts every one of these has a tenant policy.
TENANT_OWNED_TABLES = (
    "app_users",
    "auth_sessions",
    "auth_refresh_tokens",
    "app_roles",
    "app_permissions",
    "app_role_permissions",
    "app_user_roles",
    "tenant_api_keys",
    "system_audit_logs",
)

__all__ = [
    "DYNAMIC_SCHEMA",
    "TENANT_OWNED_TABLES",
    "Action",
    "AppField",
    "AppPermission",
    "AppRole",
    "AppRolePermission",
    "AppTable",
    "AppTenant",
    "AppUser",
    "AppUserRole",
    "AuthRefreshToken",
    "AuthSession",
    "Base",
    "CrudMode",
    "FieldType",
    "IsolationMode",
    "PlatformAdmin",
    "PlatformSession",
    "RowScope",
    "SchemaMigration",
    "SchemaState",
    "SystemAuditLog",
    "TenantApiKey",
    "TenantStatus",
]
