<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { api } from '@/api/client'
import { can } from '@/api/me'
import { num, opt, useBusy, useList, useLoader } from '@/composables'
import { title, when } from '@/format'
import DataTable from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
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

const tab = ref('stock')
const warehouses = ref<any[]>([])
const whLoad = useLoader(async () => { warehouses.value = (await api.get('/inventory/warehouses')).items })
const bins = computed(() => warehouses.value.flatMap((w) => w.bins.filter((b: any) => b.is_active).map((b: any) => ({ value: b.id, label: `${w.code} / ${b.code}${b.name ? ' – ' + b.name : ''}`, warehouse: w }))))
const stock = useList((q) => api.get('/inventory/stock', q), { q: '', warehouse_id: '', low_only: false })
const moves = useList((q) => api.get('/inventory/movements', q), { variant_id: '', kind: '' })
watch(tab, (t) => { if (t === 'movements') moves.reload() })
const { busy, run } = useBusy()
const canEdit = computed(() => can('inventory', 'update') || can('inventory', 'create'))

// Adjust / count
const adj = reactive({ open: false, variant: null as any, bin: '', mode: 'change', qty: '', note: '' })
function startAdjust(row?: any) {
  Object.assign(adj, { open: true, variant: row ? { id: row.variant_id, label: `${row.product_name}${row.variant_name ? ' – ' + row.variant_name : ''}`, sku: row.sku, bins: row.bins } : null, bin: bins.value[0]?.value ?? '', mode: 'change', qty: '', note: '' })
}
async function saveAdjust() {
  const q = num(adj.qty)
  if (q === null) return
  const ok = await run('adj', () => api.post('/inventory/adjustments', { variant_id: adj.variant.id, bin_id: adj.bin || null, ...(adj.mode === 'set' ? { set_to: q } : { quantity: q }), note: opt(adj.note) }), 'Stock updated.')
  if (ok) { adj.open = false; stock.reload() }
}
// Transfer
const tr = reactive({ open: false, variant: null as any, from: '', to: '', qty: '', note: '' })
function startTransfer(row: any) {
  Object.assign(tr, { open: true, variant: { id: row.variant_id, label: row.product_name, sku: row.sku }, from: row.bins[0]?.bin_id ?? '', to: '', qty: '', note: '' })
}
async function saveTransfer() {
  const ok = await run('tr', () => api.post('/inventory/transfers', { variant_id: tr.variant.id, from_bin_id: tr.from, to_bin_id: tr.to, quantity: num(tr.qty), note: opt(tr.note) }), 'Stock moved.')
  if (ok) { tr.open = false; stock.reload() }
}

// Warehouses and bins
const wh = reactive({ open: false, id: '', code: '', name: '', address: '', is_default: false, is_active: true })
function editWh(w?: any) { Object.assign(wh, { open: true, id: w?.id ?? '', code: w?.code ?? '', name: w?.name ?? '', address: w?.address ?? '', is_default: w?.is_default ?? !warehouses.value.length, is_active: w?.is_active ?? true }) }
async function saveWh() {
  const ok = await run('wh', () => wh.id
    ? api.patch(`/inventory/warehouses/${wh.id}`, { name: wh.name, address: wh.address, is_default: wh.is_default || undefined, is_active: wh.is_active })
    : api.post('/inventory/warehouses', { code: wh.code.trim(), name: wh.name.trim(), address: opt(wh.address), is_default: wh.is_default }), 'Warehouse saved.')
  if (ok) { wh.open = false; whLoad.reload() }
}
const bin = reactive({ open: false, warehouse: null as any, id: '', code: '', name: '', is_active: true })
function editBin(w: any, b?: any) { Object.assign(bin, { open: true, warehouse: w, id: b?.id ?? '', code: b?.code ?? '', name: b?.name ?? '', is_active: b?.is_active ?? true }) }
async function saveBin() {
  const ok = await run('bin', () => bin.id
    ? api.patch(`/inventory/warehouses/${bin.warehouse.id}/bins/${bin.id}`, { name: bin.name, is_active: bin.is_active })
    : api.post(`/inventory/warehouses/${bin.warehouse.id}/bins`, { code: bin.code.trim(), name: opt(bin.name) }), 'Bin saved.')
  if (ok) { bin.open = false; whLoad.reload() }
}
function pickVariant(v: PickedVariant) { adj.variant = { id: v.id, label: v.label, sku: v.sku } }
function filterMoves(row: any) { moves.filters.variant_id = row.variant_id; tab.value = 'movements' }
</script>

<template>
  <div class="page">
    <PageHeader title="Inventory" caption="Stock on hand, reserved for orders, and where it is kept.">
      <UiButton v-if="canEdit && warehouses.length" icon="plus" variant="primary" @click="startAdjust()">Adjust stock</UiButton>
    </PageHeader>
    <UiTabs v-model="tab" :tabs="[{ key: 'stock', label: 'Stock' }, { key: 'movements', label: 'Movements' }, { key: 'warehouses', label: 'Warehouses' }]" />

    <UiCard v-if="tab === 'stock'">
      <EmptyState v-if="!whLoad.loading.value && !warehouses.length" icon="warehouse" title="Add a warehouse first" body="Stock is counted per warehouse and bin. Most businesses need just one.">
        <UiButton v-if="can('inventory', 'manage')" variant="primary" icon="plus" @click="tab = 'warehouses'; editWh()">Add warehouse</UiButton>
      </EmptyState>
      <template v-else>
        <div class="toolbar" style="padding-top: 16px">
          <input v-model="stock.filters.q" class="search-input" type="search" placeholder="Search product or SKU…" aria-label="Search stock" />
          <select v-model="stock.filters.warehouse_id" class="search-input narrow" aria-label="Warehouse"><option value="">All warehouses</option><option v-for="w in warehouses" :key="w.id" :value="w.id">{{ w.name }}</option></select>
          <UiCheck v-model="stock.filters.low_only" label="Running low only" />
        </div>
        <LoadState :loading="stock.loading.value" :error="stock.error.value" :ready="stock.items.value.length > 0" @retry="stock.reload">
          <DataTable :columns="[{ key: 'item', label: 'Item', primary: true }, { key: 'on_hand', label: 'On hand', align: 'right' }, { key: 'reserved', label: 'Reserved', align: 'right' }, { key: 'available', label: 'Available', align: 'right' }, { key: 'acts', label: '' }]"
                     :rows="stock.items.value" row-key="variant_id" empty="No tracked items match.">
            <template #cell-item="{ row }">
              <strong>{{ row.product_name }}<template v-if="row.variant_name"> – {{ row.variant_name }}</template></strong>
              <span class="caption"><span class="mono">{{ row.sku }}</span><template v-for="b in row.bins" :key="b.bin_id"> · {{ b.warehouse_code }}/{{ b.bin_code }}: {{ b.on_hand }}</template></span>
            </template>
            <template #cell-available="{ row }"><UiBadge v-if="row.is_low" tone="warning">{{ row.available }}</UiBadge><span v-else>{{ row.available }}</span></template>
            <template #cell-acts="{ row }">
              <div class="row-actions">
                <UiButton v-if="canEdit" variant="ghost" @click="startAdjust(row)">Adjust</UiButton>
                <UiButton v-if="canEdit && bins.length > 1 && row.on_hand > 0" variant="ghost" @click="startTransfer(row)">Move</UiButton>
                <UiButton variant="ghost" @click="filterMoves(row)">History</UiButton>
              </div>
            </template>
          </DataTable>
          <UiPager v-model="stock.offset.value" :total="stock.total.value" :limit="stock.limit" :count="stock.items.value.length" />
        </LoadState>
      </template>
    </UiCard>

    <UiCard v-else-if="tab === 'movements'">
      <div class="toolbar" style="padding-top: 16px">
        <select v-model="moves.filters.kind" class="search-input narrow" aria-label="Kind">
          <option value="">All kinds</option>
          <option v-for="k in ['adjustment', 'transfer', 'purchase', 'sale', 'return']" :key="k" :value="k">{{ title(k) }}</option>
        </select>
        <UiButton v-if="moves.filters.variant_id" variant="ghost" icon="close" @click="moves.filters.variant_id = ''">Show all items</UiButton>
      </div>
      <LoadState :loading="moves.loading.value" :error="moves.error.value" :ready="moves.items.value.length > 0" @retry="moves.reload">
        <DataTable :columns="[{ key: 'sku', label: 'Item', primary: true }, { key: 'kind', label: 'Kind' }, { key: 'where', label: 'Where' }, { key: 'quantity', label: 'Change', align: 'right' }]"
                   :rows="moves.items.value" row-key="id" empty="No stock movements.">
          <template #cell-sku="{ row }"><strong class="mono">{{ row.sku }}</strong><span class="caption">{{ when(row.created_at) }}<template v-if="row.note"> · {{ row.note }}</template></span></template>
          <template #cell-kind="{ row }">{{ title(row.kind) }}</template>
          <template #cell-where="{ row }">{{ row.warehouse_code }}/{{ row.bin_code }}</template>
          <template #cell-quantity="{ row }"><span :class="row.quantity < 0 ? 'neg' : 'pos'">{{ row.quantity > 0 ? '+' : '' }}{{ row.quantity }}</span></template>
        </DataTable>
        <UiPager v-model="moves.offset.value" :total="moves.total.value" :limit="moves.limit" :count="moves.items.value.length" />
      </LoadState>
    </UiCard>

    <template v-else>
      <div class="whs">
        <UiCard v-for="w in warehouses" :key="w.id" :title="w.name" :caption="`${w.code}${w.address ? ' · ' + w.address : ''}`">
          <template #actions>
            <UiBadge v-if="w.is_default" tone="brand">Default</UiBadge><UiBadge v-if="!w.is_active">Inactive</UiBadge>
            <UiButton v-if="can('inventory', 'manage')" variant="ghost" icon="edit" @click="editWh(w)">Edit</UiButton>
          </template>
          <div class="card-body">
            <p class="section-label">Bins</p>
            <button v-for="b in w.bins" :key="b.id" type="button" class="binrow" :class="{ off: !b.is_active }" :disabled="!can('inventory', 'manage')" @click="editBin(w, b)">
              <span class="mono">{{ b.code }}</span><span class="caption">{{ b.name }}{{ b.is_active ? '' : ' (inactive)' }}</span>
            </button>
            <UiButton v-if="can('inventory', 'manage')" variant="ghost" icon="plus" @click="editBin(w)">Add bin</UiButton>
          </div>
        </UiCard>
      </div>
      <UiButton v-if="can('inventory', 'manage')" icon="plus" @click="editWh()">Add warehouse</UiButton>
    </template>

    <UiModal :open="adj.open" title="Adjust stock" submit-label="Save" :busy="busy === 'adj'" :submit-disabled="!adj.variant || num(adj.qty) === null" @close="adj.open = false" @submit="saveAdjust">
      <VariantPicker v-if="!adj.variant" label="Item" @pick="pickVariant" />
      <p v-else><strong>{{ adj.variant.label }}</strong> <span class="caption mono">{{ adj.variant.sku }}</span></p>
      <UiSelect v-model="adj.bin" label="Bin" :options="bins" />
      <UiSelect v-model="adj.mode" label="What" :options="[{ value: 'change', label: 'Add or remove (e.g. 10 or -3)' }, { value: 'set', label: 'Set the count (after a stocktake)' }]" />
      <UiInput v-model="adj.qty" :label="adj.mode === 'set' ? 'Counted on hand' : 'Change by'" inputmode="numeric" />
      <UiInput v-model="adj.note" label="Note" placeholder="Stocktake, damaged, found…" />
    </UiModal>

    <UiModal :open="tr.open" title="Move stock between bins" submit-label="Move" :busy="busy === 'tr'" :submit-disabled="!tr.from || !tr.to || tr.from === tr.to || !num(tr.qty)" @close="tr.open = false" @submit="saveTransfer">
      <p><strong>{{ tr.variant?.label }}</strong> <span class="caption mono">{{ tr.variant?.sku }}</span></p>
      <div class="grid2"><UiSelect v-model="tr.from" label="From" :options="bins" /><UiSelect v-model="tr.to" label="To" :options="[{ value: '', label: 'Choose' }, ...bins]" /></div>
      <UiInput v-model="tr.qty" label="Quantity" inputmode="numeric" />
      <UiInput v-model="tr.note" label="Note" />
    </UiModal>

    <UiModal :open="wh.open" :title="wh.id ? 'Edit warehouse' : 'New warehouse'" submit-label="Save" :busy="busy === 'wh'" @close="wh.open = false" @submit="saveWh">
      <div class="grid2"><UiInput v-model="wh.code" label="Code" mono :disabled="!!wh.id" placeholder="MAIN" /><UiInput v-model="wh.name" label="Name" placeholder="Main shop" /></div>
      <UiInput v-model="wh.address" label="Address" multiline :rows="2" />
      <UiCheck v-model="wh.is_default" label="Default warehouse" hint="Orders take stock from here first." />
      <UiCheck v-if="wh.id" v-model="wh.is_active" label="Active" />
    </UiModal>

    <UiModal :open="bin.open" :title="bin.id ? `Bin ${bin.code}` : 'New bin'" submit-label="Save" :busy="busy === 'bin'" @close="bin.open = false" @submit="saveBin">
      <UiInput v-model="bin.code" label="Code" mono :disabled="!!bin.id" placeholder="A-01" />
      <UiInput v-model="bin.name" label="Name" placeholder="Shelf A, top" />
      <UiCheck v-if="bin.id" v-model="bin.is_active" label="Active" />
    </UiModal>
  </div>
</template>

<style scoped>
.narrow { flex: 0 1 180px !important; }
.neg { color: var(--danger); }
.pos { color: var(--success); }
.whs { display: grid; gap: 24px; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); }
.binrow { display: flex; justify-content: space-between; gap: 12px; padding: 8px 10px; border: 1px solid var(--border-default); border-radius: var(--radius-md); background: none; cursor: pointer; text-align: left; }
.binrow:hover:not(:disabled) { background: var(--bg-subtle); }
.binrow:disabled { cursor: default; }
.binrow.off { opacity: .55; }
</style>
