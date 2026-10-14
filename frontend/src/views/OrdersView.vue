<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { Lifecycle, Order, OrderDetail } from '@/api/types'
import DataTable, { type Column } from '@/components/DataTable.vue'
import DetailPanel from '@/components/DetailPanel.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import { money, orderTone, title, when } from '@/format'

const orders = ref<Order[]>([])
const lifecycle = ref<Lifecycle | null>(null)
const filter = ref<string | undefined>()
const selected = ref<OrderDetail | null>(null)
const open = ref(false)
const busy = ref('')
const error = ref('')

async function load() {
  const [o, l] = await Promise.all([api.orders(filter.value), lifecycle.value ? Promise.resolve(lifecycle.value) : api.lifecycle()])
  orders.value = o.items; lifecycle.value = l
  if (!selected.value && o.items[0] && window.matchMedia('(min-width: 1200px)').matches) await pick(o.items[0])
}
async function pick(o: Order) { selected.value = await api.order(o.id); open.value = true; error.value = '' }
async function move(to: string) {
  if (!selected.value) return
  busy.value = to; error.value = ''
  try { await api.transition(selected.value.id, to); selected.value = await api.order(selected.value.id); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : 'Transition failed.' }
  finally { busy.value = '' }
}
onMounted(load)

const next = computed(() => (lifecycle.value?.transitions ?? []).filter(([from]) => from === selected.value?.status).map(([, to]) => to))
const label = (code: string) => lifecycle.value?.statuses.find((s) => s.code === code)?.label ?? title(code)
const tabs = computed(() => [{ code: undefined, label: 'All' }, ...(lifecycle.value?.statuses ?? []).map((s) => ({ code: s.code, label: s.label }))])
const columns: Column[] = [
  { key: 'number', label: 'Order', primary: true }, { key: 'status', label: 'Status' }, { key: 'total', label: 'Total', align: 'right' },
  { key: 'payment', label: 'Payment', hideOnMobile: true }, { key: 'placed_at', label: 'Placed' },
]
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Orders</h1><p class="muted">GET /orders · lifecycle: pending → paid → shipped → delivered</p></div>
      <div class="actions"><UiButton icon="settings">Order lifecycle</UiButton><UiButton variant="primary" icon="plus">New order</UiButton></div>
    </div>

    <div class="tabs" role="tablist" aria-label="Filter by status">
      <button v-for="t in tabs" :key="t.label" role="tab" :aria-selected="filter === t.code" class="tab" :class="{ on: filter === t.code }"
              @click="filter = t.code; selected = null; load()">{{ t.label }}</button>
    </div>

    <div class="split">
      <UiCard title="All orders" :caption="`${orders.length} shown · newest first`">
        <DataTable :columns="columns" :rows="orders" row-key="id" :selected="selected?.id" clickable empty="No orders with this status." @select="pick">
          <template #cell-number="{ row }"><strong>#{{ row.number }}</strong><div class="caption">{{ row.customer.name }}</div></template>
          <template #cell-status="{ row }"><UiBadge :tone="orderTone[row.status]">{{ label(row.status) }}</UiBadge></template>
          <template #cell-total="{ row }">{{ money(row.total, row.currency) }}</template>
          <template #cell-payment="{ row }">{{ Number(row.amount_refunded) > 0 ? 'Refunded' : Number(row.amount_paid) > 0 ? 'Paid' : 'Awaiting payment' }}</template>
          <template #cell-placed_at="{ row }">{{ when(row.placed_at) }}</template>
        </DataTable>
      </UiCard>

      <DetailPanel :open="open && !!selected" label="Order detail" @close="open = false">
        <template v-if="selected">
          <header class="d-head">
            <div class="d-title"><h2>Order #{{ selected.number }}</h2><UiBadge :tone="orderTone[selected.status]">{{ label(selected.status) }}</UiBadge></div>
            <p class="caption">{{ selected.customer.name }} · {{ selected.customer.email }} · {{ when(selected.placed_at) }}</p>
          </header>
          <section class="d-sec">
            <div class="sec-head"><span class="label">Items</span><span class="caption">GET /orders/{id}</span></div>
            <div v-for="i in selected.items" :key="i.id" class="line">
              <div><p>{{ i.name }}</p><p class="caption">{{ i.sku }} · {{ i.qty }} × {{ money(i.unit_price) }}</p></div><span>{{ money(i.line_total) }}</span>
            </div>
            <div class="kv"><span>Total</span><strong>{{ money(selected.total, selected.currency) }}</strong></div>
          </section>
          <section class="d-sec">
            <div class="sec-head"><span class="label">Next status</span><span class="caption">POST /orders/{id}/transitions</span></div>
            <div v-if="next.length" class="btns">
              <UiButton v-for="to in next" :key="to" :variant="to === 'cancelled' ? 'secondary' : 'primary'" :loading="busy === to" @click="move(to)">
                {{ to === 'cancelled' ? 'Cancel order' : `Mark as ${label(to).toLowerCase()}` }}
              </UiButton>
            </div>
            <p v-else class="caption">No further transitions: this is a final status.</p>
            <p v-if="error" class="err" role="alert">{{ error }}</p>
          </section>
          <section class="d-sec">
            <div class="sec-head"><span class="label">Payment</span><span class="caption">POST /orders/{id}/payments</span></div>
            <div class="kv"><span>Paid</span><span>{{ money(selected.amount_paid) }}</span></div>
            <div class="kv"><span>Refunded</span><span>{{ money(selected.amount_refunded) }}</span></div>
            <div class="btns"><UiButton>Refund</UiButton><UiButton>Start return</UiButton></div>
          </section>
          <section class="d-sec">
            <div class="sec-head"><span class="label">Status history</span><span class="caption">append-only</span></div>
            <ol class="history">
              <li v-for="(h, i) in selected.history" :key="i" :class="{ latest: i === 0 }"><span>{{ label(h.to_status) }}</span><span class="caption">{{ when(h.created_at) }} · {{ h.note ?? 'staff' }}</span></li>
            </ol>
          </section>
        </template>
      </DetailPanel>
    </div>
  </div>
</template>

<style scoped>
.tabs { display: flex; gap: 8px; overflow-x: auto; scrollbar-width: none; margin: 0 -4px; padding: 0 4px; }
.tab { border: 1px solid transparent; background: none; padding: 8px 14px; border-radius: var(--radius-md); color: var(--text-secondary); font-weight: 500; cursor: pointer; white-space: nowrap; min-height: 36px; }
.tab.on { background: var(--bg-surface); border-color: var(--border-strong); color: var(--text-primary); }
.d-head { padding: 20px 24px; padding-right: 64px; }
.d-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 6px; }
.d-sec { padding: 16px 24px; border-top: 1px solid var(--border-default); display: flex; flex-direction: column; gap: 10px; }
.sec-head { display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.line { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; }
.btns { display: flex; gap: 8px; flex-wrap: wrap; }
.btns > * { flex: 1 1 140px; }
.err { color: var(--danger); font-size: 13px; }
.history { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.history li { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; padding-left: 16px; position: relative; }
.history li::before { content: ''; position: absolute; left: 0; top: 6px; width: 8px; height: 8px; border-radius: 50%; background: var(--border-strong); }
.history li.latest::before { background: var(--brand-primary); }
@media (max-width: 767px) { .d-head, .d-sec { padding-left: 16px; padding-right: 16px; } .d-head { padding-right: 64px; } }
</style>
