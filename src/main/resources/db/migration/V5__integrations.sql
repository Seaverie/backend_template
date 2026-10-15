-- Stage 5: background jobs, webhooks, customer accounts, push notifications, maps, delivery tracking.

-- ------------------------------------------------------------------------------------------------
-- Secrets a tenant sets for an integration (FCM service account, map API key). Encrypted with AES-GCM.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE tenant_secrets (
    tenant_id   uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name        varchar(64) NOT NULL,
    secret_enc  text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, name)
);

-- ------------------------------------------------------------------------------------------------
-- Background jobs. Enqueued in the same transaction as the change that needs them; run by a leased
-- worker (SELECT ... FOR UPDATE SKIP LOCKED) with retries. Delivery is at least once: a handler can
-- run again if a worker dies after doing the work but before recording it, so handlers are written
-- to be safe to repeat.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE jobs (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid REFERENCES tenants (id) ON DELETE CASCADE,
    kind          varchar(40)   NOT NULL,
    payload       jsonb         NOT NULL DEFAULT '{}',
    status        varchar(8)    NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'done', 'dead')),
    run_at        timestamptz   NOT NULL DEFAULT now(),
    attempts      integer       NOT NULL DEFAULT 0,
    max_attempts  integer       NOT NULL DEFAULT 8,
    lease_until   timestamptz,
    last_error    varchar(1000),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    finished_at   timestamptz
);
CREATE INDEX jobs_due ON jobs (run_at) WHERE status IN ('queued', 'running');
CREATE INDEX jobs_status ON jobs (status, created_at DESC);

-- ------------------------------------------------------------------------------------------------
-- API keys for server-to-server access. Only the SHA-256 of a key is stored; a key acts with the
-- permissions of the role it was given.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE api_keys (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name          varchar(100) NOT NULL,
    hint          varchar(16)  NOT NULL,
    key_hash      char(64)     NOT NULL UNIQUE,
    role_id       uuid,
    created_by    uuid,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    expires_at    timestamptz,
    last_used_at  timestamptz,
    revoked_at    timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, role_id) REFERENCES roles (tenant_id, id) ON DELETE SET NULL (role_id)
);

-- ------------------------------------------------------------------------------------------------
-- Webhooks
-- ------------------------------------------------------------------------------------------------
CREATE TABLE webhook_endpoints (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid           NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    url              varchar(2000)  NOT NULL,
    description      varchar(300),
    events           text[]         NOT NULL,
    secret_enc       text           NOT NULL,
    is_active        boolean        NOT NULL DEFAULT true,
    failure_streak   integer        NOT NULL DEFAULT 0,
    disabled_reason  varchar(300),
    created_at       timestamptz    NOT NULL DEFAULT now(),
    updated_at       timestamptz    NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);

CREATE TABLE webhook_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    type        varchar(100) NOT NULL,
    payload     jsonb        NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);
CREATE INDEX webhook_events_time ON webhook_events (tenant_id, created_at DESC);

CREATE TABLE webhook_deliveries (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid          NOT NULL,
    endpoint_id       uuid          NOT NULL,
    event_id          uuid          NOT NULL,
    status            varchar(10)   NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'succeeded', 'failed', 'dead')),
    attempts          integer       NOT NULL DEFAULT 0,
    last_status_code  integer,
    last_error        varchar(1000),
    last_response     varchar(1000),
    duration_ms       integer,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    delivered_at      timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, endpoint_id) REFERENCES webhook_endpoints (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, event_id) REFERENCES webhook_events (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX webhook_deliveries_endpoint ON webhook_deliveries (tenant_id, endpoint_id, created_at DESC);

-- ------------------------------------------------------------------------------------------------
-- Customer accounts: a business's own customers, separate from its staff (app_users).
-- ------------------------------------------------------------------------------------------------
CREATE TABLE customers (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    email              varchar(320) NOT NULL,
    password_hash      varchar(255) NOT NULL,
    name               varchar(200),
    phone              varchar(40),
    marketing_opt_in   boolean      NOT NULL DEFAULT false,
    is_active          boolean      NOT NULL DEFAULT true,
    email_verified_at  timestamptz,
    failed_logins      integer      NOT NULL DEFAULT 0,
    locked_until       timestamptz,
    last_login_at      timestamptz,
    deleted_at         timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX customers_email ON customers (tenant_id, lower(email));
CREATE TRIGGER customers_touch BEFORE UPDATE ON customers FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE customer_sessions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL,
    customer_id     uuid        NOT NULL,
    refresh_hash    char(64)    NOT NULL,
    previous_hash   char(64),
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_used_at    timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    revoked_reason  varchar(64),
    user_agent      varchar(300),
    ip              inet,
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX customer_sessions_customer ON customer_sessions (tenant_id, customer_id);

CREATE TABLE customer_tokens (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL,
    customer_id  uuid        NOT NULL,
    purpose      varchar(8)  NOT NULL CHECK (purpose IN ('verify', 'reset')),
    token_hash   char(64)    NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    used_at      timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE orders ADD COLUMN customer_id uuid;
ALTER TABLE orders ADD CONSTRAINT orders_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id) ON DELETE SET NULL (customer_id);
CREATE INDEX orders_customer ON orders (tenant_id, customer_id) WHERE customer_id IS NOT NULL;

-- ------------------------------------------------------------------------------------------------
-- Push notifications
-- ------------------------------------------------------------------------------------------------
CREATE TABLE devices (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    owner_type    varchar(8)   NOT NULL CHECK (owner_type IN ('customer', 'user')),
    owner_id      uuid         NOT NULL,
    token         varchar(4096) NOT NULL,
    platform      varchar(8)   NOT NULL CHECK (platform IN ('android', 'ios', 'web')),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    last_seen_at  timestamptz  NOT NULL DEFAULT now(),
    -- Set when the push service says the token no longer works; registering again clears it.
    invalid_at    timestamptz,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX devices_token ON devices (tenant_id, md5(token));
CREATE INDEX devices_owner ON devices (tenant_id, owner_type, owner_id);

CREATE TABLE notifications (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    title         varchar(200) NOT NULL,
    body          varchar(1000) NOT NULL,
    data          jsonb        NOT NULL DEFAULT '{}',
    audience      jsonb        NOT NULL,
    source        varchar(16)  NOT NULL DEFAULT 'manual',
    target_count  integer      NOT NULL DEFAULT 0,
    created_by    uuid,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);
CREATE INDEX notifications_time ON notifications (tenant_id, created_at DESC);

-- One row per device per notification: a retried batch skips what was already sent.
CREATE TABLE notification_targets (
    tenant_id        uuid        NOT NULL,
    notification_id  uuid        NOT NULL,
    device_id        uuid        NOT NULL,
    status           varchar(8)  NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed')),
    error            varchar(300),
    sent_at          timestamptz,
    PRIMARY KEY (notification_id, device_id),
    FOREIGN KEY (tenant_id, notification_id) REFERENCES notifications (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, device_id) REFERENCES devices (tenant_id, id) ON DELETE CASCADE
);

-- ------------------------------------------------------------------------------------------------
-- Maps: geocoding results cached per tenant.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE geocode_cache (
    tenant_id   uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    provider    varchar(16) NOT NULL,
    kind        varchar(8)  NOT NULL CHECK (kind IN ('search', 'reverse')),
    query_hash  char(64)    NOT NULL,
    result      jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, provider, kind, query_hash)
);

-- ------------------------------------------------------------------------------------------------
-- Delivery tracking
-- ------------------------------------------------------------------------------------------------
CREATE TABLE deliveries (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid          NOT NULL,
    order_id           uuid          NOT NULL,
    driver_user_id     uuid,
    status             varchar(10)   NOT NULL DEFAULT 'assigned'
                       CHECK (status IN ('assigned', 'picked_up', 'in_transit', 'delivered', 'failed', 'cancelled')),
    destination_lat    double precision CHECK (destination_lat BETWEEN -90 AND 90),
    destination_lng    double precision CHECK (destination_lng BETWEEN -180 AND 180),
    destination_label  varchar(500),
    note               varchar(1000),
    last_lat           double precision,
    last_lng           double precision,
    last_accuracy_m    real,
    last_ping_at       timestamptz,
    proof_file_id      uuid,
    created_by         uuid,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    delivered_at       timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES orders (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, driver_user_id) REFERENCES app_users (tenant_id, id) ON DELETE SET NULL (driver_user_id),
    FOREIGN KEY (tenant_id, proof_file_id) REFERENCES files (tenant_id, id) ON DELETE SET NULL (proof_file_id)
);
CREATE INDEX deliveries_order ON deliveries (tenant_id, order_id);
CREATE INDEX deliveries_driver ON deliveries (tenant_id, driver_user_id, status);
CREATE TRIGGER deliveries_touch BEFORE UPDATE ON deliveries FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE delivery_pings (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     uuid             NOT NULL,
    delivery_id   uuid             NOT NULL,
    lat           double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng           double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
    accuracy_m    real CHECK (accuracy_m >= 0),
    speed_mps     real CHECK (speed_mps >= 0),
    heading       real CHECK (heading >= 0 AND heading < 360),
    recorded_at   timestamptz      NOT NULL,
    received_at   timestamptz      NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, delivery_id) REFERENCES deliveries (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX delivery_pings_delivery ON delivery_pings (tenant_id, delivery_id, recorded_at DESC);
CREATE INDEX delivery_pings_received ON delivery_pings (received_at);

-- ------------------------------------------------------------------------------------------------
-- Row-level security
-- ------------------------------------------------------------------------------------------------
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['tenant_secrets', 'api_keys', 'webhook_endpoints', 'webhook_events', 'webhook_deliveries', 'customers',
                             'customer_sessions', 'customer_tokens', 'devices', 'notifications', 'notification_targets',
                             'geocode_cache', 'deliveries'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_current_tenant_id()) '
                       'WITH CHECK (tenant_id = app_current_tenant_id())', t);
    END LOOP;
END $$;

-- The job queue and ping retention are read by platform-level workers, which then act inside each
-- job's own tenant context.
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['jobs', 'delivery_pings'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_or_platform ON %I USING (tenant_id = app_current_tenant_id() OR app_is_platform()) '
                       'WITH CHECK (tenant_id = app_current_tenant_id() OR app_is_platform())', t);
    END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_secrets, api_keys, jobs, webhook_endpoints, webhook_events, webhook_deliveries, customers,
    customer_sessions, customer_tokens, devices, notifications, notification_targets, geocode_cache, deliveries, delivery_pings TO ${appRole};
