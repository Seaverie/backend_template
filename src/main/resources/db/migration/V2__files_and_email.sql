-- Stage 2: platform settings, files, email (settings, templates, outbox), invitation and reset tokens.

-- ------------------------------------------------------------------------------------------------
-- Platform settings edited from the platform dashboard (general, storage, email).
-- Secrets live in secret_enc (AES-GCM, see Crypto); value never contains them.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE platform_settings (
    key         varchar(64) PRIMARY KEY,
    value       jsonb       NOT NULL DEFAULT '{}',
    secret_enc  text,
    updated_at  timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE platform_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_only ON platform_settings USING (app_is_platform()) WITH CHECK (app_is_platform());
GRANT SELECT, INSERT, UPDATE ON platform_settings TO ${appRole};

-- ------------------------------------------------------------------------------------------------
-- Files. The object itself is in a store (local disk or S3-compatible); each row remembers which
-- store and key, so switching the default store later never breaks existing files.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE files (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    uploaded_by     uuid,
    filename        varchar(255)  NOT NULL,
    content_type    varchar(100)  NOT NULL,
    size_bytes      bigint        NOT NULL CHECK (size_bytes >= 0),
    sha256          char(64)      NOT NULL,
    purpose         varchar(32)   NOT NULL DEFAULT 'attachment',
    is_public       boolean       NOT NULL DEFAULT false,
    storage         varchar(16)   NOT NULL CHECK (storage IN ('local', 's3')),
    storage_key     varchar(400)  NOT NULL,
    thumb_key       varchar(400),
    width           integer,
    height          integer,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);
CREATE INDEX files_tenant_time ON files (tenant_id, created_at DESC);

ALTER TABLE tenant_settings ADD COLUMN logo_file_id uuid;
ALTER TABLE tenant_settings ADD CONSTRAINT tenant_settings_logo
    FOREIGN KEY (tenant_id, logo_file_id) REFERENCES files (tenant_id, id) ON DELETE SET NULL (logo_file_id);

-- ------------------------------------------------------------------------------------------------
-- Invitations and password resets. Only the SHA-256 of a token is stored; tokens are single-use.
-- ------------------------------------------------------------------------------------------------
ALTER TABLE app_users ADD COLUMN password_set boolean NOT NULL DEFAULT true;
ALTER TABLE app_users ADD COLUMN invited_at timestamptz;

CREATE TABLE user_tokens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    purpose     varchar(16) NOT NULL CHECK (purpose IN ('invite', 'reset')),
    token_hash  char(64)    NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, user_id) REFERENCES app_users (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX user_tokens_user ON user_tokens (tenant_id, user_id, purpose);

-- ------------------------------------------------------------------------------------------------
-- Email: a tenant may use its own SMTP server instead of the platform's.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE tenant_smtp (
    tenant_id     uuid PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    host          varchar(255) NOT NULL,
    port          integer      NOT NULL CHECK (port IN (25, 465, 587, 2525)),
    username      varchar(320),
    password_enc  text,
    security      varchar(8)   NOT NULL CHECK (security IN ('starttls', 'ssl', 'none')),
    from_address  varchar(320) NOT NULL,
    from_name     varchar(200),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

-- Template overrides. tenant_id NULL = platform-wide override of the built-in default.
CREATE TABLE email_templates (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid REFERENCES tenants (id) ON DELETE CASCADE,
    key         varchar(64)  NOT NULL,
    subject     varchar(300) NOT NULL,
    html        text         NOT NULL,
    text        text         NOT NULL,
    updated_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX email_templates_key ON email_templates (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), key);

-- Outgoing mail queue. A background worker sends with retries; rows are kept as the email log.
CREATE TABLE email_outbox (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid REFERENCES tenants (id) ON DELETE CASCADE,
    to_address       varchar(320) NOT NULL,
    subject          varchar(300) NOT NULL,
    html             text         NOT NULL,
    text             text         NOT NULL,
    template_key     varchar(64),
    status           varchar(8)   NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed', 'dead')),
    attempts         integer      NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz  NOT NULL DEFAULT now(),
    last_error       varchar(1000),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    sent_at          timestamptz
);
CREATE INDEX email_outbox_due ON email_outbox (next_attempt_at) WHERE status IN ('pending', 'failed');
CREATE INDEX email_outbox_tenant_time ON email_outbox (tenant_id, created_at DESC);

-- Tenant-owned tables: the usual isolation policy.
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['files', 'user_tokens', 'tenant_smtp'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_current_tenant_id()) '
                       'WITH CHECK (tenant_id = app_current_tenant_id())', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON files, user_tokens, tenant_smtp TO ${appRole};

-- Templates: a tenant sees its own rows and the platform-wide ones; only the platform writes those.
ALTER TABLE email_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_templates FORCE ROW LEVEL SECURITY;
CREATE POLICY templates_read ON email_templates FOR SELECT
    USING (tenant_id = app_current_tenant_id() OR tenant_id IS NULL);
CREATE POLICY templates_write ON email_templates FOR ALL
    USING (tenant_id = app_current_tenant_id() OR (tenant_id IS NULL AND app_is_platform()))
    WITH CHECK (tenant_id = app_current_tenant_id() OR (tenant_id IS NULL AND app_is_platform()));
GRANT SELECT, INSERT, UPDATE, DELETE ON email_templates TO ${appRole};

-- Outbox: a tenant sees its own mail; the platform (and the sending worker) sees all of it.
ALTER TABLE email_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_access ON email_outbox
    USING (tenant_id = app_current_tenant_id() OR app_is_platform())
    WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_platform());
GRANT SELECT, INSERT, UPDATE ON email_outbox TO ${appRole};
