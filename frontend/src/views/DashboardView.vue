<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import type { Order, SalesRow, StockLevel } from '@/api/types'
import BarChart from '@/components/BarChart.vue'
import DataTable, { type Column } from '@/components/DataTable.vue'
import StatCard from '@/components/StatCard.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import { money, number, orderTone, title, when } from '@/format'

const router = useRouter()
const sales = ref<SalesRow[]>([])
const stock = ref<StockLevel[]>([])
const orders = ref<Order[]>([])
const error = ref('')

onMounted(async () => {
  const to = new Date(), from = new Date(to.getTime() - 30 * 86_400_000)
  try {
    const [s, st, o] = await Promise.all([api.sales(from.toISOString(), to.toISOString(), 'day'), api.stockLevels(), api.orders(undefined, 5)])
    sales.value = s.items.filter((r) => r.currency === 'USD'); stock.value = st.items.filter((x) => x.available < 10).slice(0, 4); orders.value = o.items.slice(0, 5)
  } catch (e) { error.value = e instanceof Error ? e.message : 'Could not load the dashboard.' }
})

const revenue = computed(() => sales.value.reduce((a, r) => a + Number(r.revenue) - Number(r.refunded), 0))
const count = computed(() => sales.value.reduce((a, r) => a + r.orders, 0))
const points = computed(() => sales.value.map((r) => ({ label: new Date(r.key).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }), value: Number(r.revenue) })))
const columns: Column[] = [
  { key: 'number', label: 'Order', primary: true }, { key: 'status', label: 'Status' }, { key: 'total', label: 'Total', align: 'right' },
  { key: 'payment', label: 'Payment', hideOnMobile: true }, { key: 'placed_at', label: 'Placed' },
]
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Dashboard</h1><p class="muted">Last 30 days · USD</p></div>
      <div class="actions">
        <UiButton>Export CSV</UiButton>
        <UiButton variant="primary" icon="plus" @click="router.push('/orders')">New order</UiButton>
      </div>
    </div>
    <p v-if="error" class="caption" role="alert">{{ error }}</p>

    <div class="stats">
      <StatCard label="Net revenue" :value="money(revenue)" note="Revenue minus refunds" />
      <StatCard label="Orders" :value="number(count)" note="GET /analytics/sales" />
      <StatCard label="Average order value" :value="count ? money(revenue / count) : '—'" note="Net revenue ÷ orders" />
      <StatCard label="Low-stock items" :value="String(stock.length)" note="Available below 10" />
    </div>

    <div class="row">
      <UiCard title="Revenue by day" caption="GET /analytics/sales?group_by=day">
        <BarChart v-if="points.length" :points="points" currency="USD" />
      </UiCard>
      <UiCard title="Low stock" caption="GET /stock/levels">
        <ul class="stock">
          <li v-for="s in stock" :key="s.sku">
            <div><p class="name">{{ s.name }}</p><p class="caption">{{ s.sku }} · {{ s.available }} available</p></div>
            <UiBadge :tone="s.available === 0 ? 'danger' : 'warning'">{{ s.available === 0 ? 'Out' : 'Low' }}</UiBadge>
          </li>
        </ul>
      </UiCard>
    </div>

    <UiCard title="Recent orders" caption="GET /orders?limit=5">
      <template #actions><UiButton variant="ghost" @click="router.push('/orders')">View all</UiButton></template>
      <DataTable :columns="columns" :rows="orders" row-key="id" clickable @select="router.push('/orders')">
        <template #cell-number="{ row }"><strong>#{{ row.number }}</strong><div class="caption">{{ row.customer.name }}</div></template>
        <template #cell-status="{ row }"><UiBadge :tone="orderTone[row.status]">{{ title(row.status) }}</UiBadge></template>
        <template #cell-total="{ row }">{{ money(row.total, row.currency) }}</template>
        <template #cell-payment="{ row }">{{ Number(row.amount_refunded) > 0 ? 'Refunded' : Number(row.amount_paid) > 0 ? 'Paid' : 'Awaiting payment' }}</template>
        <template #cell-placed_at="{ row }">{{ when(row.placed_at) }}</template>
      </DataTable>
    </UiCard>
  </div>
</template>

<style scoped>
.row { display: grid; grid-template-columns: minmax(0, 1fr) 360px; gap: var(--space-24); }
.stock { list-style: none; margin: 0; padding: 0; }
.stock li { display: flex; align-items: center; gap: 12px; justify-content: space-between; padding: 12px 24px; border-top: 1px solid var(--border-default); }
.name { font-weight: 500; }
@media (max-width: 1199px) { .row { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 767px) { .row { gap: var(--space-16); } .stock li { padding: 12px 16px; } }
</style>
