<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, idemKey } from '@/api/client'
import { can, currency } from '@/api/me'
import { num, opt, useBusy, useList, useLoader } from '@/composables'
import { money, when } from '@/format'
import { toCsv, download } from '@/ui'
import DataTable from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiPager from '@/components/UiPager.vue'
import VariantPicker, { type PickedVariant } from '@/components/VariantPicker.vue'

const router = useRouter()
const statuses = ref<{ code: string; label: string }[]>([])
useLoader(async () => { statuses.value = (await api.get('/orders/lifecycle')).statuses })
const list = useList((q) => api.get('/orders', q), { q: '', status: '', payment_status: '', from: '', to: '' })
const f = list.filters

const columns = [
  { key: 'number', label: 'Order', primary: true }, { key: 'customer', label: 'Customer' }, { key: 'status', label: 'Status' },
  { key: 'payment', label: 'Payment', hideOnMobile: true }, { key: 'total', label: 'Total', align: 'right' as const },
]

// New order
const open = ref(false)
const lines = ref<{ v: PickedVariant; qty: string }[]>([])
const form = reactive({ name: '', email: '', phone: '', notes: '', discount: '', shipping: '' })
const { busy, run } = useBusy()
const subtotal = computed(() => lines.value.reduce((s, l) => s + l.v.price * (num(l.qty) ?? 0), 0))
function addLine(v: PickedVariant) {
  const existing = lines.value.find((l) => l.v.id === v.id)
  if (existing) existing.qty = String((num(existing.qty) ?? 0) + 1)
  else lines.value.push({ v, qty: '1' })
}
function startNew() { lines.value = []; Object.assign(form, { name: '', email: '', phone: '', notes: '', discount: '', shipping: '' }); open.value = true }
async function create() {
  let id = ''
  const ok = await run('create', async () => {
    const o = await api.post('/orders', {
      items: lines.value.map((l) => ({ variant_id: l.v.id, quantity: num(l.qty) ?? 0 })),
      customer_name: opt(form.name), customer_email: opt(form.email), customer_phone: opt(form.phone), notes: opt(form.notes),
      discount: num(form.discount), shipping_total: num(form.shipping),
    }, { 'Idempotency-Key': idemKey() })
    id = o.id
  }, 'Order created.')
  if (ok) { open.value = false; router.push(`/orders/${id}`) }
}

async function exportCsv() {
  const all: any[] = []
  for (let offset = 0; offset < 5000; offset += 200) {
    const r = await api.get('/orders', { ...f, limit: 200, offset })
    all.push(...r.items)
    if (r.items.length < 200) break
  }
  download(`orders-${new Date().toISOString().slice(0, 10)}.csv`, toCsv(all.map((o) => ({
    number: o.number, placed_at: o.placed_at, status: o.status, payment_status: o.payment_status, customer_name: o.customer_name,
    customer_email: o.customer_email, currency: o.currency, subtotal: o.subtotal, discount: o.discount_total, shipping: o.shipping_total,
    tax: o.tax_total, total: o.total, paid: o.paid_total, refunded: o.refunded_total, source: o.source,
  }))), 'text/csv')
}
</script>

<template>
  <div class="page">
    <PageHeader title="Orders" caption="Every order, from the dashboard, the shop and the API.">
      <UiButton icon="download" @click="exportCsv">Export CSV</UiButton>
      <UiButton v-if="can('orders', 'create')" icon="plus" variant="primary" @click="startNew">New order</UiButton>
    </PageHeader>
    <UiCard>
      <div class="toolbar" style="padding-top: 16px">
        <input v-model="f.q" class="search-input" type="search" placeholder="Search number, name, email…" aria-label="Search orders" />
        <select v-model="f.status" class="search-input narrow" aria-label="Status">
          <option value="">All statuses</option><option v-for="s in statuses" :key="s.code" :value="s.code">{{ s.label }}</option>
        </select>
        <select v-model="f.payment_status" class="search-input narrow" aria-label="Payment">
          <option value="">Any payment</option>
          <option v-for="p in ['unpaid', 'partially_paid', 'paid', 'partially_refunded', 'refunded']" :key="p" :value="p">{{ p.replace(/_/g, ' ') }}</option>
        </select>
        <input v-model="f.from" class="search-input narrow" type="date" aria-label="From" />
        <input v-model="f.to" class="search-input narrow" type="date" aria-label="To" />
      </div>
      <LoadState :loading="list.loading.value" :error="list.error.value" :ready="list.items.value.length > 0" @retry="list.reload">
        <DataTable :columns="columns" :rows="list.items.value" row-key="id" clickable empty="No orders match." @select="(o) => router.push(`/orders/${o.id}`)">
          <template #cell-number="{ row }"><strong>#{{ row.number }}</strong><span class="caption">{{ when(row.placed_at) }} · {{ row.source }}</span></template>
          <template #cell-customer="{ row }"><span>{{ row.customer_name || '—' }}</span><span class="caption">{{ row.customer_email }}</span></template>
          <template #cell-status="{ row }"><StatusBadge :status="row.status" :label="row.status_label" /></template>
          <template #cell-payment="{ row }"><StatusBadge :status="row.payment_status" /></template>
          <template #cell-total="{ row }">{{ money(row.total, row.currency) }}</template>
        </DataTable>
        <UiPager v-model="list.offset.value" :total="list.total.value" :limit="list.limit" :count="list.items.value.length" />
      </LoadState>
    </UiCard>

    <UiModal :open="open" title="New order" wide submit-label="Create order" :busy="busy === 'create'" :submit-disabled="!lines.length" @close="open = false" @submit="create">
      <VariantPicker :currency="currency()" @pick="addLine" />
      <table v-if="lines.length" class="lines">
        <thead><tr><th>Item</th><th class="r">Price</th><th class="r">Qty</th><th class="r">Total</th><th /></tr></thead>
        <tbody>
          <tr v-for="(l, i) in lines" :key="l.v.id">
            <td><strong>{{ l.v.label }}</strong><div class="caption mono">{{ l.v.sku }}</div></td>
            <td class="r">{{ money(l.v.price, currency()) }}</td>
            <td class="r"><input v-model="l.qty" class="qty" inputmode="numeric" :aria-label="`Quantity of ${l.v.label}`" /></td>
            <td class="r">{{ money(l.v.price * (num(l.qty) ?? 0), currency()) }}</td>
            <td><UiButton variant="ghost" icon="trash" :aria-label="`Remove ${l.v.label}`" @click="lines.splice(i, 1)" /></td>
          </tr>
        </tbody>
        <tfoot><tr><td colspan="3" class="r muted">Subtotal (before tax, shipping and discount)</td><td class="r"><strong>{{ money(subtotal, currency()) }}</strong></td><td /></tr></tfoot>
      </table>
      <div class="grid3">
        <UiInput v-model="form.name" label="Customer name" autocomplete="off" />
        <UiInput v-model="form.email" label="Email" type="email" autocomplete="off" />
        <UiInput v-model="form.phone" label="Phone" autocomplete="off" />
      </div>
      <div class="grid2">
        <UiInput v-model="form.discount" label="Discount" inputmode="decimal" placeholder="0.00" />
        <UiInput v-model="form.shipping" label="Shipping fee" inputmode="decimal" placeholder="From checkout settings" />
      </div>
      <UiInput v-model="form.notes" label="Notes" multiline :rows="2" />
    </UiModal>
  </div>
</template>

<style scoped>
.narrow { flex: 0 1 170px !important; }
.lines { width: 100%; border-collapse: collapse; font-size: 13px; }
.lines th { text-align: left; font-weight: 500; color: var(--text-secondary); padding: 6px 4px; border-bottom: 1px solid var(--border-default); }
.lines td { padding: 8px 4px; border-bottom: 1px solid var(--border-default); vertical-align: middle; }
.lines tfoot td { border: 0; }
.r { text-align: right !important; }
.qty { width: 64px; padding: 6px 8px; border: 1px solid var(--border-strong); border-radius: var(--radius-sm); text-align: right; }
</style>
