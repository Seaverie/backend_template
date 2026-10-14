<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import type { Order, Product, SalesRow, StockLevel } from '@/api/types'
import BarChart from '@/components/BarChart.vue'
import DataTable, { type Column } from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import StatCard from '@/components/StatCard.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import { useLoader } from '@/composables'
import { money, number, orderTone, title, when } from '@/format'
import { download, toCsv } from '@/ui'

const router = useRouter()
const days = ref(30)
const sales = ref<SalesRow[]>([])
const stock = ref<StockLevel[]>([])
const products = ref<Product[]>([])
const orders = ref<Order[]>([])
const partial = ref<string[]>([])

// Each panel loads independently, so a role without (say) analytics access still sees its orders.
const { loading, error, reload } = useLoader(async () => {
  const to = new Date(), from = new Date(to.getTime() - days.value * 86_400_000)
  const missing: string[] = []
  const safe = async <T>(p: Promise<T>, what: string, empty: T) => p.catch(() => { missing.push(what); return empty })
  const [s, st, pr, o] = await Promise.all([
    safe(api.sales(from.toISOString(), to.toISOString(), 'day'), 'sales', { items: [] as SalesRow[] }),
    safe(api.stockLevels(), 'stock', { items: [] as StockLevel[] }),
    safe(api.products(), 'products', { items: [] as Product[] }),
    safe(api.orders(undefined, 5), 'orders', { items: [] as Order[] }),
  ])
  if (missing.length === 4) throw new Error('You do not have access to any dashboard data.')
  sales.value = s.items; stock.value = st.items; products.value = pr.items; orders.value = o.items; partial.value = missing
})
function setRange(d: number) { days.value = d; reload() }

const currency = computed(() => {
  const totals = new Map<string, number>()
  for (const r of sales.value) totals.set(r.currency, (totals.get(r.currency) ?? 0) + Number(r.revenue))
  return [...totals.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? 'USD'
})
const rows = computed(() => sales.value.filter((r) => r.currency === currency.value))
const revenue = computed(() => rows.value.reduce((a, r) => a + Number(r.revenue) - Number(r.refunded), 0))
const count = computed(() => rows.value.reduce((a, r) => a + r.orders, 0))
const points = computed(() => rows.value.map((r) => ({ label: new Date(r.key).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }), value: Number(r.revenue) })))

// Stock levels are per bin and carry only ids: sum per variant and join with the catalog.
const variantInfo = computed(() => new Map(products.value.flatMap((p) => p.variants.map((v) => [v.id, { sku: v.sku, name: v.name }] as const))))
const lowStock = computed(() => {
  const byVariant = new Map<string, number>()
  for (const s of stock.value) byVariant.set(s.variant_id, (byVariant.get(s.variant_id) ?? 0) + s.available)
  for (const id of variantInfo.value.keys()) if (!byVariant.has(id)) byVariant.set(id, 0)
  return [...byVariant.entries()].filter(([, a]) => a < 10).sort((a, b) => a[1] - b[1])
    .map(([id, available]) => ({ id, available, ...(variantInfo.value.get(id) ?? { sku: id.slice(0, 8), name: 'Unknown variant' }) }))
})
const columns: Column[] = [
  { key: 'number', label: 'Order', primary: true }, { key: 'status', label: 'Status' }, { key: 'total', label: 'Total', align: 'right' },
  { key: 'payment', label: 'Payment', hideOnMobile: true }, { key: 'placed_at', label: 'Placed' },
]
function exportCsv() {
  download(`sales-last-${days.value}-days.csv`, toCsv(sales.value.map((r) => ({ day: r.key.slice(0, 10), currency: r.currency, orders: r.orders, revenue: r.revenue, refunded: r.refunded }))), 'text/csv')
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Dashboard</h1><p class="muted">Last {{ days }} days · {{ currency }}</p></div>
      <div class="actions">
        <div class="range" role="group" aria-label="Date range">
          <button v-for="d in [7, 30, 90]" :key="d" type="button" :class="{ on: days === d }" :aria-pressed="days === d" @click="setRange(d)">{{ d }}d</button>
        </div>
        <UiButton icon="download" :disabled="!sales.length" @click="exportCsv">Export CSV</UiButton>
        <UiButton variant="primary" icon="plus" @click="router.push({ path: '/orders', query: { new: '1' } })">New order</UiButton>
      </div>
    </div>
    <p v-if="partial.length" class="note warn">Your role cannot see {{ partial.join(', ') }}, so those panels are empty.</p>

    <LoadState :loading="loading" :error="error" :ready="!!sales.length || !!orders.length" @retry="reload">
      <div class="stats">
        <StatCard label="Net revenue" :value="money(revenue, currency)" note="Revenue minus refunds" />
        <StatCard label="Orders" :value="number(count)" :note="`Sales in the last ${days} days`" />
        <StatCard label="Average order value" :value="count ? money(revenue / count, currency) : '—'" note="Net revenue ÷ orders" />
        <StatCard label="Low-stock items" :value="String(lowStock.length)" note="Fewer than 10 available" />
      </div>

      <div class="row">
        <UiCard title="Revenue by day" :caption="`${currency} · gross, before refunds`">
          <BarChart v-if="points.some((p) => p.value > 0)" :points="points" :currency="currency" />
          <EmptyState v-else icon="activity" title="No sales in this period" body="Revenue appears once orders reach a paid status." />
        </UiCard>
        <UiCard title="Low stock" caption="Available = on hand − reserved, all bins">
          <template #actions><UiButton variant="ghost" @click="router.push('/catalog')">Manage</UiButton></template>
          <ul v-if="lowStock.length" class="stock">
            <li v-for="s in lowStock.slice(0, 6)" :key="s.id">
              <div><p class="name">{{ s.name }}</p><p class="caption">{{ s.sku }} · {{ s.available }} available</p></div>
              <UiBadge :tone="s.available <= 0 ? 'danger' : 'warning'">{{ s.available <= 0 ? 'Out' : 'Low' }}</UiBadge>
            </li>
          </ul>
          <EmptyState v-else icon="package" title="Stock looks healthy" body="Every variant has at least 10 available." />
        </UiCard>
      </div>

      <UiCard title="Recent orders" caption="Newest first">
        <template #actions><UiButton variant="ghost" @click="router.push('/orders')">View all</UiButton></template>
        <DataTable v-if="orders.length" :columns="columns" :rows="orders" row-key="id" clickable @select="router.push('/orders')">
          <template #cell-number="{ row }"><strong>#{{ row.number }}</strong><div class="caption">{{ row.customer.name ?? row.customer.email ?? 'Guest' }}</div></template>
          <template #cell-status="{ row }"><UiBadge :tone="orderTone[row.status] ?? 'neutral'">{{ title(row.status) }}</UiBadge></template>
          <template #cell-total="{ row }">{{ money(row.total, row.currency) }}</template>
          <template #cell-payment="{ row }">{{ Number(row.amount_refunded) > 0 ? 'Refunded' : Number(row.amount_paid) >= Number(row.total) ? 'Paid' : 'Awaiting payment' }}</template>
          <template #cell-placed_at="{ row }">{{ when(row.placed_at) }}</template>
        </DataTable>
        <EmptyState v-else icon="orders" title="No orders yet" body="Create your first order to see it here.">
          <UiButton variant="primary" icon="plus" @click="router.push({ path: '/orders', query: { new: '1' } })">New order</UiButton>
        </EmptyState>
      </UiCard>
    </LoadState>
  </div>
</template>

<style scoped>
.row { display: grid; grid-template-columns: minmax(0, 1fr) 360px; gap: var(--space-24); }
.stock { list-style: none; margin: 0; padding: 0; }
.stock li { display: flex; align-items: center; gap: 12px; justify-content: space-between; padding: 12px 24px; border-top: 1px solid var(--border-default); }
.name { font-weight: 500; }
.range { display: inline-flex; border: 1px solid var(--border-strong); border-radius: var(--radius-md); overflow: hidden; background: var(--bg-surface); }
.range button { border: 0; background: transparent; padding: 0 12px; min-height: 36px; cursor: pointer; color: var(--text-secondary); font-weight: 500; }
.range button + button { border-left: 1px solid var(--border-strong); }
.range button.on { background: var(--brand-soft); color: var(--brand-primary); }
@media (max-width: 1199px) { .row { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 767px) { .row { gap: var(--space-16); } .stock li { padding: 12px 16px; } }
</style>
