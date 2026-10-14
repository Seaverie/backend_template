// Shapes returned by the TenantCore FastAPI backend (see app/api/routers/*).
export interface TokenPair { access_token: string; refresh_token: string | null; token_type: string; expires_in: number }
export interface Page<T> { items: T[] }

export interface Order {
  id: string; number: string; status: string; stock_state: string; currency: string
  total: string; amount_paid: string; amount_refunded: string
  customer: { name?: string; email?: string }; placed_at: string
}
export interface OrderItem { id: string; sku: string; name: string; qty: number; unit_price: string; line_total: string; qty_returned: number }
export interface OrderHistory { from_status: string | null; to_status: string; actor_id: string | null; note: string | null; created_at: string }
export interface OrderDetail extends Order { items: OrderItem[]; history: OrderHistory[] }
export interface Lifecycle {
  statuses: { code: string; label: string; is_initial: boolean; is_terminal: boolean; stock_effect: string; is_sale: boolean }[]
  transitions: [string, string][]
}
export interface SalesRow { key: string; currency: string; orders: number; revenue: string; refunded: string }
export interface StockLevel { sku: string; name: string; on_hand: number; reserved: number; available: number }

export interface User { id: string; email: string; full_name: string | null; is_active: boolean; is_tenant_admin: boolean; roles?: string[]; locked?: boolean; last_login_at?: string | null }
export type Action = 'read' | 'create' | 'update' | 'delete' | 'manage'
export interface Permission { resource: string; action: Action; row_scope: 'all' | 'own'; readable_fields: string[] | null; writable_fields: string[] | null }
export interface Role { id: string; name: string; description?: string | null; permissions: Permission[]; user_count?: number }

export interface Webhook { id: string; url: string; description: string | null; event_types: string[]; is_active: boolean; consecutive_failures: number; disabled_reason: string | null; secret_version: number; created_at: string }
export interface WebhookDelivery { id: string; event_id: string; event_type: string; status: 'pending' | 'succeeded' | 'dead'; attempts: number; next_attempt_at: string | null; last_status_code: number | null; last_error: string | null; delivered_at: string | null; created_at: string }

export interface Tenant { id: string; slug: string; name: string; status: 'active' | 'suspended' | 'offboarding'; isolation_mode: 'shared' | 'dedicated'; rate_limit_per_min: number | null; created_at?: string; user_count?: number }
export interface TenantDatabase { tenant_id: string; dialect: string; host: string; port: number; database: string; ssl_mode: string; status: string; secret_version: number; synced_schema_version: number | null; platform_schema_version?: number }
export interface DeadLetter { id: string; tenant_id: string | null; source: string; reference_id: string; task_name: string | null; error: string; created_at: string }
export interface Task { id: string; kind: string; status: 'queued' | 'running' | 'succeeded' | 'failed'; progress: Record<string, unknown>; result: Record<string, unknown> | null; error: string | null; created_at: string }
