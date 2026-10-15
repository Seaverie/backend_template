-- Stage 6: data exports and permanent removal of a tenant (offboarding).

-- Exports of a tenant's data (a zip of JSON lines plus its files). Kept for a few days after the
-- tenant itself is removed, so the export can still be handed over; hence no foreign key to tenants.
CREATE TABLE tenant_exports (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid         NOT NULL,
    tenant_slug        varchar(63)  NOT NULL,
    requested_by_type  varchar(16)  NOT NULL CHECK (requested_by_type IN ('platform', 'user')),
    requested_by       uuid,
    status             varchar(8)   NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'done', 'failed')),
    storage            varchar(16),
    storage_key        varchar(400),
    size_bytes         bigint,
    error              varchar(1000),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    finished_at        timestamptz,
    expires_at         timestamptz
);
CREATE INDEX tenant_exports_tenant ON tenant_exports (tenant_id, created_at DESC);
ALTER TABLE tenant_exports ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_exports FORCE ROW LEVEL SECURITY;
CREATE POLICY exports_access ON tenant_exports
    USING (tenant_id = app_current_tenant_id() OR app_is_platform())
    WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_platform());
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_exports TO ${appRole};

-- Purging. Only the schema owner (never the API's runtime role, which has no DELETE on tenants and
-- cannot become the owner), in a transaction that declares app.purging, may delete a tenant row or the
-- append-only audit and stock ledgers.
CREATE POLICY tenants_purge ON tenants FOR DELETE USING (app_is_platform() AND current_setting('app.purging', true) = 'on');

CREATE OR REPLACE FUNCTION audit_logs_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_user = '${ownerRole}' AND current_setting('app.purging', true) = 'on' THEN RETURN NULL; END IF;
    RAISE EXCEPTION 'audit_logs is append-only' USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE OR REPLACE FUNCTION stock_movements_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_user = '${ownerRole}' AND current_setting('app.purging', true) = 'on' THEN RETURN NULL; END IF;
    RAISE EXCEPTION 'stock_movements is append-only' USING ERRCODE = 'insufficient_privilege';
END $$;
