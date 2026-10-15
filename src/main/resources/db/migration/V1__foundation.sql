-- Foundation: tenants, platform admins, tenant users, roles, modules, settings, audit log.
--
-- Isolation model
--   * Migrations run as the schema owner (tc_owner); the API runs as ${appRole} (NOSUPERUSER, NOBYPASSRLS).
--   * Every tenant-owned table has ENABLE + FORCE ROW LEVEL SECURITY, so even the owner is filtered.
--   * The API sets app.tenant_id / app.is_platform with set_config(..., true) at the start of each
--     transaction. With no tenant set, the predicate is NULL: no rows are visible and writes are rejected.

CREATE FUNCTION app_current_tenant_id() RETURNS uuid LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE FUNCTION app_is_platform() RETURNS boolean LANGUAGE sql STABLE
AS $$ SELECT coalesce(current_setting('app.is_platform', true), '') = 'on' $$;

REVOKE ALL ON FUNCTION app_current_tenant_id(), app_is_platform() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_current_tenant_id(), app_is_platform() TO ${appRole};

CREATE FUNCTION touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END $$;

-- ------------------------------------------------------------------------------------------------
-- Tenants
-- ------------------------------------------------------------------------------------------------
CREATE TABLE tenants (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug               varchar(63)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    name               varchar(200) NOT NULL,
    status             varchar(16)  NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'offboarding')),
    rate_limit_per_min integer CHECK (rate_limit_per_min IS NULL OR rate_limit_per_min > 0),
    authz_version      bigint       NOT NULL DEFAULT 1,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE TRIGGER tenants_touch BEFORE UPDATE ON tenants FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- Readable by everyone (the sign-in page resolves a slug before any tenant is known); only platform
-- transactions may insert; a tenant may update its own row, but a trigger blocks platform-owned columns.
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenants_read   ON tenants FOR SELECT USING (true);
CREATE POLICY tenants_insert ON tenants FOR INSERT WITH CHECK (app_is_platform());
CREATE POLICY tenants_update ON tenants FOR UPDATE
    USING (app_is_platform() OR id = app_current_tenant_id())
    WITH CHECK (app_is_platform() OR id = app_current_tenant_id());

CREATE FUNCTION tenants_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.authz_version < OLD.authz_version THEN
        RAISE EXCEPTION 'authz_version must not decrease' USING ERRCODE = 'check_violation';
    END IF;
    IF NOT app_is_platform() AND (NEW.id IS DISTINCT FROM OLD.id OR NEW.slug IS DISTINCT FROM OLD.slug
        OR NEW.status IS DISTINCT FROM OLD.status OR NEW.rate_limit_per_min IS DISTINCT FROM OLD.rate_limit_per_min) THEN
        RAISE EXCEPTION 'tenant may not change platform-controlled columns' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tenants_guard BEFORE UPDATE ON tenants FOR EACH ROW EXECUTE FUNCTION tenants_guard();
GRANT SELECT, INSERT, UPDATE ON tenants TO ${appRole};

-- ------------------------------------------------------------------------------------------------
-- Platform admins ("super admins") and their sessions: platform transactions only.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE platform_admins (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email           varchar(320) NOT NULL,
    full_name       varchar(200),
    password_hash   varchar(255) NOT NULL,
    is_active       boolean      NOT NULL DEFAULT true,
    mfa_enabled     boolean      NOT NULL DEFAULT false,
    mfa_secret_enc  text,
    mfa_last_step   bigint,
    failed_logins   integer      NOT NULL DEFAULT 0,
    locked_until    timestamptz,
    last_login_at   timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX platform_admins_email ON platform_admins (lower(email));
CREATE TRIGGER platform_admins_touch BEFORE UPDATE ON platform_admins FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE platform_sessions (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id      uuid        NOT NULL REFERENCES platform_admins (id) ON DELETE CASCADE,
    mfa_verified  boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    revoked_at    timestamptz
);
CREATE INDEX platform_sessions_admin ON platform_sessions (admin_id);

DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['platform_admins', 'platform_sessions'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY platform_only ON %I USING (app_is_platform()) WITH CHECK (app_is_platform())', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON platform_admins, platform_sessions TO ${appRole};

-- ------------------------------------------------------------------------------------------------
-- Tenant-owned tables
-- ------------------------------------------------------------------------------------------------
CREATE TABLE app_users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    email           varchar(320) NOT NULL,
    password_hash   varchar(255) NOT NULL,
    full_name       varchar(200),
    is_active       boolean      NOT NULL DEFAULT true,
    is_tenant_admin boolean      NOT NULL DEFAULT false,
    failed_logins   integer      NOT NULL DEFAULT 0,
    locked_until    timestamptz,
    last_login_at   timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX app_users_email ON app_users (tenant_id, lower(email));
CREATE TRIGGER app_users_touch BEFORE UPDATE ON app_users FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- Refresh tokens are opaque; only their SHA-256 is stored. Rotation replaces the hash on every use.
CREATE TABLE auth_sessions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    refresh_hash    char(64)    NOT NULL,
    previous_hash   char(64),
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_used_at    timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    revoked_reason  varchar(64),
    user_agent      varchar(300),
    ip              inet,
    FOREIGN KEY (tenant_id, user_id) REFERENCES app_users (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX auth_sessions_user ON auth_sessions (tenant_id, user_id);

CREATE TABLE roles (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         varchar(100) NOT NULL,
    description  varchar(500),
    is_system    boolean      NOT NULL DEFAULT false,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name)
);
CREATE TRIGGER roles_touch BEFORE UPDATE ON roles FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE role_permissions (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid         NOT NULL,
    role_id          uuid         NOT NULL,
    resource         varchar(100) NOT NULL,
    action           varchar(16)  NOT NULL CHECK (action IN ('read', 'create', 'update', 'delete', 'manage')),
    row_scope        varchar(8)   NOT NULL DEFAULT 'all' CHECK (row_scope IN ('all', 'own')),
    readable_fields  text[],
    writable_fields  text[],
    FOREIGN KEY (tenant_id, role_id) REFERENCES roles (tenant_id, id) ON DELETE CASCADE,
    UNIQUE (role_id, resource, action)
);

CREATE TABLE user_roles (
    tenant_id  uuid NOT NULL,
    user_id    uuid NOT NULL,
    role_id    uuid NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES app_users (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, role_id) REFERENCES roles (tenant_id, id) ON DELETE CASCADE
);

-- Feature modules switched on per tenant by the platform admin. config holds module options.
CREATE TABLE tenant_modules (
    tenant_id   uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    module      varchar(40) NOT NULL,
    enabled     boolean     NOT NULL DEFAULT false,
    config      jsonb       NOT NULL DEFAULT '{}',
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, module)
);

-- Per-tenant settings edited from the dashboard (branding, locale, money).
CREATE TABLE tenant_settings (
    tenant_id      uuid PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    display_name   varchar(200),
    primary_color  varchar(7) CHECK (primary_color IS NULL OR primary_color ~ '^#[0-9a-fA-F]{6}$'),
    currency       char(3)      NOT NULL DEFAULT 'USD' CHECK (currency ~ '^[A-Z]{3}$'),
    timezone       varchar(64)  NOT NULL DEFAULT 'UTC',
    locale         varchar(16)  NOT NULL DEFAULT 'en',
    extra          jsonb        NOT NULL DEFAULT '{}',
    updated_at     timestamptz  NOT NULL DEFAULT now()
);
CREATE TRIGGER tenant_settings_touch BEFORE UPDATE ON tenant_settings FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['app_users', 'auth_sessions', 'roles', 'role_permissions', 'user_roles',
                             'tenant_modules', 'tenant_settings'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_current_tenant_id()) '
                       'WITH CHECK (tenant_id = app_current_tenant_id())', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON app_users, auth_sessions, roles, role_permissions, user_roles,
    tenant_modules, tenant_settings TO ${appRole};

-- ------------------------------------------------------------------------------------------------
-- Audit log: append-only. Tenant rows are visible to their tenant; platform rows (tenant_id NULL)
-- only to platform transactions. The runtime role may INSERT and SELECT, never UPDATE or DELETE.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid,
    actor_type   varchar(16)  NOT NULL,
    actor_id     uuid,
    action       varchar(64)  NOT NULL,
    resource     varchar(100) NOT NULL,
    resource_id  varchar(100),
    request_id   varchar(64),
    ip           inet,
    detail       jsonb        NOT NULL DEFAULT '{}',
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX audit_logs_tenant_time ON audit_logs (tenant_id, created_at DESC);
ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_isolation ON audit_logs
    USING (tenant_id = app_current_tenant_id() OR (tenant_id IS NULL AND app_is_platform())
           OR (app_is_platform() AND current_setting('app.platform_reads_all', true) = 'on'))
    WITH CHECK (tenant_id = app_current_tenant_id() OR (tenant_id IS NULL AND app_is_platform()));
GRANT SELECT, INSERT ON audit_logs TO ${appRole};

CREATE FUNCTION audit_logs_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'audit_logs is append-only' USING ERRCODE = 'insufficient_privilege'; END $$;
CREATE TRIGGER audit_logs_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION audit_logs_immutable();
