-- Stage 4: custom data tables defined from the dashboard, and imports into them.
--
-- A tenant's tables are rows here, not real PostgreSQL tables: no DDL ever runs from a request, and
-- every value lives in data_rows.data (jsonb), validated by the application against data_fields.
-- Filters and sorting bind field keys and values as parameters; no request text becomes SQL.

CREATE TABLE data_tables (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    key            varchar(63)   NOT NULL CHECK (key ~ '^[a-z][a-z0-9_]{0,62}$'),
    name           varchar(200)  NOT NULL,
    description    varchar(1000),
    display_field  varchar(63),
    created_by     uuid,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, key)
);

CREATE TABLE data_fields (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid          NOT NULL,
    table_id            uuid          NOT NULL,
    key                 varchar(63)   NOT NULL CHECK (key ~ '^[a-z][a-z0-9_]{0,62}$'),
    label               varchar(200)  NOT NULL,
    type                varchar(16)   NOT NULL CHECK (type IN ('text', 'long_text', 'integer', 'decimal', 'boolean', 'date', 'datetime',
                                                             'email', 'url', 'phone', 'select', 'multi_select', 'reference', 'file', 'user')),
    required            boolean       NOT NULL DEFAULT false,
    is_unique           boolean       NOT NULL DEFAULT false,
    default_value       jsonb,
    options             jsonb         NOT NULL DEFAULT '{}',
    reference_table_id  uuid,
    help_text           varchar(500),
    position            integer       NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (table_id, key),
    CHECK ((type = 'reference') = (reference_table_id IS NOT NULL)),
    FOREIGN KEY (tenant_id, table_id) REFERENCES data_tables (tenant_id, id) ON DELETE CASCADE,
    -- A table cannot be deleted while another table's field points at it.
    FOREIGN KEY (tenant_id, reference_table_id) REFERENCES data_tables (tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE data_rows (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL,
    table_id    uuid        NOT NULL,
    data        jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(data) = 'object'),
    version     integer     NOT NULL DEFAULT 1,
    created_by  uuid,
    updated_by  uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, table_id) REFERENCES data_tables (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX data_rows_table_time ON data_rows (tenant_id, table_id, created_at DESC);
CREATE INDEX data_rows_owner ON data_rows (tenant_id, table_id, created_by);
CREATE INDEX data_rows_values ON data_rows USING gin (data jsonb_path_ops);

-- Unique fields: one row per (field, normalised value). The primary key is the guarantee.
CREATE TABLE data_unique_values (
    tenant_id   uuid NOT NULL,
    table_id    uuid NOT NULL,
    field_id    uuid NOT NULL,
    value_norm  text NOT NULL,
    row_id      uuid NOT NULL,
    PRIMARY KEY (field_id, value_norm),
    FOREIGN KEY (tenant_id, field_id) REFERENCES data_fields (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, row_id) REFERENCES data_rows (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX data_unique_values_row ON data_unique_values (row_id);

-- Imports: the uploaded file is parsed once into rows (strings); a background worker writes them in
-- batches with the permissions the importing user has at that moment. Each batch and the progress counter commit together, so a restarted worker resumes exactly
-- where the last committed batch ended.
CREATE TABLE data_imports (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid          NOT NULL,
    table_id         uuid          NOT NULL,
    filename         varchar(255)  NOT NULL,
    format           varchar(8)    NOT NULL CHECK (format IN ('csv', 'xlsx')),
    headers          jsonb         NOT NULL,
    rows             jsonb         NOT NULL,
    total_rows       integer       NOT NULL,
    status           varchar(10)   NOT NULL DEFAULT 'uploaded'
                     CHECK (status IN ('uploaded', 'queued', 'running', 'done', 'failed', 'cancelled')),
    mode             varchar(8)    CHECK (mode IN ('insert', 'upsert')),
    dry_run          boolean       NOT NULL DEFAULT false,
    mapping          jsonb,
    match_field      varchar(63),
    date_format      varchar(4)    CHECK (date_format IN ('iso', 'dmy', 'mdy')),
    processed_rows   integer       NOT NULL DEFAULT 0,
    inserted_rows    integer       NOT NULL DEFAULT 0,
    updated_rows     integer       NOT NULL DEFAULT 0,
    failed_rows      integer       NOT NULL DEFAULT 0,
    errors           jsonb         NOT NULL DEFAULT '[]',
    last_error       varchar(1000),
    lease_until      timestamptz,
    attempts         integer       NOT NULL DEFAULT 0,
    created_by       uuid,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    started_at       timestamptz,
    finished_at      timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, table_id) REFERENCES data_tables (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX data_imports_due ON data_imports (lease_until) WHERE status IN ('queued', 'running');
CREATE INDEX data_imports_time ON data_imports (tenant_id, created_at DESC);

DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['data_tables', 'data_fields', 'data_rows'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION touch_updated_at()', t || '_touch', t);
    END LOOP;
    FOREACH t IN ARRAY ARRAY['data_tables', 'data_fields', 'data_rows', 'data_unique_values'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_current_tenant_id()) '
                       'WITH CHECK (tenant_id = app_current_tenant_id())', t);
    END LOOP;
END $$;

-- Imports: the tenant's own; the platform context lets the worker find due jobs (it then processes
-- each job inside that job's tenant context).
ALTER TABLE data_imports ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_imports FORCE ROW LEVEL SECURITY;
CREATE POLICY import_access ON data_imports
    USING (tenant_id = app_current_tenant_id() OR app_is_platform())
    WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_platform());

GRANT SELECT, INSERT, UPDATE, DELETE ON data_tables, data_fields, data_rows, data_unique_values, data_imports TO ${appRole};
