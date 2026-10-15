<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { api } from '@/api/client'
import { can, hasModule } from '@/api/me'
import { num, opt, useBusy, useLoader } from '@/composables'
import { dateTime, money, title, when } from '@/format'
import { ask, copy } from '@/ui'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiSelect from '@/components/UiSelect.vue'

const props = defineProps<{ id: string }>()
const order = ref<any>(null)
const lifecycle = ref<any>(null)
const gateways = ref<any[]>([])
const deliveries = ref<any[]>([])
const { busy, run } = useBusy()

const { loading, error, reload } = useLoader(async () => {
  const [o, lc] = await Promise.all([api.get(`/orders/${props.id}`), api.get('/orders/lifecycle')])
  order.value = o; lifecycle.value = lc
  if (hasModule('payments') && can('payments')) gateways.value = (await api.get('/payments/gateways').catch(() => ({ items: [] }))).items
  if (hasModule('delivery_tracking') && can('deliveries')) deliveries.value = (await api.get('/deliveries', { order_id: props.id }).catch(() => ({ items: [] }))).items
})
const statusOf = (code: string) => lifecycle.value?.statuses.find((s: any) => s.code === code)
const cur = computed(() => order.value?.currency ?? 'USD')
const charges = computed(() => (order.value?.payments ?? []) as any[])

// Status changes
const transition = reactive({ open: false, to: '', note: '' })
async function move(to: string) {
  const s = statusOf(to)
  if (s?.stock_effect === 'release' || s?.is_final) {
    transition.to = to; transition.note = ''; transition.open = true
    return
  }
  await run('t:' + to, async () => { order.value = await api.post(`/orders/${props.id}/transition`, { to }) }, `Marked ${s?.label ?? to}.`)
}
async function confirmMove() {
  const ok = await run('t:' + transition.to, async () => {
    order.value = await api.post(`/orders/${props.id}/transition`, { to: transition.to, note: opt(transition.note) })
  }, `Marked ${statusOf(transition.to)?.label ?? transition.to}.`)
  if (ok) transition.open = false
}

// Customer details
const edit = reactive({ open: false, name: '', email: '', phone: '', notes: '', address: '' })
const addressText = (a: any) => (a ? Object.values(a).filter(Boolean).join(', ') : '')
function startEdit() {
  const o = order.value
  Object.assign(edit, { open: true, name: o.customer_name ?? '', email: o.customer_email ?? '', phone: o.customer_phone ?? '', notes: o.notes ?? '', address: o.shipping_address?.text ?? addressText(o.shipping_address) })
}
async function saveEdit() {
  const ok = await run('edit', async () => {
    order.value = await api.patch(`/orders/${props.id}`, {
      customer_name: edit.name.trim(), customer_email: edit.email.trim(), customer_phone: edit.phone.trim(), notes: edit.notes,
      shipping_address: edit.address.trim() ? { text: edit.address.trim() } : undefined,
    })
  }, 'Order updated.')
  if (ok) edit.open = false
}

// Payments
const enabled = computed(() => gateways.value.filter((g) => g.is_enabled).map((g) => g.provider))
const pay = reactive({ open: false, provider: 'manual', method: 'cash', amount: '', reference: '' })
const started = ref<any>(null)
function startPay() {
  Object.assign(pay, { open: true, provider: 'manual', method: 'cash', amount: String(order.value.balance_due ?? ''), reference: '' })
  started.value = null
}
async function savePay() {
  const ok = await run('pay', async () => {
    const r = await api.post(`/orders/${props.id}/payments`, {
      provider: pay.provider, amount: num(pay.amount), method: pay.provider === 'manual' ? pay.method : null, reference: opt(pay.reference),
    })
    started.value = r
  }, pay.provider === 'manual' ? 'Payment recorded.' : 'Payment started.')
  if (ok) { if (pay.provider === 'manual') pay.open = false; await reload() }
}
async function sandbox(p: any, outcome: 'succeeded' | 'failed') {
  await run('sb:' + p.id, () => api.post(`/payments/${p.id}/sandbox`, { outcome }), `Test payment ${outcome}.`)
  await reload()
}
const refund = reactive({ open: false, payment: null as any, amount: '', reference: '' })
function startRefund(p: any) {
  const left = Number(p.amount) - charges.value.filter((c) => c.parent_id === p.id && c.kind === 'refund' && c.status === 'succeeded').reduce((s, c) => s + Number(c.amount), 0)
  Object.assign(refund, { open: true, payment: p, amount: String(Math.max(0, left).toFixed(2)), reference: '' })
}
async function saveRefund() {
  const ok = await run('refund', () => api.post(`/payments/${refund.payment.id}/refund`, { amount: num(refund.amount), reference: opt(refund.reference) }), 'Refund recorded.')
  if (ok) { refund.open = false; await reload() }
}

// PromptPay
const qrUrl = ref('')
const qrPayload = ref('')
async function showPromptPay() {
  await run('qr', async () => {
    const [img, info] = await Promise.all([api.blobUrl(`/orders/${props.id}/promptpay`, { format: 'png', size: 360 }), api.get(`/orders/${props.id}/promptpay`)])
    if (qrUrl.value) URL.revokeObjectURL(qrUrl.value)
    qrUrl.value = img; qrPayload.value = info.payload
  })
}
onBeforeUnmount(() => { if (qrUrl.value) URL.revokeObjectURL(qrUrl.value) })

// Delivery
async function createDelivery() {
  await run('delivery', () => api.post('/deliveries', { order_id: props.id, destination_label: addressText(order.value.shipping_address) || null }), 'Delivery created.')
  await reload()
}

async function copyId() { await copy(order.value.id) }
async function cancelOrder() {
  const target = order.value.allowed_transitions.find((t: string) => statusOf(t)?.stock_effect === 'release')
  if (target && await ask({ title: `Cancel order #${order.value.number}?`, body: 'Reserved stock goes back on sale. Refunds are separate.', confirmLabel: statusOf(target)?.label ?? 'Cancel', danger: true }))
    await run('t:' + target, async () => { order.value = await api.post(`/orders/${props.id}/transition`, { to: target }) }, 'Order cancelled.')
}
</script>

<template>
  <div class="page">
    <LoadState :loading="loading" :error="error" :ready="!!order" @retry="reload">
      <template v-if="order">
        <PageHeader :title="`Order #${order.number}`" back="/orders">
          <template #sub>
            <div class="chips sub">
              <StatusBadge :status="order.status" :label="order.status_label" />
              <StatusBadge :status="order.payment_status" />
              <span class="caption">Placed {{ dateTime(order.placed_at) }} · {{ title(order.source) }}</span>
            </div>
          </template>
          <template v-if="can('orders', 'update')">
            <UiButton v-for="t in order.allowed_transitions" :key="t" :loading="busy === 't:' + t"
                      :variant="statusOf(t)?.stock_effect === 'release' ? 'secondary' : 'primary'"
                      @click="statusOf(t)?.stock_effect === 'release' ? cancelOrder() : move(t)">
              {{ statusOf(t)?.stock_effect === 'release' ? statusOf(t)?.label : `Mark ${statusOf(t)?.label ?? t}` }}
            </UiButton>
          </template>
        </PageHeader>

        <div class="split">
          <div class="col">
            <UiCard title="Items">
              <table class="items">
                <tbody>
                  <tr v-for="i in order.items" :key="i.id">
                    <td><strong>{{ i.name }}</strong><div class="caption mono">{{ i.sku }}</div></td>
                    <td class="r muted">{{ money(i.unit_price, cur) }} × {{ i.quantity }}</td>
                    <td class="r">{{ money(i.line_total, cur) }}</td>
                  </tr>
                </tbody>
              </table>
              <div class="totals">
                <div class="kv"><span>Subtotal</span><span>{{ money(order.subtotal, cur) }}</span></div>
                <div v-if="Number(order.discount_total)" class="kv"><span>Discount</span><span>−{{ money(order.discount_total, cur) }}</span></div>
                <div class="kv"><span>Shipping</span><span>{{ money(order.shipping_total, cur) }}</span></div>
                <div class="kv"><span>Tax{{ order.tax_inclusive ? ' (included)' : '' }}</span><span>{{ money(order.tax_total, cur) }}</span></div>
                <div class="kv total"><span>Total</span><span>{{ money(order.total, cur) }}</span></div>
                <div class="kv"><span>Paid</span><span>{{ money(order.paid_total, cur) }}</span></div>
                <div v-if="Number(order.refunded_total)" class="kv"><span>Refunded</span><span>{{ money(order.refunded_total, cur) }}</span></div>
                <div class="kv"><span>Balance due</span><strong>{{ money(order.balance_due, cur) }}</strong></div>
              </div>
            </UiCard>

            <UiCard v-if="hasModule('payments')" title="Payments">
              <template #actions>
                <UiButton v-if="hasModule('qr_codes') && order.currency === 'THB' && Number(order.balance_due) > 0" icon="qr" :loading="busy === 'qr'" @click="showPromptPay">PromptPay QR</UiButton>
                <UiButton v-if="can('payments', 'create') && Number(order.balance_due) > 0" icon="card" variant="primary" @click="startPay">Take payment</UiButton>
              </template>
              <div class="card-body">
                <p v-if="!charges.length" class="muted">No payments yet.</p>
                <div v-for="p in charges" :key="p.id" class="pay" :class="{ child: p.kind === 'refund' }">
                  <div class="grow">
                    <strong>{{ p.kind === 'refund' ? 'Refund' : 'Payment' }} · {{ money(p.amount, p.currency) }}</strong>
                    <div class="caption">{{ title(p.provider) }}<template v-if="p.method"> · {{ title(p.method) }}</template><template v-if="p.reference"> · {{ p.reference }}</template> · {{ when(p.created_at) }}</div>
                    <div v-if="p.failure_message" class="caption err">{{ p.failure_message }}</div>
                  </div>
                  <StatusBadge :status="p.status" />
                  <template v-if="p.provider === 'sandbox' && p.status === 'pending' && can('payments', 'update')">
                    <UiButton :loading="busy === 'sb:' + p.id" @click="sandbox(p, 'succeeded')">Succeed</UiButton>
                    <UiButton variant="ghost" @click="sandbox(p, 'failed')">Fail</UiButton>
                  </template>
                  <UiButton v-if="p.kind === 'charge' && p.status === 'succeeded' && can('payments', 'update')" variant="ghost" icon="undo" @click="startRefund(p)">Refund</UiButton>
                </div>
                <div v-if="qrUrl" class="qr">
                  <img :src="qrUrl" alt="PromptPay QR code for the balance due" width="220" height="220" />
                  <div>
                    <p><strong>Scan with any Thai banking app</strong></p>
                    <p class="caption">Amount: {{ money(order.balance_due, cur) }}. Record the payment once it arrives — the QR code cannot confirm it.</p>
                    <UiButton icon="copy" variant="ghost" @click="copy(qrPayload)">Copy payload</UiButton>
                  </div>
                </div>
              </div>
            </UiCard>

            <UiCard title="History">
              <ol class="timeline">
                <li v-for="(h, i) in [...order.history].reverse()" :key="i">
                  <span class="dot" :style="{ background: statusOf(h.to_status)?.color ?? 'var(--border-strong)' }" />
                  <div><strong>{{ statusOf(h.to_status)?.label ?? h.to_status }}</strong><span class="caption"> · {{ dateTime(h.created_at) }} · {{ title(h.actor_type) }}</span>
                    <p v-if="h.note" class="muted">{{ h.note }}</p></div>
                </li>
              </ol>
            </UiCard>
          </div>

          <div class="col">
            <UiCard title="Customer">
              <template #actions><UiButton v-if="can('orders', 'update')" variant="ghost" icon="edit" @click="startEdit">Edit</UiButton></template>
              <div class="card-body">
                <div class="kv"><span>Name</span><span>{{ order.customer_name || '—' }}</span></div>
                <div class="kv"><span>Email</span><span>{{ order.customer_email || '—' }}</span></div>
                <div class="kv"><span>Phone</span><span>{{ order.customer_phone || '—' }}</span></div>
                <div class="kv"><span>Ship to</span><span>{{ addressText(order.shipping_address) || '—' }}</span></div>
                <div v-if="order.customer_id" class="kv"><span>Account</span><UiBadge tone="brand">Signed-in customer</UiBadge></div>
                <p v-if="order.notes" class="note">{{ order.notes }}</p>
              </div>
            </UiCard>
            <UiCard title="Stock">
              <div class="card-body">
                <div class="kv"><span>State</span><span>{{ title(order.stock_state ?? 'none') }}</span></div>
                <p class="caption">Stock is reserved, shipped or released as the order moves through its statuses. Change that in Settings → Orders.</p>
              </div>
            </UiCard>
            <UiCard v-if="hasModule('delivery_tracking') && can('deliveries')" title="Delivery">
              <template #actions><UiButton v-if="!deliveries.length && can('deliveries', 'create')" icon="truck" :loading="busy === 'delivery'" @click="createDelivery">Create delivery</UiButton></template>
              <div class="card-body">
                <p v-if="!deliveries.length" class="muted">Not out for delivery.</p>
                <div v-for="d in deliveries" :key="d.id" class="kv"><span>{{ d.destination_label || 'Delivery' }}</span><StatusBadge :status="d.status" /></div>
                <RouterLink v-if="deliveries.length" to="/deliveries">Open deliveries</RouterLink>
              </div>
            </UiCard>
            <UiCard v-if="hasModule('returns') && can('rma', 'create')" title="Returns">
              <div class="card-body"><RouterLink :to="{ path: '/returns', query: { order: order.id } }">Start a return for this order</RouterLink></div>
            </UiCard>
            <p class="caption mono idline" @click="copyId">ID {{ order.id }}</p>
          </div>
        </div>
      </template>
    </LoadState>

    <UiModal :open="transition.open" :title="`Mark ${statusOf(transition.to)?.label ?? ''}`" submit-label="Confirm" :busy="busy.startsWith('t:')" @close="transition.open = false" @submit="confirmMove">
      <p class="muted">This is a final status{{ statusOf(transition.to)?.stock_effect === 'release' ? ' and gives reserved stock back' : '' }}.</p>
      <UiInput v-model="transition.note" label="Note (optional)" multiline :rows="2" />
    </UiModal>

    <UiModal :open="edit.open" title="Edit customer details" submit-label="Save" :busy="busy === 'edit'" @close="edit.open = false" @submit="saveEdit">
      <div class="grid2"><UiInput v-model="edit.name" label="Name" /><UiInput v-model="edit.email" label="Email" type="email" /></div>
      <UiInput v-model="edit.phone" label="Phone" />
      <UiInput v-model="edit.address" label="Shipping address" multiline :rows="2" />
      <UiInput v-model="edit.notes" label="Notes" multiline :rows="2" />
    </UiModal>

    <UiModal :open="pay.open" title="Take payment" :submit-label="started && pay.provider !== 'manual' ? undefined : 'Continue'" :busy="busy === 'pay'" @close="pay.open = false" @submit="savePay">
      <template v-if="!started || pay.provider === 'manual'">
        <UiSelect v-model="pay.provider" label="How" :options="[{ value: 'manual', label: 'Recorded by hand (cash, transfer, terminal)' }, ...enabled.map((p) => ({ value: p, label: p === 'sandbox' ? 'Sandbox (test)' : title(p) }))]" />
        <UiSelect v-if="pay.provider === 'manual'" v-model="pay.method" label="Method" :options="[{ value: 'cash', label: 'Cash' }, { value: 'bank_transfer', label: 'Bank transfer' }, { value: 'card_terminal', label: 'Card terminal' }, { value: 'promptpay', label: 'PromptPay' }, { value: 'other', label: 'Other' }]" />
        <div class="grid2"><UiInput v-model="pay.amount" label="Amount" inputmode="decimal" :hint="`Balance due ${money(order?.balance_due, cur)}`" /><UiInput v-model="pay.reference" label="Reference" placeholder="Receipt or transfer number" /></div>
        <p v-if="!enabled.length" class="caption">Turn on a card gateway in Settings → Payments to charge cards.</p>
      </template>
      <template v-else>
        <p class="note">Payment created and waiting for the customer.</p>
        <template v-if="started.client_secret">
          <p class="caption">Finish it in your shop or app with Stripe.js using this client secret and publishable key:</p>
          <div class="secret-out"><code>{{ started.client_secret }}</code><UiButton variant="ghost" icon="copy" @click="copy(started.client_secret)" /></div>
        </template>
        <p v-else-if="pay.provider === 'sandbox'" class="caption">Use <b>Succeed</b> or <b>Fail</b> next to the payment to simulate the outcome.</p>
      </template>
    </UiModal>

    <UiModal :open="refund.open" title="Refund payment" submit-label="Refund" danger :busy="busy === 'refund'" @close="refund.open = false" @submit="saveRefund">
      <p class="muted">{{ refund.payment?.provider === 'manual' ? 'This records a refund you give back by hand.' : `The money goes back through ${title(refund.payment?.provider ?? '')}.` }}</p>
      <div class="grid2"><UiInput v-model="refund.amount" label="Amount" inputmode="decimal" /><UiInput v-model="refund.reference" label="Reference" /></div>
    </UiModal>
  </div>
</template>

<style scoped>
.sub { margin-top: 6px; align-items: center; }
.col { display: grid; gap: 24px; min-width: 0; }
.items { width: 100%; border-collapse: collapse; }
.items td { padding: 12px 24px; border-top: 1px solid var(--border-default); }
.r { text-align: right; white-space: nowrap; }
.totals { padding: 12px 24px 20px; border-top: 1px solid var(--border-default); display: grid; gap: 2px; }
.kv.total { font-weight: 600; font-size: 15px; border-top: 1px solid var(--border-default); margin-top: 6px; padding-top: 8px; }
.kv.total > :first-child { color: var(--text-primary); }
.pay { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 10px 0; border-top: 1px solid var(--border-default); }
.pay:first-of-type { border-top: 0; }
.pay.child { padding-left: 20px; }
.grow { flex: 1 1 200px; min-width: 0; }
.err { color: var(--danger); }
.qr { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; padding-top: 12px; border-top: 1px solid var(--border-default); }
.qr img { border: 1px solid var(--border-default); border-radius: var(--radius-md); }
.qr > div { flex: 1 1 200px; display: grid; gap: 6px; justify-items: start; }
.timeline { list-style: none; margin: 0; padding: 0 24px 20px; display: grid; gap: 14px; }
.timeline li { display: flex; gap: 12px; align-items: flex-start; }
.timeline .dot { width: 10px; height: 10px; border-radius: 50%; margin-top: 5px; flex: none; }
.idline { cursor: copy; overflow-wrap: anywhere; }
@media (max-width: 767px) { .items td, .totals { padding-left: 16px; padding-right: 16px; } .timeline { padding: 0 16px 16px; } }
</style>
