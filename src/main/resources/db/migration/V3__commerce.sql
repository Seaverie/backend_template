-- Stage 3: commerce. Catalog, inventory (warehouses, bins, stock ledger), purchasing, orders with a
-- lifecycle each tenant edits from the dashboard, payments, returns, idempotency keys.
--
-- Every reference between tenant tables is a composite (tenant_id, id) foreign key. Foreign-key checks
-- are not filtered by row-level security, so a plain id reference could point at another tenant's row;
-- the composite key makes that impossible.
--
-- Money is numeric(14,2) in the tenant's currency.

-- Per-tenant sequences (order numbers, purchase order numbers, return numbers). The counter row is
-- updated inside the creating transaction, so numbers have no gaps and are never reused.
CREATE TABLE tenant_counters (
    tenant_id  uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name       varchar(32) NOT NULL,
    value      bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, name)
);

-- ------------------------------------------------------------------------------------------------
-- Catalog
-- ------------------------------------------------------------------------------------------------
CREATE TABLE product_categories (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name        varchar(200) NOT NULL,
    slug        varchar(100) NOT NULL CHECK (slug ~ '^[a-z0-9][a-z0-9-]{0,99}$'),
    parent_id   uuid,
    sort_order  integer      NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, slug),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES product_categories (tenant_id, id) ON DELETE SET NULL (parent_id),
    CHECK (parent_id IS DISTINCT FROM id)
);

CREATE TABLE products (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         varchar(300) NOT NULL,
    slug         varchar(200) NOT NULL CHECK (slug ~ '^[a-z0-9][a-z0-9-]{0,199}$'),
    description  text CHECK (description IS NULL OR length(description) <= 20000),
    status       varchar(16)  NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'archived')),
    category_id  uuid,
    attributes   jsonb        NOT NULL DEFAULT '{}',
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, slug),
    FOREIGN KEY (tenant_id, category_id) REFERENCES product_categories (tenant_id, id) ON DELETE SET NULL (category_id)
);
CREATE INDEX products_tenant_status ON products (tenant_id, status, created_at DESC);

CREATE TABLE product_variants (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid          NOT NULL,
    product_id           uuid          NOT NULL,
    sku                  varchar(64)   NOT NULL CHECK (sku ~ '^[A-Za-z0-9._/-]{1,64}$'),
    name                 varchar(200),
    options              jsonb         NOT NULL DEFAULT '{}',
    price                numeric(14,2) NOT NULL CHECK (price >= 0),
    compare_at_price     numeric(14,2) CHECK (compare_at_price >= 0),
    cost                 numeric(14,2) CHECK (cost >= 0),
    barcode              varchar(64),
    weight_grams         integer CHECK (weight_grams >= 0),
    track_inventory      boolean       NOT NULL DEFAULT true,
    low_stock_threshold  integer CHECK (low_stock_threshold >= 0),
    is_active            boolean       NOT NULL DEFAULT true,
    sort_order           integer       NOT NULL DEFAULT 0,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, sku),
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX product_variants_product ON product_variants (tenant_id, product_id);

CREATE TABLE product_images (
    tenant_id   uuid    NOT NULL,
    product_id  uuid    NOT NULL,
    file_id     uuid    NOT NULL,
    sort_order  integer NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id, file_id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, file_id) REFERENCES files (tenant_id, id) ON DELETE CASCADE
);

-- ------------------------------------------------------------------------------------------------
-- Inventory. stock_levels holds the current quantity per variant and bin; the constraints make it
-- impossible to go below zero or to reserve more than is on hand, whatever the application does.
-- stock_movements is the append-only ledger of every on-hand change.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE warehouses (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    code        varchar(32)  NOT NULL CHECK (code ~ '^[A-Za-z0-9._-]{1,32}$'),
    name        varchar(200) NOT NULL,
    address     text CHECK (address IS NULL OR length(address) <= 2000),
    is_active   boolean      NOT NULL DEFAULT true,
    is_default  boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);
CREATE UNIQUE INDEX warehouses_one_default ON warehouses (tenant_id) WHERE is_default;

CREATE TABLE bins (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid         NOT NULL,
    warehouse_id  uuid         NOT NULL,
    code          varchar(32)  NOT NULL CHECK (code ~ '^[A-Za-z0-9._-]{1,32}$'),
    name          varchar(200),
    is_active     boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, warehouse_id, code),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE stock_levels (
    tenant_id   uuid        NOT NULL,
    variant_id  uuid        NOT NULL,
    bin_id      uuid        NOT NULL,
    on_hand     integer     NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
    reserved    integer     NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, variant_id, bin_id),
    CHECK (reserved <= on_hand),
    FOREIGN KEY (tenant_id, variant_id) REFERENCES product_variants (tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, bin_id) REFERENCES bins (tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX stock_levels_bin ON stock_levels (tenant_id, bin_id);

CREATE TABLE stock_movements (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL,
    variant_id  uuid        NOT NULL,
    bin_id      uuid        NOT NULL,
    quantity    integer     NOT NULL CHECK (quantity <> 0),
    kind        varchar(16) NOT NULL CHECK (kind IN ('adjustment', 'transfer', 'purchase', 'sale', 'return')),
    ref_type    varchar(32),
    ref_id      uuid,
    note        varchar(500),
    actor_id    uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, variant_id) REFERENCES product_variants (tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, bin_id) REFERENCES bins (tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX stock_movements_variant ON stock_movements (tenant_id, variant_id, created_at DESC);
CREATE INDEX stock_movements_time ON stock_movements (tenant_id, created_at DESC);

CREATE FUNCTION stock_movements_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'stock_movements is append-only' USING ERRCODE = 'insufficient_privilege'; END $$;
CREATE TRIGGER stock_movements_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON stock_movements
    FOR EACH STATEMENT EXECUTE FUNCTION stock_movements_immutable();

-- ------------------------------------------------------------------------------------------------
-- Purchasing
-- ------------------------------------------------------------------------------------------------
CREATE TABLE suppliers (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name        varchar(200) NOT NULL,
    email       varchar(320),
    phone       varchar(40),
    notes       text CHECK (notes IS NULL OR length(notes) <= 5000),
    is_active   boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);

CREATE TABLE purchase_orders (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL,
    number        bigint      NOT NULL,
    supplier_id   uuid        NOT NULL,
    warehouse_id  uuid        NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'draft'
                  CHECK (status IN ('draft', 'ordered', 'partially_received', 'received', 'cancelled')),
    currency      char(3)     NOT NULL,
    expected_at   date,
    notes         text CHECK (notes IS NULL OR length(notes) <= 5000),
    created_by    uuid,
    ordered_at    timestamptz,
    closed_at     timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses (tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX purchase_orders_time ON purchase_orders (tenant_id, created_at DESC);

CREATE TABLE purchase_order_lines (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid          NOT NULL,
    po_id              uuid          NOT NULL,
    variant_id         uuid          NOT NULL,
    quantity           integer       NOT NULL CHECK (quantity > 0),
    received_quantity  integer       NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    unit_cost          numeric(14,2) NOT NULL DEFAULT 0 CHECK (unit_cost >= 0),
    UNIQUE (tenant_id, id),
    UNIQUE (po_id, variant_id),
    CHECK (received_quantity <= quantity),
    FOREIGN KEY (tenant_id, po_id) REFERENCES purchase_orders (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id) REFERENCES product_variants (tenant_id, id) ON DELETE RESTRICT
);

-- ------------------------------------------------------------------------------------------------
-- Orders. Statuses and the allowed moves between them are rows, edited from the dashboard. Each
-- status says what happens to stock when an order enters it.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE order_statuses (
    tenant_id       uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    code            varchar(32)  NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]{0,31}$'),
    label           varchar(100) NOT NULL,
    color           varchar(7) CHECK (color IS NULL OR color ~ '^#[0-9a-fA-F]{6}$'),
    stock_effect    varchar(8)   NOT NULL DEFAULT 'none' CHECK (stock_effect IN ('none', 'reserve', 'deduct', 'release')),
    is_initial      boolean      NOT NULL DEFAULT false,
    is_final        boolean      NOT NULL DEFAULT false,
    counts_as_sale  boolean      NOT NULL DEFAULT false,
    sort_order      integer      NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, code)
);
CREATE UNIQUE INDEX order_statuses_one_initial ON order_statuses (tenant_id) WHERE is_initial;

CREATE TABLE order_transitions (
    tenant_id  uuid        NOT NULL,
    from_code  varchar(32) NOT NULL,
    to_code    varchar(32) NOT NULL,
    PRIMARY KEY (tenant_id, from_code, to_code),
    CHECK (from_code <> to_code),
    FOREIGN KEY (tenant_id, from_code) REFERENCES order_statuses (tenant_id, code) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, to_code) REFERENCES order_statuses (tenant_id, code) ON DELETE CASCADE
);

CREATE TABLE orders (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid          NOT NULL,
    number            bigint        NOT NULL,
    status            varchar(32)   NOT NULL,
    source            varchar(16)   NOT NULL CHECK (source IN ('dashboard', 'storefront', 'api')),
    customer_name     varchar(200),
    customer_email    varchar(320),
    customer_phone    varchar(40),
    shipping_address  jsonb,
    billing_address   jsonb,
    notes             text CHECK (notes IS NULL OR length(notes) <= 5000),
    currency          char(3)       NOT NULL,
    subtotal          numeric(14,2) NOT NULL CHECK (subtotal >= 0),
    discount_total    numeric(14,2) NOT NULL DEFAULT 0 CHECK (discount_total >= 0),
    shipping_total    numeric(14,2) NOT NULL DEFAULT 0 CHECK (shipping_total >= 0),
    tax_total         numeric(14,2) NOT NULL DEFAULT 0 CHECK (tax_total >= 0),
    tax_inclusive     boolean       NOT NULL DEFAULT false,
    total             numeric(14,2) NOT NULL CHECK (total >= 0),
    paid_total        numeric(14,2) NOT NULL DEFAULT 0 CHECK (paid_total >= 0),
    refunded_total    numeric(14,2) NOT NULL DEFAULT 0 CHECK (refunded_total >= 0),
    payment_status    varchar(20)   NOT NULL DEFAULT 'unpaid'
                      CHECK (payment_status IN ('unpaid', 'partially_paid', 'paid', 'partially_refunded', 'refunded')),
    stock_state       varchar(10)   NOT NULL DEFAULT 'none' CHECK (stock_state IN ('none', 'reserved', 'deducted', 'released')),
    created_by        uuid,
    placed_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    CHECK (refunded_total <= paid_total),
    FOREIGN KEY (tenant_id, status) REFERENCES order_statuses (tenant_id, code) ON DELETE RESTRICT
);
CREATE INDEX orders_time ON orders (tenant_id, placed_at DESC);
CREATE INDEX orders_status ON orders (tenant_id, status);
CREATE INDEX orders_email ON orders (tenant_id, lower(customer_email));

CREATE TABLE order_items (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid          NOT NULL,
    order_id    uuid          NOT NULL,
    variant_id  uuid          NOT NULL,
    sku         varchar(64)   NOT NULL,
    name        varchar(500)  NOT NULL,
    options     jsonb         NOT NULL DEFAULT '{}',
    unit_price  numeric(14,2) NOT NULL CHECK (unit_price >= 0),
    quantity    integer       NOT NULL CHECK (quantity > 0),
    line_total  numeric(14,2) NOT NULL CHECK (line_total >= 0),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES orders (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id) REFERENCES product_variants (tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX order_items_order ON order_items (tenant_id, order_id);
CREATE INDEX order_items_variant ON order_items (tenant_id, variant_id);

-- Which bins an order's stock was taken from, and whether it is reserved, shipped (deducted) or released.
CREATE TABLE order_allocations (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL,
    order_id       uuid        NOT NULL,
    order_item_id  uuid        NOT NULL,
    variant_id     uuid        NOT NULL,
    bin_id         uuid        NOT NULL,
    quantity       integer     NOT NULL CHECK (quantity > 0),
    state          varchar(10) NOT NULL CHECK (state IN ('reserved', 'deducted', 'released')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, order_id) REFERENCES orders (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, order_item_id) REFERENCES order_items (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, variant_id) REFERENCES product_variants (tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, bin_id) REFERENCES bins (tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX order_allocations_order ON order_allocations (tenant_id, order_id);

CREATE TABLE order_history (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid         NOT NULL,
    order_id     uuid         NOT NULL,
    from_status  varchar(32),
    to_status    varchar(32)  NOT NULL,
    note         varchar(1000),
    actor_type   varchar(16)  NOT NULL,
    actor_id     uuid,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, order_id) REFERENCES orders (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX order_history_order ON order_history (tenant_id, order_id, created_at);

-- ------------------------------------------------------------------------------------------------
-- Payments. Gateway secrets are encrypted (AES-GCM) and never returned by the API.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE payment_gateways (
    tenant_id           uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    provider            varchar(16) NOT NULL CHECK (provider IN ('stripe', 'sandbox')),
    is_enabled          boolean     NOT NULL DEFAULT false,
    config              jsonb       NOT NULL DEFAULT '{}',
    secret_enc          text,
    webhook_secret_enc  text,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, provider)
);

-- Returns (declared before payments so a refund can point at the return it settles).
CREATE TABLE rmas (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid          NOT NULL,
    number        bigint        NOT NULL,
    order_id      uuid          NOT NULL,
    status        varchar(16)   NOT NULL DEFAULT 'requested'
                  CHECK (status IN ('requested', 'approved', 'rejected', 'received', 'refunded', 'closed')),
    reason        varchar(500),
    note          text CHECK (note IS NULL OR length(note) <= 5000),
    refund_total  numeric(14,2) NOT NULL DEFAULT 0 CHECK (refund_total >= 0),
    created_by    uuid,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    FOREIGN KEY (tenant_id, order_id) REFERENCES orders (tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX rmas_time ON rmas (tenant_id, created_at DESC);

CREATE TABLE rma_items (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid    NOT NULL,
    rma_id              uuid    NOT NULL,
    order_item_id       uuid    NOT NULL,
    quantity            integer NOT NULL CHECK (quantity > 0),
    received_quantity   integer NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    restocked_quantity  integer NOT NULL DEFAULT 0 CHECK (restocked_quantity >= 0),
    UNIQUE (tenant_id, id),
    UNIQUE (rma_id, order_item_id),
    CHECK (received_quantity <= quantity),
    CHECK (restocked_quantity <= received_quantity),
    FOREIGN KEY (tenant_id, rma_id) REFERENCES rmas (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, order_item_id) REFERENCES order_items (tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE payments (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid          NOT NULL,
    order_id         uuid          NOT NULL,
    parent_id        uuid,
    rma_id           uuid,
    kind             varchar(8)    NOT NULL CHECK (kind IN ('charge', 'refund')),
    provider         varchar(16)   NOT NULL CHECK (provider IN ('manual', 'stripe', 'sandbox')),
    method           varchar(32),
    status           varchar(10)   NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed', 'cancelled')),
    amount           numeric(14,2) NOT NULL CHECK (amount > 0),
    currency         char(3)       NOT NULL,
    provider_ref     varchar(255),
    reference        varchar(200),
    failure_message  varchar(500),
    created_by       uuid,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    CHECK ((kind = 'refund') = (parent_id IS NOT NULL)),
    FOREIGN KEY (tenant_id, order_id) REFERENCES orders (tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, parent_id) REFERENCES payments (tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, rma_id) REFERENCES rmas (tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX payments_order ON payments (tenant_id, order_id);
CREATE UNIQUE INDEX payments_provider_ref ON payments (tenant_id, provider, provider_ref) WHERE provider_ref IS NOT NULL;

-- Webhook event ids already processed, so a redelivered event is applied once.
CREATE TABLE payment_events (
    tenant_id    uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    provider     varchar(16)  NOT NULL,
    event_id     varchar(255) NOT NULL,
    received_at  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, provider, event_id)
);

-- ------------------------------------------------------------------------------------------------
-- Idempotency keys: a retried create (same key, same body) returns the original result instead of
-- creating a second record. The key row is written in the same transaction as the record.
-- ------------------------------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    tenant_id     uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    scope         varchar(32)  NOT NULL,
    key           varchar(100) NOT NULL,
    request_hash  char(64)     NOT NULL,
    result_id     uuid,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, scope, key)
);
CREATE INDEX idempotency_keys_time ON idempotency_keys (created_at);

-- updated_at maintenance
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['product_categories', 'products', 'product_variants', 'warehouses', 'suppliers',
                             'purchase_orders', 'orders', 'order_allocations', 'rmas', 'payments'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION touch_updated_at()', t || '_touch', t);
    END LOOP;
END $$;

-- Row-level security: the usual tenant isolation on every new table.
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['tenant_counters', 'product_categories', 'products', 'product_variants', 'product_images',
                             'warehouses', 'bins', 'stock_levels', 'stock_movements', 'suppliers', 'purchase_orders',
                             'purchase_order_lines', 'order_statuses', 'order_transitions', 'orders', 'order_items',
                             'order_allocations', 'order_history', 'payment_gateways', 'rmas', 'rma_items', 'payments',
                             'payment_events'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_current_tenant_id()) '
                       'WITH CHECK (tenant_id = app_current_tenant_id())', t);
    END LOOP;
END $$;

-- Idempotency keys: the tenant's own, plus platform context for the expiry job.
ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_access ON idempotency_keys
    USING (tenant_id = app_current_tenant_id() OR app_is_platform())
    WITH CHECK (tenant_id = app_current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_counters, product_categories, products, product_variants, product_images,
    warehouses, bins, stock_levels, suppliers, purchase_orders, purchase_order_lines, order_statuses, order_transitions,
    orders, order_items, order_allocations, payment_gateways, rmas, rma_items, payments, idempotency_keys TO ${appRole};
GRANT SELECT, INSERT ON stock_movements, order_history, payment_events TO ${appRole};
