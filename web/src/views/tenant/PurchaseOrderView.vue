<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { can } from '@/api/me'
import { num, opt, useBusy, useLoader } from '@/composables'
import { dateTime, money } from '@/format'
import { ask } from '@/ui'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiSelect from '@/components/UiSelect.vue'

const props = defineProps<{ id: string }>()
const router = useRouter()
const po = ref<any>(null)
const bins = ref<{ value: string; label: string }[]>([])
const { loading, error, reload } = useLoader(async () => {
  po.value = await api.get(`/purchase-orders/${props.id}`)
  const w = await api.get('/inventory/warehouses').catch(() => ({ items: [] }))
  bins.value = w.items.filter((x: any) => x.id === po.value.warehouse_id).flatMap((x: any) => x.bins.filter((b: any) => b.is_active).map((b: any) => ({ value: b.id, label: `${x.code} / ${b.code}${b.name ? ' – ' + b.name : ''}` })))
})
const { busy, run } = useBusy()
const open = computed(() => po.value && ['ordered', 'partially_received'].includes(po.value.status))

async function act(action: 'submit' | 'cancel') {
  if (action === 'cancel' && !await ask({ title: `Cancel PO-${po.value.number}?`, body: 'Goods already received stay in stock.', confirmLabel: 'Cancel order', danger: true })) return
  await run(action, async () => { po.value = await api.post(`/purchase-orders/${props.id}/${action}`) }, action === 'submit' ? 'Marked as ordered.' : 'Purchase order cancelled.')
}
async function remove() {
  if (!await ask({ title: `Delete draft PO-${po.value.number}?`, confirmLabel: 'Delete', danger: true })) return
  if (await run('del', () => api.del(`/purchase-orders/${props.id}`), 'Draft deleted.')) router.push('/purchasing')
}
const recv = reactive({ open: false, bin: '', note: '', qty: {} as Record<string, string> })
function startReceive() {
  recv.open = true; recv.bin = bins.value[0]?.value ?? ''; recv.note = ''
  recv.qty = Object.fromEntries(po.value.lines.map((l: any) => [l.id, String(Math.max(0, l.quantity - l.received_quantity))]))
}
async function doReceive() {
  const lines = Object.entries(recv.qty).filter(([, q]) => (num(q) ?? 0) > 0).map(([id, q]) => ({ line_id: id, quantity: num(q) }))
  const ok = await run('recv', async () => { po.value = await api.post(`/purchase-orders/${props.id}/receive`, { lines, bin_id: recv.bin || null, note: opt(recv.note) }) }, 'Goods received into stock.')
  if (ok) recv.open = false
}
const notes = reactive({ open: false, text: '', expected: '' })
async function saveNotes() {
  const ok = await run('notes', async () => { po.value = await api.patch(`/purchase-orders/${props.id}`, { notes: notes.text, expected_at: notes.expected || null }) }, 'Saved.')
  if (ok) notes.open = false
}
</script>

<template>
  <div class="page">
    <LoadState :loading="loading" :error="error" :ready="!!po" @retry="reload">
      <template v-if="po">
        <PageHeader :title="`PO-${po.number}`" back="/purchasing">
          <template #sub><div class="chips sub"><StatusBadge :status="po.status" /><span class="caption">{{ po.supplier_name }} · to {{ po.warehouse_code }}</span></div></template>
          <template v-if="po.status === 'draft'">
            <UiButton v-if="can('purchasing', 'delete')" variant="ghost" icon="trash" @click="remove">Delete</UiButton>
            <UiButton v-if="can('purchasing', 'update')" variant="primary" :loading="busy === 'submit'" @click="act('submit')">Mark as ordered</UiButton>
          </template>
          <template v-if="open && can('purchasing', 'update')">
            <UiButton variant="ghost" @click="act('cancel')">Cancel</UiButton>
            <UiButton variant="primary" icon="inbox" @click="startReceive">Receive goods</UiButton>
          </template>
        </PageHeader>
        <div class="split">
          <UiCard title="Lines">
            <table class="items">
              <thead><tr><th>Item</th><th class="r">Ordered</th><th class="r">Received</th><th class="r">Unit cost</th><th class="r">Total</th></tr></thead>
              <tbody>
                <tr v-for="l in po.lines" :key="l.id">
                  <td><strong>{{ l.product_name }}</strong><div class="caption mono">{{ l.sku }}</div></td>
                  <td class="r">{{ l.quantity }}</td><td class="r">{{ l.received_quantity }}</td>
                  <td class="r">{{ money(l.unit_cost, po.currency) }}</td><td class="r">{{ money(l.line_total, po.currency) }}</td>
                </tr>
              </tbody>
              <tfoot><tr><td colspan="4" class="r muted">Total</td><td class="r"><strong>{{ money(po.total, po.currency) }}</strong></td></tr></tfoot>
            </table>
          </UiCard>
          <UiCard title="Details">
            <template #actions><UiButton v-if="can('purchasing', 'update') && po.status !== 'cancelled'" variant="ghost" icon="edit" @click="Object.assign(notes, { open: true, text: po.notes ?? '', expected: po.expected_at ?? '' })">Edit</UiButton></template>
            <div class="card-body">
              <div class="kv"><span>Supplier</span><span>{{ po.supplier_name }}</span></div>
              <div class="kv"><span>Expected</span><span>{{ po.expected_at ?? '—' }}</span></div>
              <div class="kv"><span>Ordered</span><span>{{ dateTime(po.ordered_at) }}</span></div>
              <div class="kv"><span>Closed</span><span>{{ dateTime(po.closed_at) }}</span></div>
              <p v-if="po.notes" class="note">{{ po.notes }}</p>
            </div>
          </UiCard>
        </div>
      </template>
    </LoadState>

    <UiModal :open="recv.open" title="Receive goods" submit-label="Receive" :busy="busy === 'recv'" @close="recv.open = false" @submit="doReceive">
      <div v-for="l in po?.lines ?? []" :key="l.id" class="recv">
        <div class="grow"><strong>{{ l.product_name }}</strong><p class="caption">{{ l.received_quantity }} of {{ l.quantity }} received so far</p></div>
        <input v-model="recv.qty[l.id]" class="qty" inputmode="numeric" :aria-label="`Quantity received of ${l.sku}`" />
      </div>
      <UiSelect v-if="bins.length" v-model="recv.bin" label="Into bin" :options="bins" />
      <UiInput v-model="recv.note" label="Note" placeholder="Delivery note number" />
    </UiModal>
    <UiModal :open="notes.open" title="Edit purchase order" submit-label="Save" :busy="busy === 'notes'" @close="notes.open = false" @submit="saveNotes">
      <UiInput v-model="notes.expected" label="Expected" type="date" />
      <UiInput v-model="notes.text" label="Notes" multiline :rows="3" />
    </UiModal>
  </div>
</template>

<style scoped>
.sub { margin-top: 6px; align-items: center; }
.items { width: 100%; border-collapse: collapse; }
.items th { text-align: left; font-size: 12px; font-weight: 500; color: var(--text-secondary); background: var(--bg-subtle); padding: 10px 16px; }
.items td { padding: 12px 16px; border-top: 1px solid var(--border-default); }
.r { text-align: right !important; white-space: nowrap; }
.recv { display: flex; align-items: center; gap: 12px; }
.grow { flex: 1; min-width: 0; }
.qty { width: 72px; padding: 8px; border: 1px solid var(--border-strong); border-radius: var(--radius-sm); text-align: right; }
</style>
