<script setup lang="ts">
import { ref } from 'vue'
import { api } from '@/api/client'
import { can } from '@/api/me'
import { useBusy, useList } from '@/composables'
import { dateTime, money, when } from '@/format'
import DataTable from '@/components/DataTable.vue'
import DetailPanel from '@/components/DetailPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiPager from '@/components/UiPager.vue'

const list = useList((q) => api.get('/customers', q), { q: '' })
const sel = ref<any>(null)
const { busy, run } = useBusy()
async function open(c: any) { sel.value = await api.get(`/customers/${c.id}`) }
async function toggle() {
  const c = sel.value.customer
  await run('active', async () => {
    await api.patch(`/customers/${c.id}`, { is_active: !c.is_active })
    await open(c); await list.reload()
  }, c.is_active ? 'Account disabled and signed out.' : 'Account enabled.')
}
</script>

<template>
  <div class="page">
    <PageHeader title="Customers" caption="People with an account in your shop or app.">
      <RouterLink to="/settings/customers"><UiButton icon="settings">Sign-up settings</UiButton></RouterLink>
    </PageHeader>
    <div class="split">
      <UiCard>
        <div class="toolbar" style="padding-top: 16px"><input v-model="list.filters.q" class="search-input" type="search" placeholder="Search name, email, phone…" aria-label="Search customers" /></div>
        <LoadState :loading="list.loading.value" :error="list.error.value" :ready="list.items.value.length > 0" @retry="list.reload">
          <DataTable :columns="[{ key: 'name', label: 'Customer', primary: true }, { key: 'orders', label: 'Orders', align: 'right' }, { key: 'joined', label: 'Joined', hideOnMobile: true }, { key: 'state', label: '' }]"
                     :rows="list.items.value" row-key="id" clickable :selected="sel?.customer.id" empty="No customers yet." @select="open">
            <template #cell-name="{ row }"><strong>{{ row.name || row.email }}</strong><span class="caption">{{ row.email }}</span></template>
            <template #cell-orders="{ row }">{{ row.order_count ?? 0 }}</template>
            <template #cell-joined="{ row }">{{ when(row.created_at) }}</template>
            <template #cell-state="{ row }"><UiBadge v-if="!row.is_active" tone="neutral">Disabled</UiBadge><UiBadge v-else-if="!row.email_verified" tone="warning">Unverified</UiBadge></template>
          </DataTable>
          <UiPager v-model="list.offset.value" :total="list.total.value" :limit="list.limit" :count="list.items.value.length" />
        </LoadState>
      </UiCard>
      <DetailPanel :open="!!sel" label="Customer" @close="sel = null">
        <EmptyState v-if="!sel" icon="user" title="Pick a customer" body="Their details and latest orders show here." />
        <div v-else class="detail">
          <div><h2>{{ sel.customer.name || sel.customer.email }}</h2><p class="caption">{{ sel.customer.email }}</p></div>
          <div class="kv"><span>Phone</span><span>{{ sel.customer.phone || '—' }}</span></div>
          <div class="kv"><span>Email confirmed</span><span>{{ sel.customer.email_verified ? 'Yes' : 'No' }}</span></div>
          <div class="kv"><span>Marketing emails</span><span>{{ sel.customer.marketing_opt_in ? 'Yes' : 'No' }}</span></div>
          <div class="kv"><span>Joined</span><span>{{ dateTime(sel.customer.created_at) }}</span></div>
          <div class="kv"><span>Last sign-in</span><span>{{ dateTime(sel.customer.last_login_at) }}</span></div>
          <p class="section-label">Latest orders</p>
          <p v-if="!sel.recent_orders.length" class="muted">No orders yet.</p>
          <RouterLink v-for="o in sel.recent_orders" :key="o.id" :to="`/orders/${o.id}`" class="kv order"><span>#{{ o.number }} · {{ when(o.placed_at) }}</span><span><StatusBadge :status="o.status" :label="o.status_label" /> {{ money(o.total, o.currency) }}</span></RouterLink>
          <UiButton v-if="can('customers', 'update')" :variant="sel.customer.is_active ? 'secondary' : 'primary'" :loading="busy === 'active'" @click="toggle">
            {{ sel.customer.is_active ? 'Disable account' : 'Enable account' }}
          </UiButton>
        </div>
      </DetailPanel>
    </div>
  </div>
</template>

<style scoped>
.detail { padding: 24px; display: grid; gap: 10px; }
.order { color: inherit; }
.order:hover { text-decoration: none; background: var(--bg-subtle); }
</style>
