// In-memory stand-in for the API, in the exact response shapes the backend returns, so the UI can be
// tried without a server. Changes live until the page reloads. Use the real API with VITE_USE_MOCKS=false.
import type { Api } from './client'
import type {
  ApiKey, AuditEntry, DataTable, DeadLetter, FieldDef, FieldSpecIn, Gateway, LifecycleStatus, Order, OrderDetail, OrderItem,
  Payment, Product, Rma, Role, StockLevel, SystemTable, Task, Tenant, TenantDatabase, User, Variant, Warehouse, Webhook, WebhookDelivery,
} from './types'

const wait = <T>(v: T, ms = 160): Promise<T> => new Promise((r) => setTimeout(() => r(v === undefined ? v : structuredClone(v)), ms))
const fail = (msg: string, ms = 160): Promise<never> => new Promise((_, rej) => setTimeout(() => rej(new Error(msg)), ms))
const now = () => new Date().toISOString()
const iso = (daysAgo: number, h = 9, m = 41) => { const d = new Date(); d.setDate(d.getDate() - daysAgo); d.setHours(h, m, 0, 0); return d.toISOString() }
const uid = () => crypto.randomUUID()
const token = () => ({ access_token: 'demo.' + Math.random().toString(36).slice(2), refresh_token: 'demo-refresh', token_type: 'bearer', expires_in: 600 })
const money = (n: number) => n.toFixed(2)
const find = <T extends { id: string }>(xs: T[], id: string, what = 'Resource'): T => {
  const x = xs.find((y) => y.id === id); if (!x) throw new Error(`${what} not found.`); return x
}

// -- audit -------------------------------------------------------------------------------------------
const audit: AuditEntry[] = []
const log = (action: string, resource: string, resourceId: string | null = null, detail: Record<string, unknown> | null = null) =>
  audit.unshift({ id: uid(), actor_type: 'user', actor_id: 'u0', action, resource, resource_id: resourceId, request_id: uid().slice(0, 8), detail, created_at: now() })

// -- catalog & stock ---------------------------------------------------------------------------------
const mkVariant = (productId: string, sku: string, name: string, price: string): Variant =>
  ({ id: uid(), product_id: productId, sku, name, price, currency: 'USD', attributes: {}, is_active: true })
const products: Product[] = ([
  ['T-shirt', 'Organic cotton tee', [['TS-BLK-M', 'T-shirt, black, M', '22.00'], ['TS-BLK-L', 'T-shirt, black, L', '22.00']]],
  ['Ceramic mug', '12oz stoneware mug', [['MUG-12', 'Ceramic mug 12oz', '16.00']]],
  ['Canvas tote', null, [['TOTE-01', 'Canvas tote', '24.00']]],
  ['Cap', 'Six-panel cap', [['CAP-NVY', 'Cap, navy', '18.50']]],
] as [string, string | null, string[][]][]).map(([name, description, vs], i) => {
  const id = uid()
  return { id, name, description, status: 'active' as const, is_public: i !== 3, created_at: iso(60 - i * 5), variants: vs.map(([sku, n, p]) => mkVariant(id, sku, n, p)) }
})
const warehouses: Warehouse[] = [
  { id: uid(), code: 'MAIN', name: 'Main warehouse', is_active: true, bins: [{ id: uid(), code: 'A-01', is_active: true }, { id: uid(), code: 'A-02', is_active: true }] },
  { id: uid(), code: 'STORE', name: 'Shop floor', is_active: true, bins: [{ id: uid(), code: 'FLOOR', is_active: true }] },
]
const stock = new Map<string, { on_hand: number; reserved: number }>()
const allVariants = () => products.flatMap((p) => p.variants)
const binA = warehouses[0].bins[0].id
;[12, 9, 4, 14, 7].forEach((q, i) => stock.set(`${allVariants()[i].id}|${binA}`, { on_hand: q, reserved: 0 }))
stock.set(`${allVariants()[2].id}|${warehouses[1].bins[0].id}`, { on_hand: 2, reserved: 0 })

function reserve(variantId: string, qty: number): void {
  const bins = [...stock.entries()].filter(([k]) => k.startsWith(variantId + '|'))
  const available = bins.reduce((a, [, v]) => a + v.on_hand - v.reserved, 0)
  if (available < qty) throw new Error(`Insufficient stock for ${allVariants().find((v) => v.id === variantId)?.sku}: ${available} available.`)
  let left = qty
  for (const [, v] of bins) { const take = Math.min(left, v.on_hand - v.reserved); v.reserved += take; left -= take; if (!left) break }
}
function settle(variantId: string, qty: number, effect: 'commit' | 'release'): void {
  let left = qty
  for (const [k, v] of stock) {
    if (!k.startsWith(variantId + '|') || !left) continue
    const take = Math.min(left, v.reserved); v.reserved -= take; if (effect === 'commit') v.on_hand -= take; left -= take
  }
}

// -- orders ------------------------------------------------------------------------------------------
const statuses: LifecycleStatus[] = ['pending', 'paid', 'shipped', 'delivered', 'cancelled'].map((code, i) => ({
  code, label: code[0].toUpperCase() + code.slice(1), is_initial: i === 0, is_terminal: code === 'delivered' || code === 'cancelled',
  stock_effect: ({ pending: 'reserve', shipped: 'commit', cancelled: 'release' } as Record<string, string>)[code] ?? 'none', is_sale: code !== 'cancelled',
}))
const transitions: [string, string][] = [['pending', 'paid'], ['pending', 'cancelled'], ['paid', 'shipped'], ['paid', 'cancelled'], ['shipped', 'delivered']]
const orders: OrderDetail[] = []
let orderSeq = 1034
function move(o: OrderDetail, to: string, note: string | null, at = now()): void {
  const effect = statuses.find((s) => s.code === to)?.stock_effect
  if (effect === 'commit' || effect === 'release') { o.items.forEach((i) => settle(i.variant_id!, i.qty, effect)); o.stock_state = effect === 'commit' ? 'committed' : 'released' }
  if (to === 'paid') o.amount_paid = o.total
  o.history.unshift({ from_status: o.status, to_status: to, actor_id: null, note, created_at: at })
  o.status = to
}
function addOrder(lines: { variant_id: string; qty: number }[], customer: Record<string, string>, placedAt = now()): OrderDetail {
  const items: OrderItem[] = lines.map((l) => {
    const v = find(allVariants(), l.variant_id, 'Variant')
    return { id: uid(), variant_id: v.id, sku: v.sku, name: v.name, qty: l.qty, unit_price: v.price, line_total: money(Number(v.price) * l.qty), qty_returned: 0 }
  })
  const total = money(items.reduce((a, i) => a + Number(i.line_total), 0))
  const o: OrderDetail = {
    id: uid(), number: String(++orderSeq), status: 'pending', stock_state: 'reserved', currency: 'USD', total, amount_paid: '0.00', amount_refunded: '0.00',
    customer, placed_at: placedAt, items, history: [{ from_status: null, to_status: 'pending', actor_id: null, note: 'Checkout', created_at: placedAt }],
  }
  orders.unshift(o)
  return o
}
const people = ['Chen Wei', 'Ana Ruiz', 'Vanna Ly', 'Tom Hart', 'Sophea Kim', 'Mark Lee', 'Lina Chan', 'Dara Sok']
const seedPath: string[][] = [['pending'], ['paid', 'shipped'], ['paid', 'shipped', 'delivered'], ['cancelled'], ['paid', 'shipped', 'delivered'], [], ['paid', 'shipped'], ['paid']]
people.forEach((name, i) => {
  const v = allVariants()
  const lines = [{ variant_id: v[i % v.length].id, qty: 1 + (i % 2) }, ...(i % 3 === 0 ? [{ variant_id: v[(i + 2) % v.length].id, qty: 1 }] : [])]
  lines.forEach((l) => { try { reserve(l.variant_id, l.qty) } catch { /* seed stock may run out */ } })
  const o = addOrder(lines, { name, email: `${name.split(' ')[0].toLowerCase()}@example.com` }, iso(Math.floor((7 - i) / 2), 9 + i, 5 * i))
  for (const to of seedPath[i].filter((s) => s !== 'pending')) move(o, to, to === 'paid' ? 'Sandbox webhook' : null, o.placed_at)
})
const summary = (o: OrderDetail): Order => { const { items: _i, history: _h, ...rest } = o; return rest }
const rmas: Rma[] = []
const payments: Payment[] = []

// -- dynamic tables ----------------------------------------------------------------------------------
const f = (name: string, type: FieldDef['type'], extra: Partial<FieldDef> = {}): FieldDef =>
  ({ name, type, nullable: true, is_unique: false, is_indexed: false, is_searchable: false, is_sensitive: false, max_length: type === 'string' ? 255 : null, enum_values: null, ref_table: null, ...extra })
const systemTables: SystemTable[] = [
  { id: uid(), name: 'customers', crud_mode: 'full', version: 3, fields: [f('name', 'string', { nullable: false, is_searchable: true }), f('email', 'string', { is_unique: true, is_searchable: true }), f('phone', 'string', { is_sensitive: true }), f('tier', 'enum', { enum_values: ['standard', 'gold', 'vip'] }), f('vip_since', 'date')] },
  { id: uid(), name: 'suppliers', crud_mode: 'full', version: 1, fields: [f('name', 'string', { nullable: false, is_searchable: true }), f('country', 'string'), f('lead_time_days', 'integer'), f('active', 'boolean')] },
]
const rows = new Map<string, Record<string, unknown>[]>([
  ['customers', people.slice(0, 6).map((n, i) => ({ id: uid(), name: n, email: `${n.split(' ')[0].toLowerCase()}@example.com`, phone: `+855 12 ${100200 + i * 37}`, tier: ['standard', 'gold', 'vip'][i % 3], vip_since: i % 3 === 2 ? '2025-11-02' : null, created_at: iso(30 - i), updated_at: iso(10 - i) }))],
  ['suppliers', [{ id: uid(), name: 'Mekong Textiles', country: 'KH', lead_time_days: 14, active: true, created_at: iso(80), updated_at: iso(20) }, { id: uid(), name: 'Clayworks Co.', country: 'VN', lead_time_days: 21, active: true, created_at: iso(70), updated_at: iso(40) }]],
])
const asDataTable = (t: SystemTable): DataTable => ({
  name: t.name, crud_mode: t.crud_mode, require_idempotency: false, actions: t.crud_mode === 'full' ? ['read', 'create', 'update', 'delete'] : ['read'],
  searchable: t.fields.filter((x) => x.is_searchable).map((x) => x.name), fields: t.fields.map((x) => ({ ...x, writable: true })),
})
const specToField = (s: FieldSpecIn): FieldDef => f(s.name, s.type, {
  nullable: s.nullable, is_unique: !!s.unique, is_indexed: !!s.indexed, is_searchable: !!s.searchable, is_sensitive: !!s.sensitive,
  max_length: s.type === 'string' ? s.max_length ?? 255 : null, enum_values: s.enum_values ?? null, ref_table: s.ref_table ?? null,
})
const matches = (table: string, q: string | undefined) => {
  const all = rows.get(table) ?? [], t = systemTables.find((x) => x.name === table)
  if (!q) return [...all]
  const keys = t?.fields.filter((x) => x.is_searchable).map((x) => x.name) ?? []
  return all.filter((r) => keys.some((k) => String(r[k] ?? '').toLowerCase().includes(q.toLowerCase())))
}
const tasks: Task[] = []

// -- users, roles, keys, settings --------------------------------------------------------------------
const roles: Role[] = [
  { id: 'r1', name: 'Dispatcher', description: 'Handles orders and returns', is_system: false, permissions: [
    ...(['read', 'update'] as const).map((action) => ({ resource: 'orders', action, row_scope: 'all' as const, readable_fields: null, writable_fields: null })),
    { resource: 'rma', action: 'create', row_scope: 'all', readable_fields: null, writable_fields: null },
    { resource: 'products', action: 'read', row_scope: 'all', readable_fields: null, writable_fields: null },
    { resource: 'data.customers', action: 'read', row_scope: 'all', readable_fields: ['name', 'email', 'phone'], writable_fields: null },
  ] },
  { id: 'r2', name: 'Warehouse', description: 'Stock counts and transfers', is_system: false, permissions: [
    { resource: 'inventory', action: 'read', row_scope: 'all', readable_fields: null, writable_fields: null },
    { resource: 'inventory', action: 'manage', row_scope: 'all', readable_fields: null, writable_fields: null },
    { resource: 'products', action: 'read', row_scope: 'all', readable_fields: null, writable_fields: null },
  ] },
]
const users: User[] = ([
  ['Seav Pheng', 'owner', true, [], 0], ['Dara Sok', 'dara', false, ['r1'], 0], ['Lina Chan', 'lina', false, ['r1', 'r2'], 1],
  ['Mark Lee', 'mark', false, ['r1'], 4], ['Sophea Kim', 'sophea', false, ['r2'], 5], ['Tom Hart', 'tom', false, [], 14],
] as [string, string, boolean, string[], number][]).map(([name, user, admin, roleIds, d], i) => ({
  id: i === 0 ? 'u0' : uid(), full_name: name, email: `${user}@acme.example.com`, is_tenant_admin: admin, role_ids: roleIds,
  is_active: name !== 'Tom Hart', locked_until: name === 'Mark Lee' ? new Date(Date.now() + 9 * 60_000).toISOString() : null, last_login_at: iso(d, 9 + i, 5),
}))
const keys: ApiKey[] = [
  { id: uid(), key_id: 'tk_7f3a91c2', name: 'ERP sync', scopes: ['orders:read', 'products:read'], created_at: iso(45), expires_at: null, revoked_at: null, rotated_from_id: null },
  { id: uid(), key_id: 'tk_02bd55e0', name: 'Old importer', scopes: ['data.customers:create'], created_at: iso(120), expires_at: iso(-30), revoked_at: iso(20), rotated_from_id: null },
]
const gateways: Gateway[] = [{ id: uid(), name: 'Sandbox', provider: 'sandbox', public_config: {}, is_default: true, is_active: true, secret_version: 1, created_at: iso(50) }]

// -- webhooks ----------------------------------------------------------------------------------------
const hooks: Webhook[] = [
  { id: 'w1', url: 'https://erp.acme.example.com/hooks/orders', description: 'ERP order sync', event_types: ['order.created', 'order.status_changed'], is_active: true, consecutive_failures: 0, disabled_reason: null, secret_version: 2, created_at: iso(40) },
  { id: 'w2', url: 'https://ops.acme.example.com/payments', description: 'Finance alerts', event_types: ['payment.failed', 'payment.refunded'], is_active: true, consecutive_failures: 3, disabled_reason: null, secret_version: 1, created_at: iso(30) },
  { id: 'w3', url: 'https://legacy.acme.example.com/hook', description: 'Old integration', event_types: ['data.created', 'data.updated'], is_active: false, consecutive_failures: 1, disabled_reason: 'Endpoint returned 410 Gone', secret_version: 1, created_at: iso(90) },
]
const deliveries = new Map<string, WebhookDelivery[]>(hooks.map((h, hi) => [h.id, ([
  ['succeeded', 1, 200, 0], ['pending', 3, 503, 0], ['succeeded', 2, 200, 1], ['dead', 10, 500, 2], ['succeeded', 1, 204, 2],
] as [WebhookDelivery['status'], number, number, number][]).slice(hi).map(([status, attempts, code, d], i) => ({
  id: uid(), event_id: uid(), event_type: h.event_types[i % h.event_types.length], status, attempts,
  next_attempt_at: status === 'pending' ? new Date(Date.now() + 4 * 60_000).toISOString() : null, last_status_code: code,
  last_error: status === 'succeeded' ? null : `HTTP ${code}`, delivered_at: status === 'succeeded' ? iso(d) : null, created_at: iso(d, 9, 41 - i),
}))]))
const secret = (prefix = 'whsec_') => prefix + Array.from(crypto.getRandomValues(new Uint8Array(24)), (b) => b.toString(16).padStart(2, '0')).join('')

// -- platform ----------------------------------------------------------------------------------------
const tenants: Tenant[] = ([
  ['Acme Store', 'acme-store', 'active', 'shared', null], ['Globex Corp', 'globex', 'active', 'dedicated', 6000],
  ['Umbrella Labs', 'umbrella', 'active', 'dedicated', 3000], ['Initech', 'initech', 'suspended', 'shared', null], ['Hooli', 'hooli', 'active', 'shared', 1200],
] as [string, string, Tenant['status'], Tenant['isolation_mode'], number | null][]).map(([name, slug, status, mode, rl]) =>
  ({ id: uid(), name, slug, status, isolation_mode: mode, rate_limit_per_min: rl }))
const schemaVersion = 42
const dbs = new Map<string, TenantDatabase>(tenants.filter((t) => t.isolation_mode === 'dedicated').map((t, i) => [t.id, {
  tenant_id: t.id, dialect: 'postgresql', host: `db-${t.slug}.internal`, port: 5432, database: 'tenant', username: 'tenantcore', ssl_mode: 'verify-full',
  status: 'active', secret_version: 3, synced_schema_version: i ? 40 : schemaVersion,
}]))
const deadLetters: DeadLetter[] = [
  { id: uid(), tenant_id: tenants[0].id, source: 'webhook', reference_id: uid(), task_name: null, error: 'HTTP 500 after 10 attempts', created_at: iso(2), requeued_at: null },
  { id: uid(), tenant_id: tenants[1].id, source: 'task', reference_id: uid(), task_name: 'tenantcore.run_import', error: "ValueError: column 'email' is required", created_at: iso(1), requeued_at: null },
]
let mfaPending = ''

export const mock: Api = {
  login: async (_t, email) => (email ? wait(token(), 400) : fail('Email required')),
  platformLogin: async () => wait(token(), 400),
  logout: async () => wait(undefined),
  me: async () => wait({ kind: 'user', tenant_id: tenants[0].id, user_id: 'u0', is_tenant_admin: true, scopes: null, permissions: ['*'] }),
  mfaSetup: async () => { mfaPending = 'JBSWY3DPEHPK3PXP'; return wait({ secret: mfaPending, otpauth_uri: `otpauth://totp/TenantCore:admin?secret=${mfaPending}&issuer=TenantCore` }) },
  mfaConfirm: async (code) => (mfaPending && /^\d{6}$/.test(code) ? wait({ access_token: token().access_token, token_type: 'bearer', expires_in: 600 }) : fail('Invalid code.')),

  sales: async () => wait({
    items: Array.from({ length: 30 }, (_, i) => {
      const d = new Date(); d.setDate(d.getDate() - (29 - i))
      const revenue = 900 + 900 * (0.5 + 0.5 * Math.sin(i * 0.55)) + 500 * (i / 29)
      return { key: d.toISOString().slice(0, 10) + 'T00:00:00Z', currency: 'USD', orders: Math.round(revenue / 37.5), revenue: revenue.toFixed(2), refunded: (i % 7 === 0 ? 24 : 0).toFixed(2) }
    }),
  }),

  products: async (q) => wait({ items: products.filter((p) => !q || (p.name + ' ' + p.variants.map((v) => v.sku).join(' ')).toLowerCase().includes(q.toLowerCase())) }),
  createProduct: async (body) => {
    if (body.variants.some((v) => allVariants().some((x) => x.sku === v.sku))) return fail('A variant with this SKU already exists.')
    const id = uid()
    const p: Product = { id, name: body.name, description: body.description ?? null, status: body.status as Product['status'], is_public: body.is_public, created_at: now(),
      variants: body.variants.map((v) => mkVariant(id, v.sku, v.name, Number(v.price).toFixed(2))) }
    products.unshift(p); log('product.create', 'products', id); return wait(p)
  },
  updateProduct: async (id, body) => { const p = find(products, id, 'Product'); Object.assign(p, body); log('product.update', 'products', id); return wait(p) },
  addVariant: async (productId, body) => {
    const p = find(products, productId, 'Product')
    if (allVariants().some((x) => x.sku === body.sku)) return fail('A variant with this SKU already exists.')
    const v = mkVariant(p.id, body.sku, body.name, Number(body.price).toFixed(2)); v.currency = body.currency; p.variants.push(v); return wait(v)
  },
  warehouses: async () => wait({ items: warehouses }),
  createWarehouse: async (code, name) => {
    if (warehouses.some((w) => w.code === code)) return fail('A warehouse with this code already exists.')
    const w: Warehouse = { id: uid(), code, name, is_active: true, bins: [] }; warehouses.push(w); log('warehouse.create', 'inventory', w.id); return wait(w)
  },
  createBin: async (warehouseId, code) => {
    const w = find(warehouses, warehouseId, 'Warehouse')
    if (w.bins.some((b) => b.code === code)) return fail('A bin with this code already exists.')
    const b = { id: uid(), code, is_active: true }; w.bins.push(b); return wait(b)
  },
  stockLevels: async () => wait({ items: [...stock.entries()].map(([k, v]): StockLevel => { const [variant_id, bin_id] = k.split('|'); return { variant_id, bin_id, ...v, available: v.on_hand - v.reserved } }) }),
  adjustStock: async (variantId, binId, delta, note) => {
    const k = `${variantId}|${binId}`, cur = stock.get(k) ?? { on_hand: 0, reserved: 0 }
    if (cur.on_hand + delta < cur.reserved) return fail('Adjustment would leave less stock than is reserved.')
    stock.set(k, { ...cur, on_hand: cur.on_hand + delta }); log('stock.adjust', 'inventory', variantId, { delta, note: note ?? null })
    return wait({ applied: true, replayed: false })
  },

  orders: async (status, limit = 50) => wait({ items: orders.filter((o) => !status || o.status === status).slice(0, limit).map(summary) }),
  order: async (id) => wait(find(orders, id, 'Order')),
  createOrder: async (items, customer) => {
    try { items.forEach((i) => reserve(i.variant_id, i.qty)) } catch (e) { return fail((e as Error).message) }
    const o = addOrder(items, customer); log('order.create', 'orders', o.id); return wait(summary(o), 300)
  },
  lifecycle: async () => wait({ statuses, transitions }),
  transition: async (id, to, note) => {
    const o = find(orders, id, 'Order')
    if (!transitions.some(([a, b]) => a === o.status && b === to)) return fail(`Cannot move from ${o.status} to ${to}.`)
    move(o, to, note || null); log('order.transition', 'orders', id, { to }); return wait(summary(o), 250)
  },
  startPayment: async (orderId, gatewayId) => {
    const o = find(orders, orderId, 'Order'), g = gatewayId ? find(gateways, gatewayId, 'Gateway') : gateways.find((x) => x.is_default)
    if (!g) return fail('No default payment gateway is configured.')
    if (Number(o.amount_paid) >= Number(o.total)) return fail('This order is already paid.')
    const p: Payment = { id: uid(), order_id: o.id, gateway_id: g.id, provider_ref: 'sbx_' + uid().slice(0, 12), amount: o.total, currency: o.currency, status: 'pending', client_secret: 'sbx_secret_' + uid().slice(0, 8) }
    payments.push(p); log('payment.start', 'payments', p.id); return wait(p, 300)
  },
  createReturn: async (orderId, items, reason) => {
    const o = find(orders, orderId, 'Order')
    if (!['shipped', 'delivered'].includes(o.status)) return fail('Only shipped or delivered orders can be returned.')
    for (const l of items) { const it = find(o.items, l.order_item_id, 'Order line'); if (l.qty > it.qty - it.qty_returned) return fail(`At most ${it.qty - it.qty_returned} of ${it.sku} can be returned.`) }
    const r: Rma = { id: uid(), number: `RMA-${1001 + rmas.length}`, order_id: o.id, status: 'requested', reason, refund_amount: '0.00', created_at: now(), items,
      events: [{ from_status: null, to_status: 'requested', note: reason, created_at: now() }] }
    rmas.unshift(r); log('rma.create', 'rma', r.id); return wait(r)
  },
  returns: async (orderId) => wait({ items: rmas.filter((r) => !orderId || r.order_id === orderId).map(({ items: _i, events: _e, ...r }) => r) }),
  rma: async (id) => wait(find(rmas, id, 'Return')),
  rmaAction: async (id, action, extra = {}) => {
    const r = find(rmas, id, 'Return'), o = find(orders, r.order_id, 'Order')
    const flow: Record<string, [string[], string]> = { approve: [['requested'], 'approved'], reject: [['requested'], 'rejected'], receive: [['approved'], 'received'], refund: [['received'], 'refunded'], close: [['refunded', 'rejected', 'received'], 'closed'] }
    const [from, to] = flow[action]
    if (!from.includes(r.status)) return fail(`Cannot ${action} a return that is ${r.status}.`)
    if (action === 'receive') {
      if (!extra.restock_bin_id) return fail('restock_bin_id: a restock bin is required.')
      r.items.forEach((l) => {
        const it = find(o.items, l.order_item_id); it.qty_returned += l.qty
        if (l.restock) { const k = `${it.variant_id}|${extra.restock_bin_id}`, cur = stock.get(k) ?? { on_hand: 0, reserved: 0 }; cur.on_hand += l.qty; stock.set(k, cur) }
      })
    }
    if (action === 'refund') {
      const max = r.items.reduce((a, l) => a + Number(find(o.items, l.order_item_id).unit_price) * l.qty, 0)
      const amt = Number(extra.refund_amount ?? max)
      if (amt > max + 1e-9) return fail(`refund_amount: at most ${money(max)} can be refunded for this return.`)
      if (amt > Number(o.amount_paid) - Number(o.amount_refunded) + 1e-9) return fail('Refund exceeds the amount paid and not yet refunded.')
      r.refund_amount = money(amt); o.amount_refunded = money(Number(o.amount_refunded) + amt)
    }
    r.events.push({ from_status: r.status, to_status: to, note: extra.note ?? null, created_at: now() }); r.status = to
    log(`rma.${action}`, 'rma', id); return wait(r, 250)
  },

  tables: async () => wait({ items: systemTables.filter((t) => t.crud_mode !== 'disabled').map(asDataTable) }),
  rows: async (table, opts) => {
    const list = matches(table, opts.q)
    if (opts.sort) { const desc = opts.sort.startsWith('-'), k = opts.sort.replace(/^-/, ''); list.sort((a, b) => String(a[k] ?? '').localeCompare(String(b[k] ?? ''), undefined, { numeric: true }) * (desc ? -1 : 1)) }
    const offset = opts.offset ?? 0, limit = opts.limit ?? 25
    return wait({ items: list.slice(offset, offset + limit), limit, offset, next_offset: offset + limit < list.length ? offset + limit : null })
  },
  createRow: async (table, body) => {
    const t = systemTables.find((x) => x.name === table)
    const missing = t?.fields.find((x) => !x.nullable && (body[x.name] === undefined || body[x.name] === null || body[x.name] === ''))
    if (missing) return fail(`${missing.name}: Field required`)
    const r = { id: uid(), ...body, created_at: now(), updated_at: now() }; rows.set(table, [r, ...(rows.get(table) ?? [])]); log('data.create', `data.${table}`, r.id); return wait(r)
  },
  updateRow: async (table, id, body) => {
    const r = (rows.get(table) ?? []).find((x) => x.id === id); if (!r) return fail('Record not found.')
    Object.assign(r, body, { updated_at: now() }); log('data.update', `data.${table}`, id); return wait(r)
  },
  deleteRow: async (table, id) => { rows.set(table, (rows.get(table) ?? []).filter((r) => r.id !== id)); log('data.delete', `data.${table}`, id); return wait(undefined) },
  importFile: async (table, file, mode) => {
    const t: Task = { id: uid(), kind: 'import', status: 'queued', progress: {}, result: null, error: null, created_at: now(), input: { table, filename: file.name, mode } }
    tasks.unshift(t)
    const text = /\.csv$/i.test(file.name) ? await file.text() : ''
    setTimeout(() => { t.status = 'running' }, 600)
    setTimeout(() => {
      const lines = text.split(/\r?\n/).filter(Boolean)
      const done = (patch: Partial<Task>) => Object.assign(t, { finished_at: now(), ...patch })
      if (!lines.length) return done({ status: 'failed', error: 'The demo can only read CSV files with a header row.' })
      const head = lines[0].split(',').map((h) => h.trim())
      const unknown = head.filter((h) => !systemTables.find((x) => x.name === table)?.fields.some((x) => x.name === h))
      if (unknown.length) return done({ status: 'failed', error: `Unknown columns: ${unknown.join(', ')}` })
      const added = lines.slice(1).map((l) => { const vals = l.split(','); return { id: uid(), ...Object.fromEntries(head.map((h, i) => [h, vals[i]?.trim() || null])), created_at: now(), updated_at: now() } })
      rows.set(table, [...added, ...(rows.get(table) ?? [])])
      log('import.completed', `data.${table}`, t.id, { inserted: added.length })
      return done({ status: 'succeeded', result: { rows: added.length, inserted: added.length, invalid: 0, errors: [] } })
    }, 1800)
    return wait(t, 300)
  },
  task: async (id) => wait(find(tasks, id, 'Task')),

  users: async () => wait({ items: users }),
  createUser: async (body) => {
    if (users.some((u) => u.email === body.email)) return fail('A user with this email already exists.')
    if (body.password.length < 12) return fail('password: String should have at least 12 characters')
    const u: User = { id: uid(), email: body.email, full_name: body.full_name ?? null, is_active: true, is_tenant_admin: body.is_tenant_admin, role_ids: [], last_login_at: null, locked_until: null }
    users.push(u); log('user.create', 'users', u.id); return wait(u)
  },
  updateUser: async (id, body) => { const u = find(users, id, 'User'); Object.assign(u, body); log('user.update', 'users', id, body); return wait(u) },
  setUserRoles: async (id, roleIds) => { find(users, id, 'User').role_ids = [...roleIds]; log('user.roles', 'users', id, { role_ids: roleIds }); return wait(undefined) },
  roles: async () => wait({ items: roles }),
  createRole: async (body) => {
    if (roles.some((r) => r.name === body.name)) return fail('A role with this name already exists.')
    const r: Role = { id: uid(), name: body.name, description: body.description ?? null, is_system: false,
      permissions: body.permissions.map((p) => ({ resource: p.resource, action: p.action, row_scope: p.row_scope ?? 'all', readable_fields: p.readable_fields ?? null, writable_fields: p.writable_fields ?? null })) }
    roles.push(r); log('role.create', 'roles', r.id); return wait(r)
  },

  apiKeys: async () => wait({ items: keys }),
  createApiKey: async (name, scopes, days) => {
    const k: ApiKey = { id: uid(), key_id: 'tk_' + uid().slice(0, 8), name, scopes, created_at: now(), expires_at: days ? new Date(Date.now() + days * 86_400_000).toISOString() : null, revoked_at: null, rotated_from_id: null }
    keys.unshift(k); log('api_key.create', 'api_keys', k.id); return wait({ ...k, api_key: `${k.key_id}.${secret('')}` })
  },
  revokeApiKey: async (id) => { find(keys, id, 'API key').revoked_at = now(); log('api_key.revoke', 'api_keys', id); return wait(undefined) },
  rotateApiKey: async (id, grace = 3600) => {
    const old = find(keys, id, 'API key'); old.expires_at = new Date(Date.now() + grace * 1000).toISOString()
    const k: ApiKey = { ...old, id: uid(), key_id: 'tk_' + uid().slice(0, 8), created_at: now(), expires_at: null, rotated_from_id: old.id }
    keys.unshift(k); log('api_key.rotate', 'api_keys', k.id); return wait({ ...k, api_key: `${k.key_id}.${secret('')}` })
  },
  auditLogs: async (offset = 0, limit = 50) => wait({ items: audit.slice(offset, offset + limit) }),
  setRateLimit: async (perMin) => (perMin > 6000 ? fail('rate limit cannot exceed the platform maximum of 6000/min') : wait({ rate_limit_per_min: perMin, platform_max_per_min: 6000 })),
  gateways: async () => wait({ items: gateways }),
  createGateway: async (body) => {
    if (body.is_default) gateways.forEach((g) => { g.is_default = false })
    const g: Gateway = { id: uid(), name: body.name, provider: body.provider, public_config: {}, is_default: body.is_default || !gateways.length, is_active: true, secret_version: 1, created_at: now() }
    gateways.push(g); log('provider.create', 'payments', g.id); return wait(g)
  },
  rotateGatewaySecrets: async (id) => { const g = find(gateways, id, 'Gateway'); g.secret_version = (g.secret_version ?? 1) + 1; return wait(undefined) },

  webhooks: async () => wait({ items: hooks }),
  createWebhook: async (url, eventTypes, description) => {
    if (!url.startsWith('https://')) return fail('url: webhooks must use https.')
    const h: Webhook = { id: uid(), url, description: description || null, event_types: eventTypes, is_active: true, consecutive_failures: 0, disabled_reason: null, secret_version: 1, created_at: now() }
    hooks.push(h); deliveries.set(h.id, []); log('webhook.create', 'webhooks', h.id); return wait({ ...h, signing_secret: secret() })
  },
  updateWebhook: async (id, body) => {
    const h = find(hooks, id, 'Webhook'); Object.assign(h, body)
    if (body.is_active) { h.disabled_reason = null; h.consecutive_failures = 0 }
    log('webhook.update', 'webhooks', id); return wait(h)
  },
  deleteWebhook: async (id) => { hooks.splice(hooks.findIndex((h) => h.id === id), 1); log('webhook.delete', 'webhooks', id); return wait(undefined) },
  deliveries: async (id) => wait({ items: deliveries.get(id) ?? [] }),
  redeliver: async (id) => {
    for (const list of deliveries.values()) { const d = list.find((x) => x.id === id); if (d) Object.assign(d, { status: 'pending', attempts: 0, next_attempt_at: now() }) }
    return wait(undefined)
  },
  rotateSecret: async (id) => { find(hooks, id, 'Webhook').secret_version += 1; return wait({ signing_secret: secret() }) },

  tenants: async () => wait({ items: tenants }),
  createTenant: async (body) => {
    if (tenants.some((t) => t.slug === body.slug)) return fail('A tenant with this slug already exists.')
    const t: Tenant = { id: uid(), slug: body.slug, name: body.name, status: 'active', isolation_mode: 'shared', rate_limit_per_min: body.rate_limit_per_min ?? null }
    tenants.push(t); return wait({ tenant: t }, 400)
  },
  updateTenant: async (id, body) => { const t = find(tenants, id, 'Tenant'); Object.assign(t, Object.fromEntries(Object.entries(body).filter(([, v]) => v !== undefined))); return wait(t) },
  tenantDb: async (id) => { const d = dbs.get(id); return d ? wait(d) : fail('Not found.') },
  configureTenantDb: async (id, body) => {
    const t = find(tenants, id, 'Tenant'), prev = dbs.get(id)
    const { password: _pw, ...rest } = body
    const d: TenantDatabase = { tenant_id: id, ...rest, status: 'active', secret_version: (prev?.secret_version ?? 0) + 1, synced_schema_version: prev?.synced_schema_version ?? null }
    dbs.set(id, d); t.isolation_mode = 'dedicated'; return wait(d, 600)
  },
  syncTenantDb: async (id) => { const d = dbs.get(id); if (!d) return fail('Not found.'); d.synced_schema_version = schemaVersion; return wait({ applied: true, schema_version: schemaVersion }, 900) },
  disableTenantDb: async (id) => { const d = dbs.get(id); if (d) d.status = 'disabled'; return wait(undefined) },
  offboard: async (id, slug) => {
    const t = find(tenants, id, 'Tenant')
    if (slug !== t.slug) return fail("confirm_slug must equal the tenant's slug.")
    t.status = 'offboarding'
    const task: Task = { id: uid(), kind: 'offboard', status: 'queued', progress: {}, result: null, error: null, created_at: now(), attempts: 0 }
    tasks.unshift(task)
    setTimeout(() => { Object.assign(task, { status: 'succeeded', finished_at: now(), attempts: 1 }); tenants.splice(tenants.indexOf(t), 1) }, 4000)
    return wait(task, 500)
  },
  platformTask: async (id) => wait(find(tasks, id, 'Task')),
  deadLetters: async (includeRequeued = false) => wait({ items: deadLetters.filter((d) => includeRequeued || !d.requeued_at) }),
  requeueDeadLetter: async (id) => { find(deadLetters, id, 'Dead letter').requeued_at = now(); return wait({ requeued: true }) },
  slowQueries: async () => wait({
    pg_stat_statements: [
      { query: 'SELECT ... FROM app_orders WHERE tenant_id = $1 ORDER BY placed_at DESC LIMIT $2', calls: 18422, mean_ms: 3.41, total_ms: 62818.2, rows: 921100 },
      { query: 'SELECT ... FROM tenant_data.dt_customers WHERE search_vector @@ plainto_tsquery($1)', calls: 2210, mean_ms: 11.9, total_ms: 26299.0, rows: 15470 },
      { query: 'UPDATE stock_bins SET reserved = reserved + $1 WHERE ...', calls: 9930, mean_ms: 0.82, total_ms: 8142.6, rows: 9930 },
    ],
    seq_scan_heavy_tables: [{ schemaname: 'tenant_data', relname: 'dt_suppliers', seq_scan: 5120, seq_tup_read: 8_400_000, idx_scan: 12, n_live_tup: 1640 }],
    slow_query_log_threshold_ms: 200,
  }),
  systemTables: async () => wait({ items: systemTables }),
  createSystemTable: async (name, fields) => {
    if (systemTables.some((t) => t.name === name)) return fail('A table with this name already exists.')
    const t: SystemTable = { id: uid(), name, crud_mode: 'full', version: 1, fields: fields.map(specToField) }
    systemTables.push(t); rows.set(name, []); return wait({ table: t }, 500)
  },
  addSystemFields: async (id, fields) => {
    const t = find(systemTables, id, 'Table')
    const dup = fields.find((x) => t.fields.some((y) => y.name === x.name)); if (dup) return fail(`Field ${dup.name} already exists.`)
    if (fields.some((x) => !x.nullable)) return fail('New fields must be nullable: existing rows have no value for them.')
    t.fields.push(...fields.map(specToField)); t.version += 1; return wait({ table: t }, 400)
  },
  dropSystemFields: async (id, names) => { const t = find(systemTables, id, 'Table'); t.fields = t.fields.filter((x) => !names.includes(x.name)); t.version += 1; return wait({ table: t }, 400) },
  deleteSystemTable: async (id, confirm) => {
    const t = find(systemTables, id, 'Table')
    if (confirm !== t.name) return fail('confirm must equal the table name.')
    systemTables.splice(systemTables.indexOf(t), 1); rows.delete(t.name); return wait({ deleted: true }, 400)
  },
  exportSnapshot: async () => wait({ format: 'tenantcore.schema/1', exported_at: now(), tables: systemTables }),
}
