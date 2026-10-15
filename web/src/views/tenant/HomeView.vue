<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import { can, currency, hasModule, me } from '@/api/me'
import { useLoader } from '@/composables'
import { isoDay, money, number, when } from '@/format'
import { tenantNav } from '@/nav'
import AppIcon from '@/components/AppIcon.vue'
import BarChart from '@/components/BarChart.vue'
import DataTable from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiCard from '@/components/UiCard.vue'
import UiTabs from '@/components/UiTabs.vue'

const showSales = computed(() => hasModule('orders') && can('analytics'))
const range = ref('30')
const sales = ref<any>(null)
const recent = ref<any[]>([])
const low = ref<any[]>([])
const hello = computed(() => {
  const h = new Date().getHours()
  const name = me.tenant?.user.full_name?.split(' ')[0]
  return `${h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening'}${name ? ', ' + name : ''}`
})
const links = computed(() => tenantNav().flatMap((g) => g.items).filter((i) => i.to !== '/').slice(0, 8))

const { loading, error, reload } = useLoader(async () => {
  const jobs: Promise<unknown>[] = []
  if (showSales.value) {
    const days = Number(range.value)
    jobs.push(api.get('/analytics/sales', { from: isoDay(1 - days), to: isoDay(), interval: days > 60 ? 'week' : 'day' }).then((r) => { sales.value = r }))
  }
  if (hasModule('orders') && can('orders')) jobs.push(api.get('/orders', { limit: 6 }).then((r) => { recent.value = r.items }))
  if (hasModule('inventory') && can('inventory')) jobs.push(api.get('/inventory/stock', { low_only: true, limit: 6 }).then((r) => { low.value = r.items }))
  await Promise.all(jobs)
})
watch(range, () => reload())
const points = computed(() => (sales.value?.series ?? []).map((p: any) => ({ label: p.period, value: Number(p.revenue) })))
const cur = computed(() => sales.value?.currency ?? currency())
</script>

<template>
  <div class="page">
    <PageHeader :title="hello" caption="Here is how things are going." />
    <LoadState :loading="loading" :error="error" :ready="!!sales || !showSales" @retry="reload">
      <template v-if="showSales && sales">
        <UiTabs v-model="range" :tabs="[{ key: '7', label: '7 days' }, { key: '30', label: '30 days' }, { key: '90', label: '90 days' }]" />
        <div class="stats">
          <StatCard label="Revenue" :value="money(sales.revenue, cur)" :note="sales.refunded > 0 ? `${money(sales.refunded, cur)} refunded` : undefined" />
          <StatCard label="Orders" :value="number(sales.orders)" />
          <StatCard label="Average order" :value="money(sales.average_order_value, cur)" />
          <StatCard label="Items sold" :value="number(sales.items_sold)" />
        </div>
        <UiCard title="Revenue" :caption="`${sales.from} to ${sales.to} · ${sales.timezone}`"><BarChart :points="points" :currency="cur" /></UiCard>
      </template>
      <div class="split">
        <UiCard v-if="recent.length || (hasModule('orders') && can('orders'))" title="Latest orders">
          <template #actions><RouterLink to="/orders">All orders</RouterLink></template>
          <DataTable :columns="[{ key: 'number', label: 'Order', primary: true }, { key: 'customer_name', label: 'Customer' }, { key: 'status', label: 'Status' }, { key: 'total', label: 'Total', align: 'right' }]"
                     :rows="recent" row-key="id" empty="No orders yet." clickable @select="(o) => $router.push(`/orders/${o.id}`)">
            <template #cell-number="{ row }"><strong>#{{ row.number }}</strong><span class="caption">{{ when(row.placed_at) }}</span></template>
            <template #cell-customer_name="{ row }">{{ row.customer_name || '—' }}</template>
            <template #cell-status="{ row }"><StatusBadge :status="row.status" :label="row.status_label" /></template>
            <template #cell-total="{ row }">{{ money(row.total, row.currency) }}</template>
          </DataTable>
        </UiCard>
        <UiCard v-if="hasModule('inventory') && can('inventory')" title="Running low" caption="At or below their low-stock level.">
          <template #actions><RouterLink to="/inventory">Inventory</RouterLink></template>
          <DataTable :columns="[{ key: 'sku', label: 'Item', primary: true }, { key: 'available', label: 'Available', align: 'right' }]" :rows="low" row-key="variant_id" empty="Nothing is running low.">
            <template #cell-sku="{ row }"><strong>{{ row.product_name }}<template v-if="row.variant_name"> – {{ row.variant_name }}</template></strong><span class="caption mono">{{ row.sku }}</span></template>
          </DataTable>
        </UiCard>
      </div>
      <UiCard v-if="!showSales" title="Jump in">
        <div class="links">
          <RouterLink v-for="l in links" :key="l.to" :to="l.to" class="link"><AppIcon :name="l.icon" /><span>{{ l.label }}</span></RouterLink>
          <p v-if="!links.length" class="muted">Your role does not include any pages yet. Ask a workspace admin for access.</p>
        </div>
      </UiCard>
    </LoadState>
  </div>
</template>

<style scoped>
.links { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; padding: 0 24px 24px; }
.link { display: flex; align-items: center; gap: 10px; padding: 14px; border: 1px solid var(--border-default); border-radius: var(--radius-md); color: var(--text-primary); font-weight: 500; }
.link:hover { text-decoration: none; background: var(--bg-subtle); }
</style>
