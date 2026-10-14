<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/api/client'
import type { Gateway, Lifecycle, Order, OrderDetail, Payment, Rma, RmaSummary, Variant, Warehouse } from '@/api/types'
import DataTable, { type Column } from '@/components/DataTable.vue'
import DetailPanel from '@/components/DetailPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiSelect from '@/components/UiSelect.vue'
import { useBusy, useLoader } from '@/composables'
import { dateTime, isEmail, isMoney, money, orderTone, rmaTone, title, when } from '@/format'
import { download, errText, toast, toCsv } from '@/ui'

const route = useRoute()
const router = useRouter()
const orders = ref<Order[]>([])
const lifecycle = ref<Lifecycle | null>(null)
const filter = ref<string | undefined>()
const selected = ref<OrderDetail | null>(null)
const returns = ref<RmaSummary[]>([])
const open = ref(false)
const note = ref('')
const { busy, run } = useBusy()
const noReturns = { items: [] as RmaSummary[] }

const { loading, error, reload } = useLoader(async () => {
  const [o, l] = await Promise.all([api.orders(filter.value, 200), lifecycle.value ? Promise.resolve(lifecycle.value) : api.lifecycle()])
  orders.value = o.items; lifecycle.value = l
  if (selected.value) await refreshSelected()
  else if (o.items[0] && window.matchMedia('(min-width: 1200px)').matches) await pick(o.items[0])
})
async function refreshSelected() {
  if (!selected.value) return
  const id = selected.value.id
  const [d, r] = await Promise.all([api.order(id), api.returns(id).catch(() => noReturns)])
  selected.value = d; returns.value = r.items
}
async function pick(o: Order) {
  try {
    const [d, r] = await Promise.all([api.order(o.id), api.returns(o.id).catch(() => noReturns)])
    selected.value = d; returns.value = r.items; open.value = true; note.value = ''
  } catch (e) { toast(errText(e), 'err') }
}
function setFilter(code: string | undefined) { filter.value = code; selected.value = null; reload() }

async function move(to: string) {
  if (!selected.value) return
  const id = selected.value.id
  if (await run(to, () => api.transition(id, to, note.value), `Order moved to ${label(to).toLowerCase()}.`)) { note.value = ''; await reload() }
}

const next = computed(() => (lifecycle.value?.transitions ?? []).filter(([from]) => from === selected.value?.status).map(([, to]) => to))
const label = (code: string) => lifecycle.value?.statuses.find((s) => s.code === code)?.label ?? title(code)
const isSale = (code: string) => lifecycle.value?.statuses.find((s) => s.code === code)?.is_sale !== false
const tabs = computed(() => [{ code: undefined, label: 'All' }, ...(lifecycle.value?.statuses ?? []).map((s) => ({ code: s.code, label: s.label }))])
const payState = (o: Order) => (Number(o.amount_refunded) > 0 ? 'Refunded' : Number(o.amount_paid) >= Number(o.total) ? 'Paid' : 'Awaiting payment')
const balance = computed(() => (selected.value ? Number(selected.value.total) - Number(selected.value.amount_paid) : 0))
const columns: Column[] = [
  { key: 'number', label: 'Order', primary: true }, { key: 'status', label: 'Status' }, { key: 'total', label: 'Total', align: 'right' },
  { key: 'payment', label: 'Payment', hideOnMobile: true }, { key: 'placed_at', label: 'Placed' },
]

function exportCsv() {
  download(`orders-${new Date().toISOString().slice(0, 10)}.csv`, toCsv(orders.value.map((o) => ({
    number: o.number, status: o.status, total: o.total, currency: o.currency, amount_paid: o.amount_paid, amount_refunded: o.amount_refunded,
    customer_name: o.customer.name ?? '', customer_email: o.customer.email ?? '', placed_at: o.placed_at,
  }))), 'text/csv')
}

// -- reference data for the forms (loaded on first use) --
const variants = ref<Variant[]>([])
const warehouses = ref<Warehouse[]>([])
const gateways = ref<Gateway[] | null>(null)
async function loadVariants() { variants.value = (await api.products()).items.filter((p) => p.status !== 'archived').flatMap((p) => p.variants.filter((v) => v.is_active)) }
async function loadBins() { if (!warehouses.value.length) warehouses.value = (await api.warehouses()).items }
const binOptions = computed(() => warehouses.value.flatMap((w) => w.bins.filter((b) => b.is_active).map((b) => ({ value: b.id, label: `${w.code} / ${b.code}` }))))

// -- new order --
const newOrder = reactive({ open: false, name: '', email: '', lines: [{ variant_id: '', qty: '1' }] })
async function openNewOrder() {
  Object.assign(newOrder, { open: true, name: '', email: '', lines: [{ variant_id: '', qty: '1' }] })
  try { await loadVariants() } catch (e) { toast(errText(e, 'Could not load products.'), 'err') }
}
const variantOptions = computed(() => variants.value.map((v) => ({ value: v.id, label: `${v.sku} · ${v.name} · ${money(v.price, v.currency)}` })))
const orderTotal = computed(() => newOrder.lines.reduce((a, l) => a + Number(variants.value.find((v) => v.id === l.variant_id)?.price ?? 0) * (Number(l.qty) || 0), 0))
const orderValid = computed(() => newOrder.lines.length > 0 && newOrder.lines.every((l) => l.variant_id && Number.isInteger(Number(l.qty)) && Number(l.qty) > 0)
  && new Set(newOrder.lines.map((l) => l.variant_id)).size === newOrder.lines.length && (!newOrder.email || isEmail(newOrder.email)))
async function submitOrder() {
  const customer: Record<string, string> = {}
  if (newOrder.name.trim()) customer.name = newOrder.name.trim()
  if (newOrder.email.trim()) customer.email = newOrder.email.trim()
  let created: Order | undefined
  const ok = await run('order', async () => { created = await api.createOrder(newOrder.lines.map((l) => ({ variant_id: l.variant_id, qty: Number(l.qty) })), customer) })
  if (ok && created) {
    newOrder.open = false
    toast(`Order #${created.number} created. Stock is reserved until it ships or is cancelled.`)
    filter.value = undefined; selected.value = null; await reload(); await pick(created)
  }
}

// -- payment --
const pay = reactive({ open: false, gateway: '', result: null as Payment | null })
async function openPayment() {
  Object.assign(pay, { open: true, gateway: '', result: null })
  if (gateways.value === null) gateways.value = (await api.gateways().catch(() => ({ items: [] as Gateway[] }))).items
  pay.gateway = gateways.value.find((g) => g.is_default)?.id ?? gateways.value[0]?.id ?? ''
}
async function submitPayment() {
  if (!selected.value) return
  const id = selected.value.id
  await run('pay', async () => { pay.result = await api.startPayment(id, pay.gateway || undefined) })
}

// -- returns --
interface RetLine { order_item_id: string; label: string; max: number; qty: string; restock: boolean }
const ret = reactive({ open: false, reason: '', lines: [] as RetLine[] })
function openReturn() {
  if (!selected.value) return
  Object.assign(ret, { open: true, reason: '', lines: selected.value.items.filter((i) => i.qty > i.qty_returned).map((i) => ({ order_item_id: i.id, label: `${i.sku} · ${i.name}`, max: i.qty - i.qty_returned, qty: '0', restock: true })) })
}
const retValid = computed(() => ret.reason.trim().length > 0 && ret.lines.some((l) => Number(l.qty) > 0) && ret.lines.every((l) => Number.isInteger(Number(l.qty)) && Number(l.qty) >= 0 && Number(l.qty) <= l.max))
async function submitReturn() {
  if (!selected.value) return
  const id = selected.value.id
  const items = ret.lines.filter((l) => Number(l.qty) > 0).map((l) => ({ order_item_id: l.order_item_id, qty: Number(l.qty), restock: l.restock }))
  if (await run('ret', () => api.createReturn(id, items, ret.reason.trim()), 'Return requested. Approve it when the customer may send the items back.')) {
    ret.open = false; await refreshSelected()
  }
}
const rma = reactive({ open: false, data: null as Rma | null, note: '', bin: '', amount: '' })
function suggestRefund() {
  if (!rma.data || !selected.value) return
  const items = selected.value.items
  const value = rma.data.items.reduce((a, l) => a + Number(items.find((i) => i.id === l.order_item_id)?.unit_price ?? 0) * l.qty, 0)
  rma.amount = Math.min(value, refundable.value).toFixed(2)
}
// Refunds go back through the payment provider, so only money actually received can be refunded.
const refundable = computed(() => (selected.value ? Math.max(0, Number(selected.value.amount_paid) - Number(selected.value.amount_refunded)) : 0))
async function openRma(r: RmaSummary) {
  try { Object.assign(rma, { open: true, data: await api.rma(r.id), note: '', bin: '', amount: '' }) } catch (e) { toast(errText(e), 'err'); return }
  if (rma.data?.status === 'approved') await loadBins().catch(() => {})
  if (rma.data?.status === 'received') suggestRefund()
}
const rmaActions = computed(() => ({ requested: ['approve', 'reject'], approved: ['receive'], received: ['refund', 'close'], refunded: ['close'], rejected: ['close'] } as Record<string, string[]>)[rma.data?.status ?? ''] ?? [])
const done: Record<string, string> = { approve: 'approved', reject: 'rejected', receive: 'received', refund: 'refunded', close: 'closed' }
async function rmaAct(action: string) {
  if (!rma.data) return
  if (action === 'receive' && !rma.bin) { toast('Choose the bin the returned items go back into.', 'err'); return }
  if (action === 'refund' && !isMoney(rma.amount)) { toast('Enter a refund amount such as 24.00.', 'err'); return }
  const id = rma.data.id
  const extra = { note: rma.note || undefined, restock_bin_id: action === 'receive' ? rma.bin : undefined, refund_amount: action === 'refund' ? rma.amount : undefined }
  let updated: Rma | undefined
  if (await run(action, async () => { updated = await api.rmaAction(id, action, extra) }, `Return ${done[action]}.`) && updated) {
    rma.data = updated; rma.note = ''
    if (updated.status === 'approved') await loadBins().catch(() => {})
    await reload()
    if (updated.status === 'received') suggestRefund()
  }
}
const itemName = (id: string) => selected.value?.items.find((i) => i.id === id)?.name ?? id
const lifeOpen = ref(false)

onMounted(() => { if (route.query.new) { openNewOrder(); router.replace({ query: {} }) } })
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Orders</h1><p class="muted">Create orders, move them through the lifecycle, take payments and handle returns.</p></div>
      <div class="actions">
        <UiButton icon="download" :disabled="!orders.length" @click="exportCsv">Export CSV</UiButton>
        <UiButton icon="settings" @click="lifeOpen = true">Lifecycle</UiButton>
        <UiButton variant="primary" icon="plus" @click="openNewOrder">New order</UiButton>
      </div>
    </div>

    <div class="tabs" role="tablist" aria-label="Filter by status">
      <button v-for="t in tabs" :key="t.label" role="tab" :aria-selected="filter === t.code" class="tab" :class="{ on: filter === t.code }" @click="setFilter(t.code)">{{ t.label }}</button>
    </div>

    <LoadState :loading="loading" :error="error" :ready="!!lifecycle" @retry="reload">
      <div class="split">
        <UiCard :title="filter ? `${label(filter)} orders` : 'All orders'" :caption="`${orders.length} shown · newest first`">
          <template #actions><UiButton variant="ghost" icon="refresh" :loading="loading" @click="reload">Refresh</UiButton></template>
          <EmptyState v-if="!orders.length" icon="orders" :title="filter ? 'No orders with this status' : 'No orders yet'" body="Orders appear here when customers check out or when you create one.">
            <UiButton variant="primary" icon="plus" @click="openNewOrder">New order</UiButton>
          </EmptyState>
          <DataTable v-else :columns="columns" :rows="orders" row-key="id" :selected="selected?.id" clickable @select="pick">
            <template #cell-number="{ row }"><strong>#{{ row.number }}</strong><div class="caption">{{ row.customer.name ?? row.customer.email ?? 'Guest' }}</div></template>
            <template #cell-status="{ row }"><UiBadge :tone="orderTone[row.status] ?? 'neutral'">{{ label(row.status) }}</UiBadge></template>
            <template #cell-total="{ row }">{{ money(row.total, row.currency) }}</template>
            <template #cell-payment="{ row }">{{ payState(row) }}</template>
            <template #cell-placed_at="{ row }">{{ when(row.placed_at) }}</template>
          </DataTable>
        </UiCard>

        <DetailPanel :open="open && !!selected" label="Order detail" @close="open = false">
          <template v-if="selected">
            <header class="d-head">
              <div class="d-title"><h2>Order #{{ selected.number }}</h2><UiBadge :tone="orderTone[selected.status] ?? 'neutral'">{{ label(selected.status) }}</UiBadge></div>
              <p class="caption">{{ [selected.customer.name, selected.customer.email].filter(Boolean).join(' · ') || 'Guest' }} · {{ dateTime(selected.placed_at) }}</p>
            </header>
            <section class="d-sec">
              <div class="sec-head"><span class="label">Items</span><span class="caption">Stock {{ selected.stock_state }}</span></div>
              <div v-for="i in selected.items" :key="i.id" class="line">
                <div><p>{{ i.name }}</p><p class="caption">{{ i.sku }} · {{ i.qty }} × {{ money(i.unit_price, selected.currency) }}<template v-if="i.qty_returned"> · {{ i.qty_returned }} returned</template></p></div>
                <span>{{ money(i.line_total, selected.currency) }}</span>
              </div>
              <div class="kv"><span>Total</span><strong>{{ money(selected.total, selected.currency) }}</strong></div>
            </section>
            <section class="d-sec">
              <div class="sec-head"><span class="label">Next status</span></div>
              <template v-if="next.length">
                <UiInput v-model="note" label="Note (optional)" placeholder="e.g. tracking number" :maxlength="500" />
                <div class="btns">
                  <UiButton v-for="to in next" :key="to" :variant="isSale(to) ? 'primary' : 'secondary'" :loading="busy === to" :disabled="!!busy && busy !== to" @click="move(to)">
                    {{ isSale(to) ? `Mark as ${label(to).toLowerCase()}` : `${label(to)} order` }}
                  </UiButton>
                </div>
              </template>
              <p v-else class="caption">No further transitions: this is a final status.</p>
            </section>
            <section class="d-sec">
              <div class="sec-head"><span class="label">Payment</span><span class="caption">{{ payState(selected) }}</span></div>
              <div class="kv"><span>Paid</span><span>{{ money(selected.amount_paid, selected.currency) }}</span></div>
              <div class="kv"><span>Refunded</span><span>{{ money(selected.amount_refunded, selected.currency) }}</span></div>
              <div class="btns">
                <UiButton icon="card" :disabled="balance <= 0 || !isSale(selected.status)" @click="openPayment">Take payment</UiButton>
                <UiButton icon="undo" :disabled="!['shipped', 'delivered'].includes(selected.status) || selected.items.every((i) => i.qty_returned >= i.qty)" @click="openReturn">Start return</UiButton>
              </div>
            </section>
            <section v-if="returns.length" class="d-sec">
              <div class="sec-head"><span class="label">Returns</span></div>
              <button v-for="r in returns" :key="r.id" type="button" class="rma-row" @click="openRma(r)">
                <span><strong>{{ r.number }}</strong><span class="caption"> · {{ r.reason }}</span></span>
                <UiBadge :tone="rmaTone[r.status] ?? 'neutral'">{{ title(r.status) }}</UiBadge>
              </button>
            </section>
            <section class="d-sec">
              <div class="sec-head"><span class="label">Status history</span><span class="caption">append-only</span></div>
              <ol class="history">
                <li v-for="(h, i) in selected.history" :key="i" :class="{ latest: i === 0 }"><span>{{ label(h.to_status) }}</span><span class="caption">{{ when(h.created_at) }}<template v-if="h.note"> · {{ h.note }}</template></span></li>
              </ol>
            </section>
          </template>
        </DetailPanel>
      </div>
    </LoadState>

    <!-- New order -->
    <UiModal :open="newOrder.open" title="New order" caption="Prices come from the catalog; stock is reserved immediately." submit-label="Create order"
             :busy="busy === 'order'" :submit-disabled="!orderValid" wide @close="newOrder.open = false" @submit="submitOrder">
      <div class="grid2">
        <UiInput v-model="newOrder.name" label="Customer name" autocomplete="off" />
        <UiInput v-model="newOrder.email" label="Customer email" type="email" :error="newOrder.email && !isEmail(newOrder.email) ? 'Enter a valid email address.' : ''" />
      </div>
      <p class="section-label">Items</p>
      <p v-if="!variants.length" class="note warn">No active products yet. Add one under Catalog &amp; stock first.</p>
      <div v-for="(l, i) in newOrder.lines" :key="i" class="order-line">
        <UiSelect v-model="l.variant_id" :label="`Product ${i + 1}`" :options="variantOptions" placeholder="Choose a product" />
        <UiInput v-model="l.qty" label="Qty" inputmode="numeric" />
        <UiButton variant="ghost" icon="trash" :aria-label="`Remove line ${i + 1}`" :disabled="newOrder.lines.length === 1" @click="newOrder.lines.splice(i, 1)" />
      </div>
      <div class="between">
        <UiButton variant="ghost" icon="plus" :disabled="newOrder.lines.length >= 100" @click="newOrder.lines.push({ variant_id: '', qty: '1' })">Add line</UiButton>
        <strong>Total {{ money(orderTotal) }}</strong>
      </div>
    </UiModal>

    <!-- Payment -->
    <UiModal :open="pay.open" title="Take payment" :caption="selected ? `Order #${selected.number} · balance ${money(balance, selected.currency)}` : ''"
             :submit-label="pay.result ? undefined : 'Start payment'" :busy="busy === 'pay'" :submit-disabled="!gateways?.length"
             @close="pay.open = false; refreshSelected()" @submit="submitPayment">
      <template v-if="!pay.result">
        <UiSelect v-if="gateways && gateways.length" v-model="pay.gateway" label="Payment gateway" :options="gateways.map((g) => ({ value: g.id, label: `${g.name} (${g.provider})${g.is_default ? ' · default' : ''}` }))" />
        <p v-else class="note warn">No payment gateway is configured. Add one under Settings → Payment gateways.</p>
        <p class="caption">The payment is created with the provider. The order is marked paid when the provider's signed webhook confirms it, never from the browser.</p>
      </template>
      <template v-else>
        <p class="note">Payment {{ pay.result.provider_ref }} for {{ money(pay.result.amount, pay.result.currency) }} is <strong>{{ pay.result.status }}</strong>.</p>
        <p class="caption">It completes when the provider sends its signed webhook to POST /webhooks/payments/{gateway_id}. Refresh the order afterwards to see it paid.</p>
      </template>
    </UiModal>

    <!-- Start return -->
    <UiModal :open="ret.open" title="Start a return" :caption="selected ? `Order #${selected.number}` : ''" submit-label="Request return" :busy="busy === 'ret'"
             :submit-disabled="!retValid" @close="ret.open = false" @submit="submitReturn">
      <div v-for="l in ret.lines" :key="l.order_item_id" class="ret-line">
        <div class="ret-name"><p>{{ l.label }}</p><p class="caption">Up to {{ l.max }}</p></div>
        <UiInput v-model="l.qty" label="Qty" inputmode="numeric" :error="Number(l.qty) > l.max ? `Max ${l.max}` : ''" />
        <UiCheck v-model="l.restock" label="Restock" />
      </div>
      <UiInput v-model="ret.reason" label="Reason" required multiline :rows="2" :maxlength="500" placeholder="e.g. Damaged in transit" />
    </UiModal>

    <!-- RMA detail -->
    <UiModal :open="rma.open" :title="rma.data ? `Return ${rma.data.number}` : 'Return'" @close="rma.open = false">
      <template v-if="rma.data">
        <div class="kv"><span>Status</span><UiBadge :tone="rmaTone[rma.data.status] ?? 'neutral'">{{ title(rma.data.status) }}</UiBadge></div>
        <div class="kv"><span>Reason</span><span>{{ rma.data.reason }}</span></div>
        <div v-if="Number(rma.data.refund_amount)" class="kv"><span>Refunded</span><span>{{ money(rma.data.refund_amount ?? 0, selected?.currency) }}</span></div>
        <p class="section-label">Items</p>
        <div v-for="l in rma.data.items" :key="l.order_item_id" class="kv"><span>{{ itemName(l.order_item_id) }}</span><span>{{ l.qty }}{{ l.restock ? ' · restock' : '' }}</span></div>
        <template v-if="rmaActions.length">
          <p class="section-label">Next step</p>
          <UiSelect v-if="rmaActions.includes('receive')" v-model="rma.bin" label="Restock into bin" :options="binOptions" placeholder="Choose a bin" />
          <template v-if="rmaActions.includes('refund')">
            <UiInput v-if="refundable > 0" v-model="rma.amount" label="Refund amount" inputmode="decimal" :hint="`Up to ${money(refundable, selected?.currency)} received on this order can be refunded.`" />
            <p v-else class="note warn">No payment was received for this order, so there is nothing to refund. Close the return instead.</p>
          </template>
          <UiInput v-model="rma.note" label="Note (optional)" :maxlength="2000" />
          <div class="btns">
            <UiButton v-for="a in rmaActions" :key="a" :variant="a === 'reject' ? 'danger' : a === 'close' ? 'secondary' : 'primary'" :loading="busy === a"
                      :disabled="(!!busy && busy !== a) || (a === 'refund' && refundable <= 0)" @click="rmaAct(a)">{{ a === 'close' ? 'Close return' : title(a) }}</UiButton>
          </div>
        </template>
        <p class="section-label">History</p>
        <ol class="history">
          <li v-for="(e, i) in [...rma.data.events].reverse()" :key="i" :class="{ latest: i === 0 }"><span>{{ title(e.to_status) }}</span><span class="caption">{{ when(e.created_at) }}<template v-if="e.note"> · {{ e.note }}</template></span></li>
        </ol>
      </template>
    </UiModal>

    <!-- Lifecycle -->
    <UiModal :open="lifeOpen" title="Order lifecycle" caption="Statuses and allowed transitions for this workspace." @close="lifeOpen = false">
      <div v-for="s in lifecycle?.statuses ?? []" :key="s.code" class="life">
        <div class="life-head"><UiBadge :tone="orderTone[s.code] ?? 'neutral'">{{ s.label }}</UiBadge>
          <span class="caption">{{ [s.is_initial && 'initial', s.is_terminal && 'final', s.stock_effect !== 'none' && `stock: ${s.stock_effect}`, !s.is_sale && 'not counted as a sale'].filter(Boolean).join(' · ') }}</span>
        </div>
        <p class="caption">→ {{ (lifecycle?.transitions ?? []).filter(([a]) => a === s.code).map(([, b]) => label(b)).join(', ') || 'no further transitions' }}</p>
      </div>
      <p class="caption">Changing the lifecycle is an API operation: PUT /order-lifecycle.</p>
    </UiModal>
  </div>
</template>

<style scoped>
.d-head { padding: 20px 24px; padding-right: 64px; }
.d-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 6px; }
.d-sec { padding: 16px 24px; border-top: 1px solid var(--border-default); display: flex; flex-direction: column; gap: 10px; }
.sec-head { display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.line { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; }
.btns { display: flex; gap: 8px; flex-wrap: wrap; }
.btns > * { flex: 1 1 140px; }
.history { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.history li { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; padding-left: 16px; position: relative; }
.history li::before { content: ''; position: absolute; left: 0; top: 6px; width: 8px; height: 8px; border-radius: 50%; background: var(--border-strong); }
.history li.latest::before { background: var(--brand-primary); }
.rma-row { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 8px 10px; border: 1px solid var(--border-default); border-radius: var(--radius-md); background: var(--bg-surface); cursor: pointer; text-align: left; }
.rma-row:hover { background: var(--bg-canvas); }
.order-line { display: grid; grid-template-columns: minmax(0, 1fr) 90px auto; gap: 10px; align-items: end; }
.ret-line { display: grid; grid-template-columns: minmax(0, 1fr) 90px auto; gap: 12px; align-items: center; }
.ret-name p:first-child { font-size: 13px; }
.between { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.life { display: grid; gap: 4px; padding: 8px 0; border-bottom: 1px solid var(--border-default); }
.life-head { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
@media (max-width: 767px) { .d-head, .d-sec { padding-left: 16px; padding-right: 16px; } .d-head { padding-right: 64px; } }
@media (max-width: 479px) { .order-line, .ret-line { grid-template-columns: minmax(0, 1fr) 72px auto; } }
</style>
