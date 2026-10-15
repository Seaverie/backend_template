<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { can, currency } from '@/api/me'
import { num, opt, useBusy, useList, useLoader } from '@/composables'
import { money, title, when } from '@/format'
import DataTable from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiPager from '@/components/UiPager.vue'
import UiSelect from '@/components/UiSelect.vue'
import UiTabs from '@/components/UiTabs.vue'
import VariantPicker, { type PickedVariant } from '@/components/VariantPicker.vue'

const router = useRouter()
const tab = ref('orders')
const suppliers = ref<any[]>([])
const warehouses = ref<any[]>([])
const base = useLoader(async () => {
  const [s, w] = await Promise.all([api.get('/suppliers'), api.get('/inventory/warehouses').catch(() => ({ items: [] }))])
  suppliers.value = s.items; warehouses.value = w.items
})
const pos = useList((q) => api.get('/purchase-orders', q), { status: '', supplier_id: '' })
const { busy, run } = useBusy()

const sup = reactive({ open: false, id: '', name: '', email: '', phone: '', notes: '', is_active: true })
function editSup(s?: any) { Object.assign(sup, { open: true, id: s?.id ?? '', name: s?.name ?? '', email: s?.email ?? '', phone: s?.phone ?? '', notes: s?.notes ?? '', is_active: s?.is_active ?? true }) }
async function saveSup() {
  const body = { name: sup.name.trim(), email: opt(sup.email), phone: opt(sup.phone), notes: opt(sup.notes), ...(sup.id ? { is_active: sup.is_active } : {}) }
  const ok = await run('sup', () => (sup.id ? api.patch(`/suppliers/${sup.id}`, body) : api.post('/suppliers', body)), 'Supplier saved.')
  if (ok) { sup.open = false; base.reload() }
}

const po = reactive({ open: false, supplier: '', warehouse: '', expected: '', notes: '', lines: [] as { v: PickedVariant; qty: string; cost: string }[] })
function startPo() { Object.assign(po, { open: true, supplier: suppliers.value.find((s) => s.is_active)?.id ?? '', warehouse: '', expected: '', notes: '', lines: [] }) }
function addLine(v: PickedVariant) { if (!po.lines.some((l) => l.v.id === v.id)) po.lines.push({ v, qty: '1', cost: String(v.cost ?? '') }) }
const poTotal = computed(() => po.lines.reduce((s, l) => s + (num(l.qty) ?? 0) * (num(l.cost) ?? 0), 0))
async function savePo() {
  let id = ''
  const ok = await run('po', async () => {
    const r = await api.post('/purchase-orders', { supplier_id: po.supplier, warehouse_id: po.warehouse || null, expected_at: po.expected || null, notes: opt(po.notes),
      lines: po.lines.map((l) => ({ variant_id: l.v.id, quantity: num(l.qty) ?? 0, unit_cost: num(l.cost) ?? 0 })) })
    id = r.id
  }, 'Purchase order drafted.')
  if (ok) router.push(`/purchasing/${id}`)
}
</script>

<template>
  <div class="page">
    <PageHeader title="Purchasing" caption="Order stock from suppliers and receive it into a warehouse.">
      <UiButton v-if="tab === 'suppliers' && can('purchasing', 'create')" icon="plus" variant="primary" @click="editSup()">New supplier</UiButton>
      <UiButton v-else-if="can('purchasing', 'create')" icon="plus" variant="primary" :disabled="!suppliers.length" :title="suppliers.length ? '' : 'Add a supplier first'" @click="startPo">New purchase order</UiButton>
    </PageHeader>
    <UiTabs v-model="tab" :tabs="[{ key: 'orders', label: 'Purchase orders' }, { key: 'suppliers', label: 'Suppliers' }]" />
    <UiCard v-if="tab === 'orders'">
      <div class="toolbar" style="padding-top: 16px">
        <select v-model="pos.filters.status" class="search-input" aria-label="Status"><option value="">Any status</option><option v-for="s in ['draft', 'ordered', 'partially_received', 'received', 'cancelled']" :key="s" :value="s">{{ title(s) }}</option></select>
        <select v-model="pos.filters.supplier_id" class="search-input" aria-label="Supplier"><option value="">All suppliers</option><option v-for="s in suppliers" :key="s.id" :value="s.id">{{ s.name }}</option></select>
      </div>
      <LoadState :loading="pos.loading.value" :error="pos.error.value" :ready="pos.items.value.length > 0" @retry="pos.reload">
        <DataTable :columns="[{ key: 'number', label: 'PO', primary: true }, { key: 'supplier_name', label: 'Supplier' }, { key: 'status', label: 'Status' }, { key: 'total', label: 'Total', align: 'right' }]"
                   :rows="pos.items.value" row-key="id" clickable empty="No purchase orders yet." @select="(p) => router.push(`/purchasing/${p.id}`)">
          <template #cell-number="{ row }"><strong>PO-{{ row.number }}</strong><span class="caption">{{ when(row.created_at) }} · to {{ row.warehouse_code }}</span></template>
          <template #cell-status="{ row }"><StatusBadge :status="row.status" /></template>
          <template #cell-total="{ row }">{{ money(row.total, row.currency) }}</template>
        </DataTable>
        <UiPager v-model="pos.offset.value" :total="pos.total.value" :limit="pos.limit" :count="pos.items.value.length" />
      </LoadState>
    </UiCard>
    <UiCard v-else>
      <LoadState :loading="base.loading.value" :error="base.error.value" :ready="suppliers.length > 0" @retry="base.reload">
        <DataTable :columns="[{ key: 'name', label: 'Supplier', primary: true }, { key: 'contact', label: 'Contact' }, { key: 'state', label: '' }]"
                   :rows="suppliers" row-key="id" :clickable="can('purchasing', 'update')" empty="No suppliers yet." @select="editSup">
          <template #cell-name="{ row }"><strong>{{ row.name }}</strong><span v-if="row.notes" class="caption">{{ row.notes }}</span></template>
          <template #cell-contact="{ row }"><span>{{ row.email || '—' }}</span><span class="caption">{{ row.phone }}</span></template>
          <template #cell-state="{ row }"><UiBadge v-if="!row.is_active">Inactive</UiBadge></template>
        </DataTable>
      </LoadState>
    </UiCard>

    <UiModal :open="sup.open" :title="sup.id ? 'Edit supplier' : 'New supplier'" submit-label="Save" :busy="busy === 'sup'" :submit-disabled="!sup.name.trim()" @close="sup.open = false" @submit="saveSup">
      <UiInput v-model="sup.name" label="Name" required />
      <div class="grid2"><UiInput v-model="sup.email" label="Email" type="email" /><UiInput v-model="sup.phone" label="Phone" /></div>
      <UiInput v-model="sup.notes" label="Notes" multiline :rows="2" />
      <UiCheck v-if="sup.id" v-model="sup.is_active" label="Active" />
    </UiModal>

    <UiModal :open="po.open" title="New purchase order" wide submit-label="Save draft" :busy="busy === 'po'" :submit-disabled="!po.supplier || !po.lines.length" @close="po.open = false" @submit="savePo">
      <div class="grid3">
        <UiSelect v-model="po.supplier" label="Supplier" :options="suppliers.filter((s) => s.is_active).map((s) => ({ value: s.id, label: s.name }))" />
        <UiSelect v-model="po.warehouse" label="Deliver to" :options="[{ value: '', label: 'Default warehouse' }, ...warehouses.map((w) => ({ value: w.id, label: w.name }))]" />
        <UiInput v-model="po.expected" label="Expected" type="date" />
      </div>
      <VariantPicker label="Add an item" show-cost :currency="currency()" @pick="addLine" />
      <table v-if="po.lines.length" class="lines">
        <thead><tr><th>Item</th><th class="r">Qty</th><th class="r">Unit cost</th><th class="r">Total</th><th /></tr></thead>
        <tbody>
          <tr v-for="(l, i) in po.lines" :key="l.v.id">
            <td><strong>{{ l.v.label }}</strong><div class="caption mono">{{ l.v.sku }}</div></td>
            <td class="r"><input v-model="l.qty" class="qty" inputmode="numeric" aria-label="Quantity" /></td>
            <td class="r"><input v-model="l.cost" class="qty wide" inputmode="decimal" aria-label="Unit cost" /></td>
            <td class="r">{{ money((num(l.qty) ?? 0) * (num(l.cost) ?? 0), currency()) }}</td>
            <td><UiButton variant="ghost" icon="trash" aria-label="Remove" @click="po.lines.splice(i, 1)" /></td>
          </tr>
        </tbody>
        <tfoot><tr><td colspan="3" class="r muted">Total</td><td class="r"><strong>{{ money(poTotal, currency()) }}</strong></td><td /></tr></tfoot>
      </table>
      <UiInput v-model="po.notes" label="Notes" multiline :rows="2" />
    </UiModal>
  </div>
</template>

<style scoped>
.lines { width: 100%; border-collapse: collapse; font-size: 13px; }
.lines th { text-align: left; font-weight: 500; color: var(--text-secondary); padding: 6px 4px; border-bottom: 1px solid var(--border-default); }
.lines td { padding: 8px 4px; border-bottom: 1px solid var(--border-default); }
.lines tfoot td { border: 0; }
.r { text-align: right !important; }
.qty { width: 64px; padding: 6px 8px; border: 1px solid var(--border-strong); border-radius: var(--radius-sm); text-align: right; }
.qty.wide { width: 96px; }
</style>
