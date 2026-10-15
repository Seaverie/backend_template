<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { can, currency, hasModule } from '@/api/me'
import { num, opt, str, useBusy, useLoader } from '@/composables'
import { money } from '@/format'
import { ask, toast } from '@/ui'
import FileButton from '@/components/FileButton.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiSelect from '@/components/UiSelect.vue'

const props = defineProps<{ id: string }>()
const router = useRouter()
const isNew = computed(() => props.id === 'new')
const editable = computed(() => can('products', isNew.value ? 'create' : 'update'))
const cats = ref<any[]>([])
const product = ref<any>(null)
const form = reactive({ name: '', slug: '', description: '', status: 'draft', category: '', attributes: '' })
const images = ref<{ file_id: string; url: string; thumbnail_url?: string | null }[]>([])
const variants = ref<any[]>([]) // for a new product: kept locally until saved
const { busy, run } = useBusy()

const attrsToText = (a: Record<string, unknown>) => Object.entries(a ?? {}).map(([k, v]) => `${k}: ${typeof v === 'string' ? v : JSON.stringify(v)}`).join('\n')
const textToMap = (t: string) => Object.fromEntries(t.split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
  const i = l.indexOf(':'); return i < 0 ? [l, ''] : [l.slice(0, i).trim(), l.slice(i + 1).trim()]
}).filter(([k]) => k))

function fill(p: any) {
  product.value = p
  Object.assign(form, { name: p.name, slug: p.slug, description: p.description ?? '', status: p.status, category: p.category_id ?? '', attributes: attrsToText(p.attributes) })
  images.value = p.images.map((i: any) => ({ ...i }))
  variants.value = p.variants
}
const { loading, error, reload } = useLoader(async () => {
  cats.value = (await api.get('/product-categories')).items
  if (!isNew.value) fill(await api.get(`/products/${props.id}`))
})

async function save() {
  if (!form.name.trim()) { toast('Give the product a name.', 'err'); return }
  const base = {
    name: form.name.trim(), slug: opt(form.slug), description: form.description, status: form.status,
    attributes: textToMap(form.attributes), image_file_ids: images.value.map((i) => i.file_id),
  }
  if (isNew.value) {
    if (!variants.value.length) { toast('Add at least one variant (even a single default one).', 'err'); return }
    await run('save', async () => {
      const p = await api.post('/products', { ...base, category_id: form.category || null, variants: variants.value.map(({ _key, ...v }) => v) })
      router.replace(`/products/${p.id}`)
    }, 'Product created.')
  } else {
    await run('save', async () => fill(await api.patch(`/products/${props.id}`, { ...base, ...(form.category ? { category_id: form.category } : { clear_category: true }) })), 'Saved.')
  }
}
async function remove() {
  if (!await ask({ title: `Delete “${product.value.name}”?`, body: 'Products that were ordered are archived instead so order history stays intact.', confirmLabel: 'Delete', danger: true })) return
  if (await run('del', () => api.del(`/products/${props.id}`), 'Product removed.')) router.push('/products')
}

// Images (uploaded as public files so the shop can show them)
async function upload(files: File[]) {
  await run('img', async () => {
    for (const file of files) {
      const fd = new FormData(); fd.append('file', file)
      const r = await api.upload(`/files?purpose=product_image&public=true`, fd)
      images.value.push({ file_id: r.id, url: r.url, thumbnail_url: r.thumbnail_url })
    }
  }, isNew.value ? undefined : 'Uploaded. Save to keep the new images.')
}
function moveImage(i: number, d: number) { const a = images.value; const j = i + d; if (j < 0 || j >= a.length) return; [a[i], a[j]] = [a[j], a[i]] }

// Variants
const blankVariant = () => ({ id: '', sku: '', name: '', price: '', compare_at_price: '', cost: '', barcode: '', weight_grams: '', track_inventory: true, low_stock_threshold: '', is_active: true, options: '', sort_order: '0' })
const v = reactive({ open: false, index: -1, ...blankVariant() })
function editVariant(x?: any, index = -1) {
  Object.assign(v, blankVariant(), { open: true, index })
  if (x) Object.assign(v, {
    id: x.id ?? '', sku: x.sku, name: x.name ?? '', price: str(x.price), compare_at_price: str(x.compare_at_price), cost: str(x.cost), barcode: x.barcode ?? '',
    weight_grams: str(x.weight_grams), track_inventory: x.track_inventory, low_stock_threshold: str(x.low_stock_threshold), is_active: x.is_active,
    options: attrsToText(x.options ?? {}), sort_order: str(x.sort_order ?? 0),
  })
}
function variantBody(forPatch: boolean) {
  const body: any = {
    sku: v.sku.trim(), name: opt(v.name), price: num(v.price), compare_at_price: num(v.compare_at_price), cost: num(v.cost), barcode: opt(v.barcode),
    weight_grams: num(v.weight_grams), track_inventory: v.track_inventory, low_stock_threshold: num(v.low_stock_threshold), is_active: v.is_active,
    options: textToMap(v.options), sort_order: num(v.sort_order) ?? 0,
  }
  if (forPatch) {
    body.clear = (['name', 'compare_at_price', 'cost', 'barcode', 'weight_grams', 'low_stock_threshold'] as const).filter((k) => body[k] === null)
    for (const k of body.clear) delete body[k]
  }
  return body
}
async function saveVariant() {
  if (!v.sku.trim() || num(v.price) === null) { toast('A variant needs a SKU and a price.', 'err'); return }
  if (isNew.value) {
    const body = { ...variantBody(false), _key: Math.random() }
    if (v.index >= 0) variants.value[v.index] = body; else variants.value.push(body)
    v.open = false
    return
  }
  const ok = await run('variant', async () => {
    if (v.id) await api.patch(`/products/${props.id}/variants/${v.id}`, variantBody(true))
    else await api.post(`/products/${props.id}/variants`, variantBody(false))
    fill(await api.get(`/products/${props.id}`))
  }, 'Variant saved.')
  if (ok) v.open = false
}
async function deleteVariant() {
  if (isNew.value) { variants.value.splice(v.index, 1); v.open = false; return }
  if (!await ask({ title: `Delete variant ${v.sku}?`, confirmLabel: 'Delete', danger: true })) return
  const ok = await run('variant', async () => { await api.del(`/products/${props.id}/variants/${v.id}`); fill(await api.get(`/products/${props.id}`)) }, 'Variant deleted.')
  if (ok) v.open = false
}
</script>

<template>
  <div class="page">
    <LoadState :loading="loading" :error="error" :ready="isNew || !!product" @retry="reload">
      <PageHeader :title="isNew ? 'New product' : product?.name ?? ''" back="/products">
        <template v-if="product" #sub><div class="chips sub"><StatusBadge :status="product.status" /><span class="caption mono">/{{ product.slug }}</span></div></template>
        <UiButton v-if="!isNew && can('products', 'delete')" variant="ghost" icon="trash" :loading="busy === 'del'" @click="remove">Delete</UiButton>
        <UiButton v-if="editable" variant="primary" icon="check" :loading="busy === 'save'" @click="save">{{ isNew ? 'Create product' : 'Save' }}</UiButton>
      </PageHeader>
      <div class="split">
        <div class="col">
          <UiCard title="Details">
            <div class="card-body">
              <UiInput v-model="form.name" label="Name" required :disabled="!editable" />
              <UiInput v-model="form.description" label="Description" multiline :rows="5" :disabled="!editable" />
              <UiInput v-model="form.attributes" label="Attributes" multiline :rows="3" mono placeholder="material: cotton&#10;origin: Chiang Mai" hint="One per line as name: value. Shown in your shop and available to the API." :disabled="!editable" />
            </div>
          </UiCard>
          <UiCard title="Variants" caption="Each size, colour or pack you sell, with its own SKU and price.">
            <template #actions><UiButton v-if="editable" icon="plus" @click="editVariant()">Add variant</UiButton></template>
            <table class="vt">
              <thead><tr><th>Variant</th><th class="r">Price</th><th v-if="hasModule('inventory') && !isNew" class="r">Available</th><th /></tr></thead>
              <tbody>
                <tr v-for="(x, i) in variants" :key="x.id || x._key" :class="{ off: !x.is_active }" @click="editable && editVariant(x, i)">
                  <td><strong>{{ x.name || 'Default' }}</strong><div class="caption mono">{{ x.sku }}<template v-if="Object.keys(x.options ?? {}).length"> · {{ Object.entries(x.options).map(([k, o]) => `${k}: ${o}`).join(', ') }}</template></div></td>
                  <td class="r">{{ money(x.price, currency()) }}<div v-if="x.compare_at_price" class="caption strike">{{ money(x.compare_at_price, currency()) }}</div></td>
                  <td v-if="hasModule('inventory') && !isNew" class="r">{{ x.track_inventory ? x.stock?.available ?? 0 : 'Not tracked' }}</td>
                  <td class="r"><span v-if="editable" class="caption">Edit</span></td>
                </tr>
                <tr v-if="!variants.length"><td colspan="4" class="muted empty">No variants yet. A product needs at least one.</td></tr>
              </tbody>
            </table>
          </UiCard>
        </div>
        <div class="col">
          <UiCard title="Status">
            <div class="card-body">
              <UiSelect v-model="form.status" label="Visibility" :disabled="!editable" :options="[{ value: 'draft', label: 'Draft (hidden)' }, { value: 'active', label: 'Active (for sale)' }, { value: 'archived', label: 'Archived' }]" />
              <UiSelect v-model="form.category" label="Category" :disabled="!editable" :options="[{ value: '', label: 'None' }, ...cats.map((c) => ({ value: c.id, label: c.name }))]" />
              <UiInput v-model="form.slug" label="Web address name" placeholder="made from the name" :disabled="!editable" />
            </div>
          </UiCard>
          <UiCard title="Images" :caption="hasModule('files') ? 'The first image is the main one.' : 'Switch on the Files module to add images.'">
            <template v-if="hasModule('files') && editable" #actions><FileButton accept="image/*" multiple :loading="busy === 'img'" @pick="upload">Upload</FileButton></template>
            <div class="imgs">
              <div v-for="(im, i) in images" :key="im.file_id" class="img">
                <img :src="im.thumbnail_url ?? im.url" alt="" />
                <div v-if="editable" class="img-acts">
                  <button type="button" :disabled="i === 0" aria-label="Move earlier" @click="moveImage(i, -1)">‹</button>
                  <button type="button" aria-label="Remove image" @click="images.splice(i, 1)">✕</button>
                  <button type="button" :disabled="i === images.length - 1" aria-label="Move later" @click="moveImage(i, 1)">›</button>
                </div>
              </div>
              <p v-if="!images.length" class="muted">No images.</p>
            </div>
          </UiCard>
        </div>
      </div>
    </LoadState>

    <UiModal :open="v.open" :title="v.id || v.index >= 0 ? `Variant ${v.sku}` : 'New variant'" wide submit-label="Save variant" :busy="busy === 'variant'" @close="v.open = false" @submit="saveVariant">
      <div class="grid3">
        <UiInput v-model="v.sku" label="SKU" required mono />
        <UiInput v-model="v.name" label="Name" placeholder="Large, Red, 6-pack…" />
        <UiInput v-model="v.barcode" label="Barcode" />
      </div>
      <div class="grid3">
        <UiInput v-model="v.price" label="Price" inputmode="decimal" required />
        <UiInput v-model="v.compare_at_price" label="Was (compare-at)" inputmode="decimal" />
        <UiInput v-model="v.cost" label="Cost" inputmode="decimal" hint="For margins; never shown to customers." />
      </div>
      <div class="grid3">
        <UiInput v-model="v.weight_grams" label="Weight (g)" inputmode="numeric" />
        <UiInput v-model="v.low_stock_threshold" label="Low-stock level" inputmode="numeric" />
        <UiInput v-model="v.sort_order" label="Order" inputmode="numeric" />
      </div>
      <UiInput v-model="v.options" label="Options" multiline :rows="2" mono placeholder="size: L&#10;colour: red" />
      <UiCheck v-model="v.track_inventory" toggle label="Track stock" hint="Orders reserve and deduct stock for this variant." />
      <UiCheck v-model="v.is_active" toggle label="For sale" />
      <template v-if="v.id || v.index >= 0" #footer-start><UiButton variant="ghost" icon="trash" @click="deleteVariant">Delete</UiButton></template>
    </UiModal>
  </div>
</template>

<style scoped>
.sub { margin-top: 6px; align-items: center; }
.col { display: grid; gap: 24px; min-width: 0; align-content: start; }
.vt { width: 100%; border-collapse: collapse; }
.vt th { text-align: left; font-size: 12px; font-weight: 500; color: var(--text-secondary); background: var(--bg-subtle); padding: 10px 24px; }
.vt td { padding: 12px 24px; border-top: 1px solid var(--border-default); cursor: pointer; }
.vt tr:hover td { background: var(--bg-canvas); }
.vt tr.off td { opacity: .55; }
.vt .empty { cursor: default; text-align: center; }
.r { text-align: right !important; }
.strike { text-decoration: line-through; }
.imgs { display: grid; grid-template-columns: repeat(auto-fill, minmax(96px, 1fr)); gap: 10px; padding: 0 24px 20px; }
.img { position: relative; aspect-ratio: 1; border-radius: var(--radius-md); overflow: hidden; border: 1px solid var(--border-default); background: var(--bg-subtle); }
.img img { width: 100%; height: 100%; object-fit: cover; }
.img-acts { position: absolute; inset: auto 0 0 0; display: flex; justify-content: space-between; background: rgba(15, 23, 42, .6); }
.img-acts button { border: 0; background: none; color: #fff; padding: 4px 8px; cursor: pointer; }
.img-acts button:disabled { opacity: .3; }
@media (max-width: 767px) { .vt th, .vt td { padding-left: 16px; padding-right: 16px; } .imgs { padding: 0 16px 16px; } }
</style>
