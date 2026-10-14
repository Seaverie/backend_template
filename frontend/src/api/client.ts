import { clearSession, saveSession, session } from './session'
import { mock } from './mock'
import type {
  Lifecycle, Order, OrderDetail, Page, Role, SalesRow, StockLevel, Task, Tenant, TenantDatabase, TokenPair, User, Webhook, WebhookDelivery,
} from './types'

export const BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'
export const USE_MOCKS = import.meta.env.VITE_USE_MOCKS !== 'false'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId?: string) { super(message) }
}

async function raw(path: string, init: RequestInit = {}, retry = true): Promise<Response> {
  const headers = new Headers(init.headers)
  if (session.access) headers.set('Authorization', `Bearer ${session.access}`)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const res = await fetch(BASE + path, { ...init, headers })
  // Access tokens are short-lived: refresh once on 401, then give up and sign out.
  if (res.status === 401 && retry && session.refresh && session.audience === 'tenant') {
    const r = await fetch(`${BASE}/auth/refresh`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refresh_token: session.refresh }),
    })
    if (r.ok) {
      const pair = (await r.json()) as TokenPair
      saveSession({ access: pair.access_token, refresh: pair.refresh_token })
      return raw(path, init, false)
    }
    clearSession()
  }
  return res
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await raw(path, init)
  if (res.status === 204) return undefined as T
  const body = await res.json().catch(() => ({}))
  if (!res.ok) {
    const e = body?.error ?? {}
    throw new ApiError(res.status, e.code ?? `http_${res.status}`, e.message ?? 'Request failed.', e.request_id)
  }
  return body as T
}
const get = <T>(p: string) => request<T>(p)
const send = <T>(method: string, p: string, body?: unknown) => request<T>(p, { method, body: body === undefined ? undefined : JSON.stringify(body) })

// Every call has the same signature in mock and live mode, so views never know which one runs.
const live = {
  login: (tenant: string, email: string, password: string) => send<TokenPair>('POST', '/auth/login', { tenant, email, password }),
  platformLogin: (email: string, password: string, otp?: string) => send<TokenPair>('POST', '/platform/auth/login', { email, password, ...(otp ? { otp } : {}) }),
  logout: () => send<void>('POST', session.audience === 'platform' ? '/platform/auth/logout' : '/auth/logout'),
  sales: (from: string, to: string, groupBy = 'day') => get<{ items: SalesRow[] }>(`/analytics/sales?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&group_by=${groupBy}`),
  stockLevels: () => get<Page<StockLevel>>('/stock/levels'),
  orders: (status?: string, limit = 50) => get<Page<Order>>(`/orders?limit=${limit}${status ? `&status=${status}` : ''}`),
  order: (id: string) => get<OrderDetail>(`/orders/${id}`),
  lifecycle: () => get<Lifecycle>('/order-lifecycle'),
  transition: (id: string, to: string) => request<Order>(`/orders/${id}/transitions`, {
    method: 'POST', body: JSON.stringify({ to }), headers: { 'Idempotency-Key': crypto.randomUUID() },
  }),
  users: () => get<Page<User>>('/users'),
  roles: () => get<Page<Role>>('/roles'),
  webhooks: () => get<Page<Webhook>>('/webhooks'),
  deliveries: (id: string) => get<Page<WebhookDelivery>>(`/webhooks/${id}/deliveries`),
  redeliver: (id: string) => send<void>('POST', `/webhook-deliveries/${id}/redeliver`),
  rotateSecret: (id: string) => send<{ signing_secret: string }>('POST', `/webhooks/${id}/rotate-secret`),
  tenants: () => get<Page<Tenant>>('/platform/tenants'),
  tenantDb: (id: string) => get<TenantDatabase>(`/platform/tenants/${id}/database`),
  syncTenantDb: (id: string) => send<TenantDatabase>('POST', `/platform/tenants/${id}/database/sync`),
  offboard: (id: string, confirmSlug: string, dropDedicatedData: boolean) =>
    send<Task>('POST', `/platform/tenants/${id}/offboard`, { confirm_slug: confirmSlug, drop_dedicated_data: dropDedicatedData }),
}

export type Api = typeof live
export const api: Api = USE_MOCKS ? mock : live
