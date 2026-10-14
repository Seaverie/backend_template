<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import type { Product, StockLevel, Warehouse } from '@/api/types'
import DataTable, { type Column } from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiSelect from '@/components/UiSelect.vue'
import { useBusy, useLoader } from '@/composables'
import { dateTime, isMoney, money, title } from '@/format'

type Tab = 'products' | 'stock' | 'warehouses'
const tab = ref<Tab>('products')
const products = ref<Product[]>([])
const warehouses = ref<Warehouse[]>([])
const levels = ref<StockLevel[]>([])
const q = ref('')
const { busy, run } = useBusy()

const { loading, error, reload } = useLoader(async () => {
  const [p, w, s] = await Promise.all([api.products(), api.warehouses(), api.stockLevels()])
  products.value = p.items; warehouses.value = w.items; levels.value = s.items
})

const statusTone = { active: 'success', draft: 'neutral', archived: 'warning' } as const
const shown = computed(() => {
  const s = q.value.trim().toLowerCase()
  return s ? products.value.filter((p) => `${p.name} ${p.variants.map((v) => `${v.sku} ${v.name}`).join(' ')}`.toLowerCase().includes(s)) : products.value
})
const variants = computed(() => products.value.flatMap((p) => p.variants.map((v) => ({ ...v, product: p.name }))))
const bins = computed(() => warehouses.value.flatMap((w) => w.bins.map((b) => ({ ...b, warehouse: w.code, label: `${w.code} / ${b.code}` }))))
const stockRows = computed(() => levels.value.map((l) => {
  const v = variants.value.find((x) => x.id === l.variant_id), b = bins.value.find((x) => x.id === l.bin_id)
  return { key: `${l.variant_id}|${l.bin_id}`, ...l, sku: v?.sku ?? l.variant_id.slice(0, 8), name: v?.name ?? 'Unknown', bin: b?.label ?? l.bin_id.slice(0, 8) }
}).filter((r) => !q.value || `${r.sku} ${r.name} ${r.bin}`.toLowerCase().includes(q.value.toLowerCase())).sort((a, b) => a.sku.localeCompare(b.sku)))
const productAvailable = (p: Product) => p.variants.reduce((a, v) => a + totalOnHand(v.id), 0)
const totalOnHand = (variantId: string) => levels.value.filter((l) => l.variant_id === variantId).reduce((a, l) => a + l.available, 0)

const productCols: Column[] = [
  { key: 'name', label: 'Product', primary: true }, { key: 'status', label: 'Status' }, { key: 'variants', label: 'Variants' },
  { key: 'price', label: 'Price', align: 'right' }, { key: 'stock', label: 'Available', align: 'right', hideOnMobile: true },
]
const stockCols: Column[] = [
  { key: 'sku', label: 'Variant', primary: true }, { key: 'bin', label: 'Bin' }, { key: 'on_hand', label: 'On hand', align: 'right' },
  { key: 'reserved', label: 'Reserved', align: 'right' }, { key: 'available', label: 'Available', align: 'right' }, { key: 'act', label: '', align: 'right' },
]
const priceRange = (p: Product) => {
  const ps = p.variants.map((v) => Number(v.price)); if (!ps.length) return '—'
  const cur = p.variants[0].currency, lo = Math.min(...ps), hi = Math.max(...ps)
  return lo === hi ? money(lo, cur) : `${money(lo, cur)} – ${money(hi, cur)}`
}

// -- product create / edit --
interface VariantDraft { sku: string; name: string; price: string; currency: string }
const blankVariant = (): VariantDraft => ({ sku: '', name: '', price: '', currency: 'USD' })
const skuOk = (s: string) => /^[A-Za-z0-9_.-]{1,64}$/.test(s)
const variantOk = (v: VariantDraft) => skuOk(v.sku) && v.name.trim() && isMoney(v.price) && /^[A-Z]{3}$/.test(v.currency)
const create = reactive({ open: false, name: '', description: '', status: 'active', is_public: true, variants: [blankVariant()] })
function openCreate() { Object.assign(create, { open: true, name: '', description: '', status: 'active', is_public: true, variants: [blankVariant()] }) }
const createValid = computed(() => create.name.trim() && create.variants.every(variantOk))
async function submitCreate() {
  const body = { name: create.name.trim(), description: create.description.trim() || null, status: create.status, is_public: create.is_public,
    variants: create.variants.map((v) => ({ ...v, sku: v.sku.trim(), name: v.name.trim(), price: Number(v.price).toFixed(2), attributes: {} })) }
  if (await run('create', () => api.createProduct(body), `Product “${body.name}” created.`)) { create.open = false; await reload() }
}

const edit = reactive({ open: false, id: '', name: '', description: '', status: 'active', is_public: false, variant: blankVariant() })
const editing = computed(() => products.value.find((p) => p.id === edit.id) ?? null)
function openEdit(p: Product) { Object.assign(edit, { open: true, id: p.id, name: p.name, description: p.description ?? '', status: p.status, is_public: p.is_public, variant: blankVariant() }) }
async function saveEdit() {
  const body = { name: edit.name.trim(), description: edit.description.trim() || null, status: edit.status as Product['status'], is_public: edit.is_public }
  if (await run('save', () => api.updateProduct(edit.id, body), 'Product saved.')) { edit.open = false; await reload() }
}
async function addVariant() {
  const v = edit.variant
  if (!variantOk(v)) return
  if (await run('variant', () => api.addVariant(edit.id, { ...v, sku: v.sku.trim(), name: v.name.trim(), price: Number(v.price).toFixed(2) }), `Variant ${v.sku} added.`)) {
    edit.variant = blankVariant(); await reload()
  }
}

// -- stock adjustment --
const adj = reactive({ open: false, variant: '', bin: '', delta: '', note: '' })
function openAdjust(row?: { variant_id: string; bin_id: string }) { Object.assign(adj, { open: true, variant: row?.variant_id ?? '', bin: row?.bin_id ?? '', delta: '', note: '' }) }
const adjValid = computed(() => adj.variant && adj.bin && /^-?\d+$/.test(adj.delta.trim()) && Number(adj.delta) !== 0 && Math.abs(Number(adj.delta)) <= 1_000_000)
const current = computed(() => levels.value.find((l) => l.variant_id === adj.variant && l.bin_id === adj.bin))
async function submitAdjust() {
  const d = Number(adj.delta)
  if (await run('adjust', () => api.adjustStock(adj.variant, adj.bin, d, adj.note.trim() || undefined), `Stock ${d > 0 ? 'increased' : 'decreased'} by ${Math.abs(d)}.`)) { adj.open = false; await reload() }
}

// -- warehouses & bins --
const codeOk = (s: string) => /^[A-Za-z0-9_.-]{1,32}$/.test(s)
const wh = reactive({ open: false, code: '', name: '' })
async function submitWarehouse() {
  if (await run('wh', () => api.createWarehouse(wh.code.trim(), wh.name.trim()), `Warehouse ${wh.code} created. Add a bin to start storing stock.`)) { wh.open = false; await reload() }
}
const bin = reactive({ open: false, warehouse: '', code: '' })
const binWarehouse = computed(() => warehouses.value.find((w) => w.id === bin.warehouse))
async function submitBin() {
  if (await run('bin', () => api.createBin(bin.warehouse, bin.code.trim()), `Bin ${bin.code} added.`)) { bin.open = false; await reload() }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Catalog &amp; stock</h1><p class="muted">Products and their variants, where stock is kept, and how much is available.</p></div>
      <div class="actions">
        <UiButton v-if="tab === 'products'" variant="primary" icon="plus" @click="openCreate">New product</UiButton>
        <UiButton v-if="tab === 'stock'" variant="primary" icon="plus" :disabled="!variants.length || !bins.length" @click="openAdjust()">Adjust stock</UiButton>
        <UiButton v-if="tab === 'warehouses'" variant="primary" icon="plus" @click="Object.assign(wh, { open: true, code: '', name: '' })">New warehouse</UiButton>
      </div>
    </div>

    <div class="tabs" role="tablist">
      <button v-for="t in (['products', 'stock', 'warehouses'] as Tab[])" :key="t" role="tab" class="tab" :class="{ on: tab === t }" :aria-selected="tab === t" @click="tab = t">{{ title(t) }}</button>
    </div>

    <LoadState :loading="loading" :error="error" :ready="!!products.length || !!warehouses.length" @retry="reload">
      <UiCard v-if="tab === 'products'" title="Products" :caption="`${products.length} products · ${variants.length} variants`">
        <div class="toolbar"><input v-model="q" class="search-input" type="search" placeholder="Search name or SKU" aria-label="Search products" /></div>
        <EmptyState v-if="!products.length" icon="package" title="No products yet" body="Add a product with at least one variant (SKU and price) to start selling.">
          <UiButton variant="primary" icon="plus" @click="openCreate">New product</UiButton>
        </EmptyState>
        <DataTable v-else :columns="productCols" :rows="shown" row-key="id" clickable empty="No products match your search." @select="openEdit">
          <template #cell-name="{ row }"><strong>{{ row.name }}</strong><div class="caption">{{ row.is_public ? 'Public catalog' : 'Private' }} · added {{ dateTime(row.created_at) }}</div></template>
          <template #cell-status="{ row }"><UiBadge :tone="statusTone[row.status as Product['status']]">{{ title(row.status) }}</UiBadge></template>
          <template #cell-variants="{ row }"><span class="chips"><span v-for="v in row.variants.slice(0, 3)" :key="v.id" class="chip">{{ v.sku }}</span><span v-if="row.variants.length > 3" class="chip">+{{ row.variants.length - 3 }}</span></span></template>
          <template #cell-price="{ row }">{{ priceRange(row) }}</template>
          <template #cell-stock="{ row }">{{ productAvailable(row) }}</template>
        </DataTable>
      </UiCard>

      <UiCard v-else-if="tab === 'stock'" title="Stock by bin" caption="Every change is written to an append-only ledger.">
        <div class="toolbar"><input v-model="q" class="search-input" type="search" placeholder="Search SKU, name or bin" aria-label="Search stock" /></div>
        <EmptyState v-if="!bins.length" icon="package" title="No bins yet" body="Create a warehouse and a bin first; stock is always held in a bin.">
          <UiButton @click="tab = 'warehouses'">Go to warehouses</UiButton>
        </EmptyState>
        <DataTable v-else :columns="stockCols" :rows="stockRows" row-key="key" empty="No stock recorded yet. Use Adjust stock to add some.">
          <template #cell-sku="{ row }"><strong>{{ row.sku }}</strong><div class="caption">{{ row.name }}</div></template>
          <template #cell-available="{ row }"><UiBadge :tone="row.available <= 0 ? 'danger' : row.available < 10 ? 'warning' : 'success'">{{ row.available }}</UiBadge></template>
          <template #cell-act="{ row }"><UiButton variant="ghost" @click="openAdjust(row)">Adjust</UiButton></template>
        </DataTable>
      </UiCard>

      <div v-else class="wh-grid">
        <EmptyState v-if="!warehouses.length" icon="package" title="No warehouses yet" body="A warehouse holds bins; bins hold stock.">
          <UiButton variant="primary" icon="plus" @click="Object.assign(wh, { open: true, code: '', name: '' })">New warehouse</UiButton>
        </EmptyState>
        <UiCard v-for="w in warehouses" :key="w.id" :title="w.name" :caption="`${w.code} · ${w.bins.length} bins`">
          <template #actions><UiButton variant="ghost" icon="plus" @click="Object.assign(bin, { open: true, warehouse: w.id, code: '' })">Add bin</UiButton></template>
          <div class="card-body">
            <div v-if="w.bins.length" class="chips">
              <span v-for="b in w.bins" :key="b.id" class="chip">{{ b.code }} · {{ levels.filter((l) => l.bin_id === b.id).reduce((a, l) => a + l.on_hand, 0) }} units</span>
            </div>
            <p v-else class="caption">No bins yet.</p>
          </div>
        </UiCard>
      </div>
    </LoadState>

    <UiModal :open="create.open" title="New product" submit-label="Create product" wide :busy="busy === 'create'" :submit-disabled="!createValid" @close="create.open = false" @submit="submitCreate">
      <div class="grid2">
        <UiInput v-model="create.name" label="Name" required :maxlength="200" />
        <UiSelect v-model="create.status" label="Status" :options="['draft', 'active', 'archived'].map((s) => ({ value: s, label: title(s) }))" />
      </div>
      <UiInput v-model="create.description" label="Description" multiline :rows="2" />
      <UiCheck v-model="create.is_public" label="Show in the public catalog" hint="Anonymous visitors can see active, public products." />
      <p class="section-label">Variants</p>
      <div v-for="(v, i) in create.variants" :key="i" class="variant-row">
        <UiInput v-model="v.sku" label="SKU" mono :error="v.sku && !skuOk(v.sku) ? 'Letters, digits, . _ -' : ''" />
        <UiInput v-model="v.name" label="Variant name" />
        <UiInput v-model="v.price" label="Price" inputmode="decimal" :error="v.price && !isMoney(v.price) ? 'e.g. 19.99' : ''" />
        <UiInput v-model="v.currency" label="Currency" :maxlength="3" />
        <UiButton variant="ghost" icon="trash" :aria-label="`Remove variant ${i + 1}`" :disabled="create.variants.length === 1" @click="create.variants.splice(i, 1)" />
      </div>
      <div><UiButton variant="ghost" icon="plus" @click="create.variants.push(blankVariant())">Add variant</UiButton></div>
    </UiModal>

    <UiModal :open="edit.open" :title="editing?.name ?? 'Product'" submit-label="Save changes" wide :busy="busy === 'save'" :submit-disabled="!edit.name.trim()" @close="edit.open = false" @submit="saveEdit">
      <div class="grid2">
        <UiInput v-model="edit.name" label="Name" required :maxlength="200" />
        <UiSelect v-model="edit.status" label="Status" :options="['draft', 'active', 'archived'].map((s) => ({ value: s, label: title(s) }))" />
      </div>
      <UiInput v-model="edit.description" label="Description" multiline :rows="2" />
      <UiCheck v-model="edit.is_public" label="Show in the public catalog" />
      <p class="section-label">Variants</p>
      <div v-for="v in editing?.variants ?? []" :key="v.id" class="kv"><span><span class="mono">{{ v.sku }}</span> · {{ v.name }}</span><span>{{ money(v.price, v.currency) }} · {{ totalOnHand(v.id) }} available</span></div>
      <div class="variant-row">
        <UiInput v-model="edit.variant.sku" label="New SKU" mono />
        <UiInput v-model="edit.variant.name" label="Variant name" />
        <UiInput v-model="edit.variant.price" label="Price" inputmode="decimal" />
        <UiInput v-model="edit.variant.currency" label="Currency" :maxlength="3" />
        <UiButton icon="plus" :loading="busy === 'variant'" :disabled="!variantOk(edit.variant)" @click="addVariant">Add</UiButton>
      </div>
      <p class="caption">Variant prices are fixed once created; add a new variant to sell at a different price.</p>
    </UiModal>

    <UiModal :open="adj.open" title="Adjust stock" caption="Use a positive number to add stock and a negative one to remove it." submit-label="Apply adjustment"
             :busy="busy === 'adjust'" :submit-disabled="!adjValid" @close="adj.open = false" @submit="submitAdjust">
      <UiSelect v-model="adj.variant" label="Variant" :options="variants.map((v) => ({ value: v.id, label: `${v.sku} · ${v.name}` }))" placeholder="Choose a variant" />
      <UiSelect v-model="adj.bin" label="Bin" :options="bins.map((b) => ({ value: b.id, label: b.label }))" placeholder="Choose a bin" />
      <p v-if="adj.variant && adj.bin" class="caption">Currently {{ current?.on_hand ?? 0 }} on hand, {{ current?.reserved ?? 0 }} reserved.</p>
      <UiInput v-model="adj.delta" label="Change" inputmode="numeric" placeholder="e.g. 25 or -3" :error="adj.delta && !/^-?\d+$/.test(adj.delta.trim()) ? 'Whole numbers only' : ''" />
      <UiInput v-model="adj.note" label="Note (optional)" placeholder="e.g. Cycle count" :maxlength="500" />
    </UiModal>

    <UiModal :open="wh.open" title="New warehouse" submit-label="Create warehouse" :busy="busy === 'wh'" :submit-disabled="!codeOk(wh.code) || !wh.name.trim()" @close="wh.open = false" @submit="submitWarehouse">
      <UiInput v-model="wh.code" label="Code" required mono placeholder="MAIN" hint="Letters, digits, . _ - (up to 32)" />
      <UiInput v-model="wh.name" label="Name" required placeholder="Main warehouse" />
    </UiModal>
    <UiModal :open="bin.open" :title="`Add bin to ${binWarehouse?.code ?? ''}`" submit-label="Add bin" :busy="busy === 'bin'" :submit-disabled="!codeOk(bin.code)" @close="bin.open = false" @submit="submitBin">
      <UiInput v-model="bin.code" label="Bin code" required mono placeholder="A-01" />
    </UiModal>
  </div>
</template>

<style scoped>
.variant-row { display: grid; grid-template-columns: 1.1fr 1.4fr 0.8fr 0.6fr auto; gap: 10px; align-items: end; }
.wh-grid { display: grid; gap: var(--space-24); grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); }
@media (max-width: 767px) { .variant-row { grid-template-columns: 1fr 1fr; } .wh-grid { gap: var(--space-16); } }
</style>
