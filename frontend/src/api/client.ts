import { clearSession, saveSession, session } from './session'
import { mock } from './mock'
import type {
  ApiKey, AuditEntry, DataPage, DataTable, DeadLetter, FieldSpecIn, Gateway, Lifecycle, Me, Order, OrderDetail, Page, Payment, Product,
  Permission, Rma, RmaSummary, Role, SalesRow, SlowQueries, StockLevel, SystemTable, Task, Tenant, TenantDatabase, TokenPair, User, Variant, Warehouse, Webhook, WebhookDelivery,
} from './types'

export const BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'
export const USE_MOCKS = import.meta.env.VITE_USE_MOCKS !== 'false'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId?: string, public details?: unknown) { super(message) }
}

async function raw(path: string, init: RequestInit = {}, retry = true): Promise<Response> {
  const headers = new Headers(init.headers)
  if (session.access) headers.set('Authorization', `Bearer ${session.access}`)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  // Every write carries a fresh Idempotency-Key: endpoints that require one get it, and a retried
  // network request can never create a second order or payment.
  if (init.method && init.method !== 'GET' && !headers.has('Idempotency-Key')) headers.set('Idempotency-Key', crypto.randomUUID())
  const res = await fetch(BASE + path, { ...init, headers })
  if (res.status === 401 && retry && session.refresh && session.audience === 'tenant') {
    const r = await fetch(`${BASE}/auth/refresh`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refresh_token: session.refresh }) })
    if (r.ok) {
      const pair = (await r.json()) as TokenPair
      saveSession({ access: pair.access_token, refresh: pair.refresh_token })
      return raw(path, init, false)
    }
  }
  // A 401 from a credential check (sign-in, MFA code) is a wrong answer, not an expired session.
  if (res.status === 401 && session.access && !/\/login$|\/mfa\/confirm$/.test(path)) {
    clearSession()
    window.dispatchEvent(new CustomEvent('tc:signed-out'))
  }
  return res
}

function messageFrom(e: Record<string, unknown>, status: number): string {
  const details = e.details as { loc?: (string | number)[]; msg?: string }[] | undefined
  if (Array.isArray(details) && details.length && details[0]?.msg) {
    return details.slice(0, 3).map((d) => `${(d.loc ?? []).filter((x) => x !== 'body').join('.') || 'input'}: ${d.msg}`).join('; ')
  }
  if (typeof e.message === 'string') return e.message
  return status === 429 ? 'Too many requests. Wait a moment and try again.' : 'Request failed.'
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await raw(path, init)
  if (res.status === 204) return undefined as T
  const body = await res.json().catch(() => ({}))
  if (!res.ok) {
    const e = (body?.error ?? {}) as Record<string, unknown>
    throw new ApiError(res.status, String(e.code ?? `http_${res.status}`), messageFrom(e, res.status), e.request_id as string | undefined, e.details)
  }
  return body as T
}
const get = <T>(p: string) => request<T>(p)
const send = <T>(method: string, p: string, body?: unknown) => request<T>(p, { method, body: body === undefined ? undefined : JSON.stringify(body) })
const qs = (o: Record<string, string | number | boolean | undefined | null>) => {
  const s = new URLSearchParams(); for (const [k, v] of Object.entries(o)) if (v !== undefined && v !== null && v !== '') s.set(k, String(v))
  const t = s.toString(); return t ? `?${t}` : ''
}

const live = {
  // auth
  login: (tenant: string, email: string, password: string) => send<TokenPair>('POST', '/auth/login', { tenant, email, password }),
  platformLogin: (email: string, password: string, otp?: string) => send<TokenPair>('POST', '/platform/auth/login', { email, password, ...(otp ? { otp } : {}) }),
  logout: () => send<void>('POST', session.audience === 'platform' ? '/platform/auth/logout' : '/auth/logout'),
  me: () => get<Me>('/auth/me'),
  mfaSetup: () => send<{ secret: string; otpauth_uri: string }>('POST', '/platform/auth/mfa/setup'),
  mfaConfirm: (code: string) => send<{ access_token: string; token_type: string; expires_in: number }>('POST', '/platform/auth/mfa/confirm', { code }),
  // analytics
  sales: (from: string, to: string, groupBy = 'day') => get<{ items: SalesRow[] }>(`/analytics/sales${qs({ from, to, group_by: groupBy })}`),
  // catalog & stock
  products: (q?: string) => get<Page<Product>>(`/products${qs({ q, limit: 200 })}`),
  createProduct: (body: { name: string; description?: string | null; status: string; is_public: boolean; variants: Omit<Variant, 'id' | 'product_id' | 'is_active'>[] }) => send<Product>('POST', '/products', body),
  updateProduct: (id: string, body: Partial<Pick<Product, 'name' | 'description' | 'status' | 'is_public'>>) => send<Product>('PATCH', `/products/${id}`, body),
  addVariant: (productId: string, body: { sku: string; name: string; price: string; currency: string }) => send<Variant>('POST', `/products/${productId}/variants`, { ...body, attributes: {} }),
  warehouses: () => get<Page<Warehouse>>('/warehouses'),
  createWarehouse: (code: string, name: string) => send<Warehouse>('POST', '/warehouses', { code, name }),
  createBin: (warehouseId: string, code: string) => send<unknown>('POST', `/warehouses/${warehouseId}/bins`, { code }),
  stockLevels: () => get<Page<StockLevel>>('/stock/levels?limit=500'),
  adjustStock: (variantId: string, binId: string, delta: number, note?: string) =>
    send<unknown>('POST', '/stock/adjustments', { batch_key: crypto.randomUUID(), lines: [{ variant_id: variantId, bin_id: binId, delta }], note: note || null }),
  // orders
  orders: (status?: string, limit = 50) => get<Page<Order>>(`/orders${qs({ status, limit })}`),
  order: (id: string) => get<OrderDetail>(`/orders/${id}`),
  createOrder: (items: { variant_id: string; qty: number }[], customer: Record<string, string>) => send<Order>('POST', '/orders', { items, customer }),
  lifecycle: () => get<Lifecycle>('/order-lifecycle'),
  transition: (id: string, to: string, note?: string) => send<Order>('POST', `/orders/${id}/transitions`, { to, note: note || null }),
  startPayment: (orderId: string, gatewayId?: string) => send<Payment>('POST', `/orders/${orderId}/payments`, { gateway_id: gatewayId || null }),
  createReturn: (orderId: string, items: { order_item_id: string; qty: number; restock: boolean }[], reason: string) => send<Rma>('POST', `/orders/${orderId}/returns`, { items, reason }),
  returns: (orderId?: string) => get<Page<RmaSummary>>(`/returns${qs({ order_id: orderId, limit: 100 })}`),
  rma: (id: string) => get<Rma>(`/returns/${id}`),
  rmaAction: (id: string, action: string, extra: { note?: string; restock_bin_id?: string; refund_amount?: string } = {}) => send<Rma>('POST', `/returns/${id}/actions`, { action, ...extra }),
  // dynamic data
  tables: () => get<Page<DataTable>>('/data'),
  rows: (table: string, opts: { q?: string; offset?: number; limit?: number; sort?: string }) => get<DataPage>(`/data/${table}${qs({ ...opts })}`),
  createRow: (table: string, body: Record<string, unknown>) => send<Record<string, unknown>>('POST', `/data/${table}`, body),
  updateRow: (table: string, id: string, body: Record<string, unknown>) => send<Record<string, unknown>>('PATCH', `/data/${table}/${id}`, body),
  deleteRow: (table: string, id: string) => send<void>('DELETE', `/data/${table}/${id}`),
  // imports & tasks
  importFile: (table: string, file: File, mode: string) => {
    const fd = new FormData(); fd.append('file', file)
    return request<Task>(`/import/excel/${table}${qs({ mode })}`, { method: 'POST', body: fd })
  },
  task: (id: string) => get<Task>(`/tasks/${id}`),
  // users & roles
  users: () => get<Page<User>>('/users'),
  createUser: (body: { email: string; password: string; full_name?: string | null; is_tenant_admin: boolean }) => send<User>('POST', '/users', body),
  updateUser: (id: string, body: { is_active?: boolean; is_tenant_admin?: boolean }) => send<User>('PATCH', `/users/${id}`, body),
  setUserRoles: (id: string, roleIds: string[]) => send<void>('PUT', `/users/${id}/roles`, { role_ids: roleIds }),
  roles: () => get<Page<Role>>('/roles'),
  createRole: (body: { name: string; description?: string | null; permissions: (Pick<Permission, 'resource' | 'action'> & Partial<Permission>)[] }) => send<Role>('POST', '/roles', body),
  // api keys
  apiKeys: () => get<Page<ApiKey>>('/api-keys'),
  createApiKey: (name: string, scopes: string[], expiresInDays?: number) => send<ApiKey>('POST', '/api-keys', { name, scopes, expires_in_days: expiresInDays || null }),
  revokeApiKey: (id: string) => send<unknown>('POST', `/api-keys/${id}/revoke`),
  rotateApiKey: (id: string, graceSeconds = 3600) => send<ApiKey>('POST', `/api-keys/${id}/rotate`, { grace_seconds: graceSeconds }),
  // audit & settings
  auditLogs: (offset = 0, limit = 50) => get<Page<AuditEntry>>(`/audit-logs${qs({ offset, limit })}`),
  setRateLimit: (perMin: number) => send<{ rate_limit_per_min: number; platform_max_per_min: number }>('PUT', '/tenant/rate-limit', { rate_limit_per_min: perMin }),
  gateways: () => get<Page<Gateway>>('/payment-gateways'),
  createGateway: (body: { name: string; provider: string; secrets: Record<string, string>; is_default: boolean }) => send<Gateway>('POST', '/payment-gateways', { ...body, public_config: {} }),
  // webhooks
  webhooks: () => get<Page<Webhook>>('/webhooks'),
  createWebhook: (url: string, eventTypes: string[], description?: string) => send<Webhook>('POST', '/webhooks', { url, event_types: eventTypes, description: description || null }),
  updateWebhook: (id: string, body: { url?: string; event_types?: string[]; is_active?: boolean }) => send<Webhook>('PATCH', `/webhooks/${id}`, body),
  deleteWebhook: (id: string) => send<void>('DELETE', `/webhooks/${id}`),
  deliveries: (id: string) => get<Page<WebhookDelivery>>(`/webhooks/${id}/deliveries`),
  redeliver: (id: string) => send<void>('POST', `/webhook-deliveries/${id}/redeliver`),
  rotateGatewaySecrets: (id: string, secrets: Record<string, string>) => send<void>('PUT', `/payment-gateways/${id}/secrets`, { secrets }),
  rotateSecret: (id: string) => send<{ signing_secret: string }>('POST', `/webhooks/${id}/rotate-secret`),
  // platform
  // The list endpoint pages at 200; fetch every page so search and counts cover all tenants.
  tenants: async (): Promise<Page<Tenant>> => {
    const items: Tenant[] = []
    for (let offset = 0; offset < 100_000; offset += 200) {
      const page = await get<Page<Tenant>>(`/platform/tenants?limit=200&offset=${offset}`)
      items.push(...page.items)
      if (page.items.length < 200) break
    }
    return { items }
  },
  createTenant: (body: { slug: string; name: string; owner_email: string; owner_password: string; rate_limit_per_min?: number | null }) => send<{ tenant: Tenant }>('POST', '/platform/tenants', body),
  updateTenant: (id: string, body: { status?: string; rate_limit_per_min?: number | null }) => send<Tenant>('PATCH', `/platform/tenants/${id}`, body),
  tenantDb: (id: string) => get<TenantDatabase>(`/platform/tenants/${id}/database`),
  configureTenantDb: (id: string, body: { dialect: string; host: string; port: number; database: string; username: string; password: string; ssl_mode: string }) => send<TenantDatabase>('PUT', `/platform/tenants/${id}/database`, body),
  syncTenantDb: (id: string) => send<Record<string, unknown>>('POST', `/platform/tenants/${id}/database/sync`),
  disableTenantDb: (id: string) => send<void>('POST', `/platform/tenants/${id}/database/disable`),
  offboard: (id: string, confirmSlug: string, dropDedicatedData: boolean) => send<Task>('POST', `/platform/tenants/${id}/offboard`, { confirm_slug: confirmSlug, drop_dedicated_data: dropDedicatedData }),
  platformTask: (id: string) => get<Task>(`/platform/tasks/${id}`),
  deadLetters: (includeRequeued = false) => get<Page<DeadLetter>>(`/platform/dead-letters${qs({ limit: 500, include_requeued: includeRequeued })}`),
  requeueDeadLetter: (id: string) => send<unknown>('POST', `/platform/dead-letters/${id}/requeue`),
  slowQueries: () => get<SlowQueries>('/platform/diagnostics/slow-queries?limit=20'),
  systemTables: () => get<Page<SystemTable>>('/system/tables'),
  createSystemTable: (name: string, fields: FieldSpecIn[]) => send<unknown>('POST', '/system/tables', { name, fields }),
  addSystemFields: (id: string, fields: FieldSpecIn[]) => send<unknown>('PATCH', `/system/tables/${id}`, { add_fields: fields }),
  dropSystemFields: (id: string, names: string[]) => send<unknown>('PATCH', `/system/tables/${id}`, { drop_fields: names }),
  deleteSystemTable: (id: string, confirm: string) => send<unknown>('DELETE', `/system/tables/${id}${qs({ confirm })}`),
  exportSnapshot: () => send<Record<string, unknown>>('POST', '/system/snapshot/export'),
}

export type Api = typeof live
export const api: Api = USE_MOCKS ? mock : live
