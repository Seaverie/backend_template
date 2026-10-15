<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/api/client'
import { can } from '@/api/me'
import { num, opt, useBusy, useList } from '@/composables'
import { dateTime, money, title, when } from '@/format'
import DataTable from '@/components/DataTable.vue'
import DetailPanel from '@/components/DetailPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiPager from '@/components/UiPager.vue'
import UiSelect from '@/components/UiSelect.vue'

const route = useRoute()
const list = useList((q) => api.get('/returns', q), { status: '' })
const sel = ref<any>(null)
const order = ref<any>(null) // the selected return's order (for refunds)
const { busy, run } = useBusy()

async function open(r: any) {
  sel.value = await api.get(`/returns/${r.id}`)
  order.value = await api.get(`/orders/${r.order_id}`).catch(() => null)
}
async function refresh(r: any) { sel.value = r; await list.reload(); order.value = await api.get(`/orders/${r.order_id}`).catch(() => null) }

const note = reactive({ open: false, action: '' as 'approve' | 'reject' | 'close' | '', text: '' })
async function doNote() {
  const ok = await run('note', async () => refresh(await api.post(`/returns/${sel.value.id}/${note.action}`, { note: opt(note.text) })), `Return ${note.action === 'approve' ? 'approved' : note.action === 'reject' ? 'rejected' : 'closed'}.`)
  if (ok) note.open = false
}

const recv = reactive({ open: false, items: [] as { id: string; label: string; max: number; qty: string; restock: boolean }[] })
function startReceive() {
  recv.items = sel.value.items.map((i: any) => ({ id: i.id, label: `${i.name} (${i.sku})`, max: i.quantity - i.received_quantity, qty: String(i.quantity - i.received_quantity), restock: true }))
  recv.open = true
}
async function doReceive() {
  const items = recv.items.filter((i) => (num(i.qty) ?? 0) > 0).map((i) => ({ rma_item_id: i.id, quantity: num(i.qty), restock: i.restock }))
  const ok = await run('recv', async () => refresh(await api.post(`/returns/${sel.value.id}/receive`, { items })), 'Goods received.')
  if (ok) recv.open = false
}

const charges = computed(() => (order.value?.payments ?? []).filter((p: any) => p.kind === 'charge' && p.status === 'succeeded'))
const refund = reactive({ open: false, amount: '', payment: '', reference: '' })
function startRefund() { Object.assign(refund, { open: true, amount: String(sel.value.refundable), payment: charges.value[0]?.id ?? '', reference: '' }) }
async function doRefund() {
  const ok = await run('refund', async () => refresh(await api.post(`/returns/${sel.value.id}/refund`, { amount: num(refund.amount), payment_id: refund.payment || null, reference: opt(refund.reference) })), 'Refund recorded.')
  if (ok) refund.open = false
}

// New return
const create = reactive({ open: false, number: '', order: null as any, reason: '', note: '', qty: {} as Record<string, string> })
async function findOrder() {
  await run('find', async () => {
    const r = await api.get('/orders', { q: create.number.trim().replace(/^#/, ''), limit: 5 })
    const o = r.items.find((x: any) => String(x.number) === create.number.trim().replace(/^#/, '')) ?? r.items[0]
    if (!o) throw new Error('No order with that number.')
    create.order = o; create.qty = Object.fromEntries(o.items.map((i: any) => [i.id, '0']))
  })
}
async function doCreate() {
  const items = Object.entries(create.qty).filter(([, q]) => (num(q) ?? 0) > 0).map(([id, q]) => ({ order_item_id: id, quantity: num(q) }))
  const ok = await run('create', async () => {
    const r = await api.post('/returns', { order_id: create.order.id, reason: opt(create.reason), note: opt(create.note), items })
    await list.reload(); await open(r)
  }, 'Return created.')
  if (ok) create.open = false
}
onMounted(async () => {
  const o = route.query.order
  if (typeof o === 'string') {
    const full = await api.get(`/orders/${o}`).catch(() => null)
    if (full) Object.assign(create, { open: true, number: String(full.number), order: full, reason: '', note: '', qty: Object.fromEntries(full.items.map((i: any) => [i.id, '0'])) })
  }
})
</script>

<template>
  <div class="page">
    <PageHeader title="Returns" caption="Requests to send goods back, restocking and refunds.">
      <UiButton v-if="can('rma', 'create')" icon="plus" variant="primary" @click="Object.assign(create, { open: true, number: '', order: null, reason: '', note: '', qty: {} })">New return</UiButton>
    </PageHeader>
    <div class="split">
      <UiCard>
        <div class="toolbar" style="padding-top: 16px">
          <select v-model="list.filters.status" class="search-input" aria-label="Status">
            <option value="">All returns</option>
            <option v-for="s in ['requested', 'approved', 'received', 'refunded', 'rejected', 'closed']" :key="s" :value="s">{{ title(s) }}</option>
          </select>
        </div>
        <LoadState :loading="list.loading.value" :error="list.error.value" :ready="list.items.value.length > 0" @retry="list.reload">
          <DataTable :columns="[{ key: 'number', label: 'Return', primary: true }, { key: 'order', label: 'Order' }, { key: 'status', label: 'Status' }, { key: 'refund', label: 'Refunded', align: 'right' }]"
                     :rows="list.items.value" row-key="id" clickable :selected="sel?.id" empty="No returns." @select="open">
            <template #cell-number="{ row }"><strong>RMA-{{ row.number }}</strong><span class="caption">{{ when(row.created_at) }}</span></template>
            <template #cell-order="{ row }">#{{ row.order_number }}</template>
            <template #cell-status="{ row }"><StatusBadge :status="row.status" /></template>
            <template #cell-refund="{ row }">{{ money(row.refund_total, order?.currency) }}</template>
          </DataTable>
          <UiPager v-model="list.offset.value" :total="list.total.value" :limit="list.limit" :count="list.items.value.length" />
        </LoadState>
      </UiCard>
      <DetailPanel :open="!!sel" label="Return details" @close="sel = null">
        <EmptyState v-if="!sel" icon="undo" title="Pick a return" body="Its items, status and refunds show here." />
        <div v-else class="detail">
          <div><h2>RMA-{{ sel.number }}</h2><p class="caption">For <RouterLink :to="`/orders/${sel.order_id}`">order #{{ sel.order_number }}</RouterLink> · {{ dateTime(sel.created_at) }}</p></div>
          <StatusBadge :status="sel.status" />
          <p v-if="sel.reason"><strong>Reason:</strong> {{ sel.reason }}</p>
          <p v-if="sel.note" class="note">{{ sel.note }}</p>
          <div>
            <p class="section-label">Items</p>
            <div v-for="i in sel.items" :key="i.id" class="kv"><span>{{ i.name }} × {{ i.quantity }}</span><span>{{ i.received_quantity }} received<template v-if="i.restocked_quantity"> · {{ i.restocked_quantity }} restocked</template></span></div>
          </div>
          <div class="kv"><span>Refunded</span><strong>{{ money(sel.refund_total, order?.currency) }}</strong></div>
          <div class="kv"><span>Still refundable</span><span>{{ money(sel.refundable, order?.currency) }}</span></div>
          <div v-if="can('rma', 'update')" class="acts">
            <template v-if="sel.status === 'requested'">
              <UiButton variant="primary" @click="Object.assign(note, { open: true, action: 'approve', text: '' })">Approve</UiButton>
              <UiButton @click="Object.assign(note, { open: true, action: 'reject', text: '' })">Reject</UiButton>
            </template>
            <UiButton v-if="['approved', 'received'].includes(sel.status) && sel.items.some((i: any) => i.received_quantity < i.quantity)" icon="inbox" @click="startReceive">Receive goods</UiButton>
            <UiButton v-if="['approved', 'received', 'refunded'].includes(sel.status) && Number(sel.refundable) > 0" icon="undo" @click="startRefund">Refund</UiButton>
            <UiButton v-if="!['closed', 'rejected'].includes(sel.status)" variant="ghost" @click="Object.assign(note, { open: true, action: 'close', text: '' })">Close</UiButton>
          </div>
        </div>
      </DetailPanel>
    </div>

    <UiModal :open="note.open" :title="title(note.action) + ' return'" :submit-label="title(note.action)" :busy="busy === 'note'" :danger="note.action === 'reject'" @close="note.open = false" @submit="doNote">
      <UiInput v-model="note.text" label="Note (optional)" multiline :rows="2" />
    </UiModal>

    <UiModal :open="recv.open" title="Receive returned goods" submit-label="Receive" :busy="busy === 'recv'" @close="recv.open = false" @submit="doReceive">
      <div v-for="i in recv.items" :key="i.id" class="recv">
        <div class="grow"><strong>{{ i.label }}</strong><p class="caption">Up to {{ i.max }}</p></div>
        <input v-model="i.qty" class="qty" inputmode="numeric" :aria-label="`Quantity received of ${i.label}`" />
        <UiCheck v-model="i.restock" label="Back to stock" />
      </div>
      <p class="caption">Untick “Back to stock” for damaged goods.</p>
    </UiModal>

    <UiModal :open="refund.open" title="Refund this return" submit-label="Refund" :busy="busy === 'refund'" @close="refund.open = false" @submit="doRefund">
      <UiInput v-model="refund.amount" label="Amount" inputmode="decimal" />
      <UiSelect v-model="refund.payment" label="Send back through" :options="[...charges.map((c: any) => ({ value: c.id, label: `${title(c.provider)} payment of ${money(c.amount, c.currency)}` })), { value: '', label: 'Record only (refunded outside the system)' }]" />
      <UiInput v-model="refund.reference" label="Reference" />
    </UiModal>

    <UiModal :open="create.open" title="New return" wide submit-label="Create return" :busy="busy === 'create'" :submit-disabled="!create.order" @close="create.open = false" @submit="doCreate">
      <div class="find"><UiInput v-model="create.number" label="Order number" placeholder="1042" inputmode="numeric" /><UiButton :loading="busy === 'find'" @click="findOrder">Find</UiButton></div>
      <template v-if="create.order">
        <p class="caption">Order #{{ create.order.number }} · {{ create.order.customer_name || 'no name' }} · {{ money(create.order.total, create.order.currency) }}</p>
        <div v-for="i in create.order.items" :key="i.id" class="recv">
          <div class="grow"><strong>{{ i.name }}</strong><p class="caption">Bought {{ i.quantity }} · {{ money(i.unit_price, create.order.currency) }} each</p></div>
          <input v-model="create.qty[i.id]" class="qty" inputmode="numeric" :aria-label="`Quantity to return of ${i.name}`" />
        </div>
        <UiInput v-model="create.reason" label="Reason" placeholder="Wrong size, damaged…" />
        <UiInput v-model="create.note" label="Note" multiline :rows="2" />
      </template>
    </UiModal>
  </div>
</template>

<style scoped>
.detail { padding: 24px; display: grid; gap: 14px; }
.acts { display: flex; gap: 8px; flex-wrap: wrap; }
.recv { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.grow { flex: 1 1 200px; min-width: 0; }
.qty { width: 72px; padding: 8px; border: 1px solid var(--border-strong); border-radius: var(--radius-sm); text-align: right; }
.find { display: flex; gap: 8px; align-items: flex-end; }
.find > :first-child { flex: 1; }
</style>
