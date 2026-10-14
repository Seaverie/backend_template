// Shapes returned by the TenantCore FastAPI backend (see app/api/routers/*).
export interface TokenPair { access_token: string; refresh_token: string | null; token_type: string; expires_in: number }
export interface Page<T> { items: T[] }

export interface Order {
  id: string; number: string; status: string; stock_state: string; currency: string
  total: string; amount_paid: string; amount_refunded: string
  customer: { name?: string; email?: string }; placed_at: string
}
export interface OrderItem { id: string; variant_id?: string; sku: string; name: string; qty: number; unit_price: string; line_total: string; qty_returned: number }
export interface OrderHistory { from_status: string | null; to_status: string; actor_id: string | null; note: string | null; created_at: string }
export interface OrderDetail extends Order { items: OrderItem[]; history: OrderHistory[] }
export interface LifecycleStatus { code: string; label: string; is_initial: boolean; is_terminal: boolean; stock_effect: string; is_sale: boolean }
export interface Lifecycle { statuses: LifecycleStatus[]; transitions: [string, string][] }
export interface Payment { id: string; order_id: string; gateway_id: string; provider_ref: string; amount: string; currency: string; status: string; client_secret?: string | null }
export interface Rma {
  id: string; number: string; order_id: string; status: string; reason: string; refund_amount: string | null; created_at: string
  items: { order_item_id: string; qty: number; restock: boolean }[]
  events: { from_status: string | null; to_status: string; note: string | null; created_at: string }[]
}
export type RmaSummary = Omit<Rma, 'items' | 'events'>
export interface SalesRow { key: string; currency: string; orders: number; revenue: string; refunded: string }

export interface Variant { id: string; product_id: string; sku: string; name: string; price: string; currency: string; attributes: Record<string, unknown>; is_active: boolean }
export interface Product { id: string; name: string; description: string | null; status: 'draft' | 'active' | 'archived'; is_public: boolean; created_at: string; variants: Variant[] }
export interface Bin { id: string; code: string; is_active: boolean }
export interface Warehouse { id: string; code: string; name: string; is_active: boolean; bins: Bin[] }
export interface StockLevel { variant_id: string; bin_id: string; on_hand: number; reserved: number; available: number }

export interface User { id: string; email: string; full_name: string | null; is_active: boolean; is_tenant_admin: boolean; role_ids?: string[]; last_login_at?: string | null; locked_until?: string | null }
export type Action = 'read' | 'create' | 'update' | 'delete' | 'manage'
export interface Permission { resource: string; action: Action; row_scope: 'all' | 'own'; readable_fields: string[] | null; writable_fields: string[] | null }
export interface Role { id: string; name: string; description?: string | null; is_system?: boolean; permissions: Permission[] }
export interface Me { kind: string; tenant_id: string; user_id: string; is_tenant_admin: boolean; scopes: string[] | null; permissions: string[] }

export interface ApiKey { id: string; key_id: string; name: string; scopes: string[]; created_at: string | null; expires_at: string | null; revoked_at: string | null; rotated_from_id: string | null; api_key?: string }
export interface AuditEntry { id: string; actor_type: string; actor_id: string | null; action: string; resource: string; resource_id: string | null; request_id: string | null; detail: Record<string, unknown> | null; created_at: string }

export interface Webhook { id: string; url: string; description: string | null; event_types: string[]; is_active: boolean; consecutive_failures: number; disabled_reason: string | null; secret_version: number; created_at: string; signing_secret?: string }
export interface WebhookDelivery { id: string; event_id: string; event_type: string; status: 'pending' | 'succeeded' | 'dead'; attempts: number; next_attempt_at: string | null; last_status_code: number | null; last_error: string | null; delivered_at: string | null; created_at: string }
export interface Gateway { id: string; name: string; provider: string; public_config: Record<string, unknown>; is_default: boolean; is_active?: boolean; secret_version?: number; created_at?: string }

export type FieldType = 'string' | 'text' | 'integer' | 'bigint' | 'numeric' | 'boolean' | 'date' | 'timestamp' | 'uuid' | 'json' | 'enum' | 'reference'
export interface FieldDef { name: string; type: FieldType; nullable: boolean; is_unique?: boolean; is_indexed?: boolean; is_searchable?: boolean; is_sensitive?: boolean; max_length?: number | null; enum_values?: string[] | null; ref_table?: string | null; writable?: boolean }
export interface FieldSpecIn { name: string; type: FieldType; nullable: boolean; unique?: boolean; indexed?: boolean; searchable?: boolean; sensitive?: boolean; max_length?: number | null; enum_values?: string[] | null; ref_table?: string | null }
export interface DataTable { name: string; crud_mode: string; require_idempotency: boolean; actions: string[]; searchable: string[]; fields: FieldDef[] }
export interface DataPage { items: Record<string, unknown>[]; limit: number; offset: number; next_offset: number | null }
export interface SystemTable { id: string; name: string; crud_mode: string; version: number; fields: FieldDef[] }

export interface Tenant { id: string; slug: string; name: string; status: 'active' | 'suspended' | 'offboarding'; isolation_mode: 'shared' | 'dedicated'; rate_limit_per_min: number | null; created_at?: string; user_count?: number }
export interface TenantDatabase { tenant_id: string; dialect: string; host: string; port: number; database: string; username?: string; ssl_mode: string; status: string; secret_version: number; synced_schema_version: number | null; platform_schema_version?: number }
export interface Task { id: string; kind: string; status: 'queued' | 'running' | 'succeeded' | 'failed'; progress: Record<string, unknown>; result: Record<string, unknown> | null; error: string | null; created_at: string; finished_at?: string | null; attempts?: number; input?: Record<string, unknown> }
export interface DeadLetter { id: string; tenant_id: string | null; source: string; reference_id: string; task_name: string | null; error: string; created_at: string; requeued_at?: string | null }
export interface SlowQueries { pg_stat_statements: { query: string; calls: number; mean_ms: number; total_ms: number; rows: number }[] | null; seq_scan_heavy_tables: Record<string, unknown>[]; slow_query_log_threshold_ms: number }
