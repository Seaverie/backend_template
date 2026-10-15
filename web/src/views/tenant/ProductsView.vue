<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { can, currency, hasModule } from '@/api/me'
import { num, opt, useBusy, useList, useLoader } from '@/composables'
import { money } from '@/format'
import { ask } from '@/ui'
import DataTable from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiSelect from '@/components/UiSelect.vue'
import UiPager from '@/components/UiPager.vue'

const router = useRouter()
const cats = ref<any[]>([])
const loadCats = useLoader(async () => { cats.value = (await api.get('/product-categories')).items })
const catName = (id: string | null) => cats.value.find((c) => c.id === id)?.name ?? ''
const list = useList((q) => api.get('/products', q), { q: '', status: '', category_id: '' })
const f = list.filters
const priceRange = (p: any) => {
  const ps = p.variants.map((v: any) => Number(v.price))
  if (!ps.length) return '—'
  const lo = Math.min(...ps), hi = Math.max(...ps)
  return lo === hi ? money(lo, currency()) : `${money(lo, currency())} – ${money(hi, currency())}`
}
const stock = (p: any) => p.variants.some((v: any) => v.stock) ? p.variants.reduce((s: number, v: any) => s + (v.stock?.available ?? 0), 0) : null

const { busy, run } = useBusy()
const cat = reactive({ open: false, id: '', name: '', slug: '', parent: '', sort: '0' })
function editCat(c?: any) { Object.assign(cat, { open: true, id: c?.id ?? '', name: c?.name ?? '', slug: c?.slug ?? '', parent: c?.parent_id ?? '', sort: String(c?.sort_order ?? 0) }) }
async function saveCat() {
  const body = { name: cat.name.trim(), slug: opt(cat.slug), parent_id: cat.parent || null, sort_order: num(cat.sort) ?? 0, ...(cat.id && !cat.parent ? { clear_parent: true } : {}) }
  const ok = await run('cat', () => (cat.id ? api.patch(`/product-categories/${cat.id}`, body) : api.post('/product-categories', body)), 'Category saved.')
  if (ok) { cat.open = false; await loadCats.reload() }
}
async function delCat() {
  if (!await ask({ title: `Delete “${cat.name}”?`, body: 'Products in it stay, without a category.', confirmLabel: 'Delete', danger: true })) return
  const ok = await run('cat', () => api.del(`/product-categories/${cat.id}`), 'Category deleted.')
  if (ok) { cat.open = false; await loadCats.reload(); await list.reload() }
}
const tree = computed(() => {
  const out: { c: any; depth: number }[] = []
  const walk = (parent: string | null, depth: number) => cats.value.filter((c) => c.parent_id === parent).sort((a, b) => a.sort_order - b.sort_order || a.name.localeCompare(b.name)).forEach((c) => { out.push({ c, depth }); walk(c.id, depth + 1) })
  walk(null, 0)
  return out
})
</script>

<template>
  <div class="page">
    <PageHeader title="Products" caption="What you sell, with variants, prices and images.">
      <UiButton v-if="can('products', 'create')" icon="plus" variant="primary" @click="router.push('/products/new')">New product</UiButton>
    </PageHeader>
    <div class="split">
      <UiCard>
        <div class="toolbar" style="padding-top: 16px">
          <input v-model="f.q" class="search-input" type="search" placeholder="Search name, SKU, barcode…" aria-label="Search products" />
          <select v-model="f.status" class="search-input narrow" aria-label="Status"><option value="">Any status</option><option value="active">Active</option><option value="draft">Draft</option><option value="archived">Archived</option></select>
          <select v-model="f.category_id" class="search-input narrow" aria-label="Category"><option value="">All categories</option><option v-for="t in tree" :key="t.c.id" :value="t.c.id">{{ '— '.repeat(t.depth) }}{{ t.c.name }}</option></select>
        </div>
        <LoadState :loading="list.loading.value" :error="list.error.value" :ready="list.items.value.length > 0" @retry="list.reload">
          <DataTable :columns="[{ key: 'name', label: 'Product', primary: true }, { key: 'status', label: 'Status' }, { key: 'price', label: 'Price', align: 'right' }, ...(hasModule('inventory') ? [{ key: 'stock', label: 'Available', align: 'right' as const }] : [])]"
                     :rows="list.items.value" row-key="id" clickable empty="No products yet." @select="(p) => router.push(`/products/${p.id}`)">
            <template #cell-name="{ row }">
              <div class="prod">
                <img v-if="row.images[0]" :src="row.images[0].thumbnail_url ?? row.images[0].url" alt="" class="thumb" loading="lazy" />
                <span v-else class="thumb ph" />
                <div><strong>{{ row.name }}</strong><span class="caption">{{ row.variants.length }} variant{{ row.variants.length === 1 ? '' : 's' }}<template v-if="row.category_id"> · {{ catName(row.category_id) }}</template></span></div>
              </div>
            </template>
            <template #cell-status="{ row }"><StatusBadge :status="row.status" /></template>
            <template #cell-price="{ row }">{{ priceRange(row) }}</template>
            <template #cell-stock="{ row }">{{ stock(row) ?? '—' }}</template>
          </DataTable>
          <UiPager v-model="list.offset.value" :total="list.total.value" :limit="list.limit" :count="list.items.value.length" />
        </LoadState>
      </UiCard>
      <UiCard title="Categories" caption="Group products for your shop and reports.">
        <template #actions><UiButton v-if="can('products', 'manage') || can('products', 'create')" icon="plus" @click="editCat()">Add</UiButton></template>
        <div class="cats">
          <p v-if="!cats.length" class="muted">No categories yet.</p>
          <button v-for="t in tree" :key="t.c.id" type="button" class="cat" :style="{ paddingLeft: 12 + t.depth * 18 + 'px' }" @click="editCat(t.c)">
            <span>{{ t.c.name }}</span><span class="caption">{{ t.c.product_count }}</span>
          </button>
        </div>
      </UiCard>
    </div>

    <UiModal :open="cat.open" :title="cat.id ? 'Edit category' : 'New category'" submit-label="Save" :busy="busy === 'cat'" :submit-disabled="!cat.name.trim()" @close="cat.open = false" @submit="saveCat">
      <UiInput v-model="cat.name" label="Name" required />
      <div class="grid2"><UiInput v-model="cat.slug" label="Web address name" placeholder="made from the name" hint="Lowercase letters, digits, dashes." /><UiInput v-model="cat.sort" label="Order" inputmode="numeric" /></div>
      <UiSelect v-model="cat.parent" label="Inside" :options="[{ value: '', label: 'Top level' }, ...cats.filter((c) => c.id !== cat.id).map((c) => ({ value: c.id, label: c.name }))]" />
      <template v-if="cat.id" #footer-start><UiButton variant="ghost" icon="trash" @click="delCat">Delete</UiButton></template>
    </UiModal>
  </div>
</template>

<style scoped>
.narrow { flex: 0 1 170px !important; }
.prod { display: flex; gap: 12px; align-items: center; min-width: 0; }
.prod > div { display: flex; flex-direction: column; min-width: 0; }
.thumb { width: 40px; height: 40px; border-radius: var(--radius-sm); object-fit: cover; background: var(--bg-subtle); flex: none; border: 1px solid var(--border-default); }
.cats { padding: 0 12px 16px; display: grid; }
.cat { display: flex; justify-content: space-between; align-items: center; border: 0; background: none; padding: 9px 12px; border-radius: var(--radius-md); cursor: pointer; text-align: left; }
.cat:hover { background: var(--bg-subtle); }
</style>
