// Sample data in the exact shapes the backend returns, so the UI works without a server.
// Switch to the real API with VITE_USE_MOCKS=false.
import type { Api } from './client'
import type { Order, OrderDetail, Role, Tenant, User, Webhook, WebhookDelivery } from './types'

const wait = <T>(v: T, ms = 180): Promise<T> => new Promise((r) => setTimeout(() => r(structuredClone(v)), ms))
const iso = (daysAgo: number, h = 9, m = 41) => { const d = new Date(); d.setDate(d.getDate() - daysAgo); d.setHours(h, m, 0, 0); return d.toISOString() }
const token = () => ({ access_token: 'demo.' + Math.random().toString(36).slice(2), refresh_token: 'demo-refresh', token_type: 'bearer', expires_in: 600 })

const orders: Order[] = [
  ['1042', 'paid', '84.00', 'Dara Sok', 0, 9, 41], ['1041', 'shipped', '19.50', 'Lina Chan', 0, 8, 12], ['1040', 'pending', '56.25', 'Mark Lee', 1, 18, 3],
  ['1039', 'delivered', '142.80', 'Sophea Kim', 2, 14, 27], ['1038', 'cancelled', '12.00', 'Tom Hart', 2, 10, 5], ['1037', 'delivered', '64.10', 'Vanna Ly', 3, 16, 50],
  ['1036', 'shipped', '27.00', 'Ana Ruiz', 3, 11, 2], ['1035', 'pending', '210.00', 'Chen Wei', 4, 13, 19],
].map(([n, status, total, name, d, h, m]) => ({
  id: `00000000-0000-4000-8000-00000000${n}`, number: String(n), status: String(status), stock_state: status === 'shipped' || status === 'delivered' ? 'committed' : status === 'cancelled' ? 'released' : 'reserved',
  currency: 'USD', total: String(total), amount_paid: status === 'pending' ? '0.00' : String(total), amount_refunded: status === 'cancelled' ? String(total) : '0.00',
  customer: { name: String(name), email: `${String(name).split(' ')[0].toLowerCase()}@example.com` }, placed_at: iso(Number(d), Number(h), Number(m)),
}))

const detail = (o: Order): OrderDetail => ({
  ...o,
  items: [
    { id: 'i1', sku: 'TS-BLK-M', name: 'T-shirt, black, M', qty: 2, unit_price: '22.00', line_total: '44.00', qty_returned: 0 },
    { id: 'i2', sku: 'MUG-12', name: 'Ceramic mug 12oz', qty: 1, unit_price: '16.00', line_total: '16.00', qty_returned: 0 },
    { id: 'i3', sku: 'TOTE-01', name: 'Canvas tote', qty: 1, unit_price: '24.00', line_total: '24.00', qty_returned: 0 },
  ],
  history: [
    ...(o.status !== 'pending' ? [{ from_status: 'pending', to_status: o.status === 'cancelled' ? 'cancelled' : 'paid', actor_id: null, note: 'Stripe webhook', created_at: o.placed_at }] : []),
    { from_status: null, to_status: 'pending', actor_id: null, note: 'Checkout', created_at: o.placed_at },
  ],
})

const transitions: [string, string][] = [['pending', 'paid'], ['pending', 'cancelled'], ['paid', 'shipped'], ['paid', 'cancelled'], ['shipped', 'delivered']]

const users: User[] = [
  ['Seav Pheng', 'owner', true, ['Tenant admin'], false, 0], ['Dara Sok', 'dara', false, ['Dispatcher'], false, 0], ['Lina Chan', 'lina', false, ['Catalog editor', 'Support'], false, 1],
  ['Mark Lee', 'mark', false, ['Support'], true, 4], ['Sophea Kim', 'sophea', false, ['Warehouse'], false, 5], ['Tom Hart', 'tom', false, ['Dispatcher'], false, 14],
].map(([name, user, admin, roles, locked, d], i) => ({
  id: `u${i}`, full_name: String(name), email: `${user}@acme.example.com`, is_tenant_admin: Boolean(admin), roles: roles as string[],
  is_active: name !== 'Tom Hart', locked: Boolean(locked), last_login_at: iso(Number(d), 9 + i, 5),
}))

const roles: Role[] = [{
  id: 'r1', name: 'Dispatcher', description: 'Handles orders and delivery tracking', user_count: 2,
  permissions: [
    ...(['read', 'create', 'update', 'delete'] as const).map((action) => ({ resource: 'orders', action, row_scope: 'own' as const, readable_fields: null, writable_fields: null })),
    ...(['read', 'create'] as const).map((action) => ({ resource: 'deliveries', action, row_scope: 'all' as const, readable_fields: null, writable_fields: null })),
    { resource: 'products', action: 'read', row_scope: 'all', readable_fields: null, writable_fields: null },
    { resource: 'inventory', action: 'read', row_scope: 'all', readable_fields: null, writable_fields: null },
    { resource: 'data.customers', action: 'read', row_scope: 'all', readable_fields: ['name', 'email', 'phone'], writable_fields: null },
    { resource: 'data.customers', action: 'update', row_scope: 'all', readable_fields: null, writable_fields: ['phone'] },
    { resource: 'webhooks', action: 'read', row_scope: 'all', readable_fields: null, writable_fields: null },
  ],
}]

const hooks: Webhook[] = [
  { id: 'w1', url: 'https://erp.acme.example.com/hooks/orders', description: 'ERP order sync', event_types: ['order.created', 'order.status_changed'], is_active: true, consecutive_failures: 0, disabled_reason: null, secret_version: 2, created_at: iso(40) },
  { id: 'w2', url: 'https://ops.acme.example.com/payments', description: 'Finance alerts', event_types: ['payment.failed', 'payment.refunded'], is_active: true, consecutive_failures: 3, disabled_reason: null, secret_version: 1, created_at: iso(30) },
  { id: 'w3', url: 'https://legacy.acme.example.com/hook', description: 'Old integration', event_types: ['data.created', 'data.updated'], is_active: false, consecutive_failures: 1, disabled_reason: 'Endpoint returned 410 Gone', secret_version: 1, created_at: iso(90) },
]
const deliveries: WebhookDelivery[] = [
  ['order.created', 'succeeded', 1, 200, 0], ['order.status_changed', 'pending', 3, 503, 0], ['order.created', 'succeeded', 2, 200, 0],
  ['order.status_changed', 'dead', 10, 500, 2], ['order.created', 'succeeded', 1, 204, 2],
].map(([type, status, attempts, code, d], i) => ({
  id: `d${i}`, event_id: ['7c1e9a40', '3a90b2c1', '91bd03ee', '0f44d6a2', 'c2d8ff10'][i] + '-…', event_type: String(type), status: status as WebhookDelivery['status'], attempts: Number(attempts),
  next_attempt_at: status === 'pending' ? new Date(Date.now() + 4 * 60_000).toISOString() : null, last_status_code: Number(code),
  last_error: status === 'succeeded' ? null : `HTTP ${code}`, delivered_at: status === 'succeeded' ? iso(Number(d)) : null, created_at: iso(Number(d), 9, 41 - i),
}))

const tenants: Tenant[] = [
  ['Acme Store', 'acme-store', 'active', 'shared', 1200, 6, '2026-01-14'], ['Globex Corp', 'globex', 'active', 'dedicated', 6000, 48, '2026-03-02'],
  ['Umbrella Labs', 'umbrella', 'active', 'dedicated', 3000, 21, '2026-05-19'], ['Initech', 'initech', 'suspended', 'shared', 1200, 9, '2026-06-07'],
  ['Hooli', 'hooli', 'offboarding', 'shared', 1200, 0, '2026-07-30'],
].map(([name, slug, status, iso_, rl, uc, created], i) => ({
  id: `t${i}`, name: String(name), slug: String(slug), status: status as Tenant['status'], isolation_mode: iso_ as Tenant['isolation_mode'],
  rate_limit_per_min: Number(rl), user_count: Number(uc), created_at: `${created}T08:00:00Z`,
}))

export const mock: Api = {
  login: async (_t, email) => { if (!email) throw new Error('Email required'); return wait(token(), 400) },
  platformLogin: async () => wait(token(), 400),
  logout: async () => wait(undefined),
  sales: async () => wait({
    items: Array.from({ length: 30 }, (_, i) => {
      const d = new Date(); d.setDate(d.getDate() - (29 - i))
      const revenue = 900 + 900 * (0.5 + 0.5 * Math.sin(i * 0.55)) + 500 * (i / 29)
      return { key: d.toISOString().slice(0, 10) + 'T00:00:00Z', currency: 'USD', orders: Math.round(revenue / 37.5), revenue: revenue.toFixed(2), refunded: (i % 7 === 0 ? 24 : 0).toFixed(2) }
    }),
  }),
  stockLevels: async () => wait({ items: [
    { sku: 'MUG-12', name: 'Ceramic mug 12oz', on_hand: 2, reserved: 2, available: 0 },
    { sku: 'TS-BLK-M', name: 'T-shirt, black, M', on_hand: 9, reserved: 5, available: 4 },
    { sku: 'CAP-NVY', name: 'Cap, navy', on_hand: 7, reserved: 0, available: 7 },
    { sku: 'TOTE-01', name: 'Canvas tote', on_hand: 12, reserved: 3, available: 9 },
  ] }),
  orders: async (status) => wait({ items: orders.filter((o) => !status || o.status === status) }),
  order: async (id) => { const o = orders.find((x) => x.id === id); if (!o) throw new Error('Not found'); return wait(detail(o)) },
  lifecycle: async () => wait({
    statuses: ['pending', 'paid', 'shipped', 'delivered', 'cancelled'].map((code, i) => ({
      code, label: code[0].toUpperCase() + code.slice(1), is_initial: i === 0, is_terminal: code === 'delivered' || code === 'cancelled',
      stock_effect: { pending: 'reserve', shipped: 'commit', cancelled: 'release' }[code] ?? 'none', is_sale: code !== 'cancelled',
    })),
    transitions,
  }),
  transition: async (id, to) => {
    const o = orders.find((x) => x.id === id)!
    if (!transitions.some(([a, b]) => a === o.status && b === to)) throw new Error(`Cannot move from ${o.status} to ${to}`)
    o.status = to; if (to === 'paid') o.amount_paid = o.total
    return wait(o, 300)
  },
  users: async () => wait({ items: users }),
  roles: async () => wait({ items: roles }),
  webhooks: async () => wait({ items: hooks }),
  deliveries: async () => wait({ items: deliveries }),
  redeliver: async (id) => { const d = deliveries.find((x) => x.id === id); if (d) { d.status = 'pending'; d.attempts = 0; d.next_attempt_at = new Date().toISOString() } return wait(undefined) },
  rotateSecret: async (id) => { const h = hooks.find((x) => x.id === id); if (h) h.secret_version += 1; return wait({ signing_secret: 'whsec_' + crypto.randomUUID().replace(/-/g, '') }) },
  tenants: async () => wait({ items: tenants }),
  deadLetters: async () => wait({ items: [
    { id: 'dl1', tenant_id: 't0', source: 'webhook', reference_id: 'd3', task_name: null, error: 'HTTP 500', created_at: iso(2) },
    { id: 'dl2', tenant_id: 't1', source: 'task', reference_id: 'task9', task_name: 'tenantcore.run_import', error: 'ValueError: bad row', created_at: iso(1) },
  ] }),
  tenantDb: async (id) => wait({
    tenant_id: id, dialect: 'postgresql', host: id === 't2' ? 'db-umbrella.internal' : 'db-globex.internal', port: 5432, database: 'tenant', ssl_mode: 'verify-full',
    status: 'active', secret_version: 3, synced_schema_version: id === 't2' ? 40 : 42, platform_schema_version: 42,
  }),
  syncTenantDb: async (id) => wait({ tenant_id: id, dialect: 'postgresql', host: 'db.internal', port: 5432, database: 'tenant', ssl_mode: 'verify-full', status: 'active', secret_version: 3, synced_schema_version: 42, platform_schema_version: 42 }, 900),
  offboard: async (id, slug) => {
    const t = tenants.find((x) => x.id === id)!
    if (slug !== t.slug) throw new Error("confirm_slug must equal the tenant's slug.")
    t.status = 'offboarding'
    return wait({ id: 'task1', kind: 'offboard', status: 'queued', progress: {}, result: null, error: null, created_at: new Date().toISOString() }, 500)
  },
}
