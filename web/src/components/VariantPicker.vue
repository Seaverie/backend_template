<script setup lang="ts">
// Search the catalog and pick a variant (by product name, SKU or barcode).
import { ref, useId, watch } from 'vue'
import { api } from '@/api/client'
import { money } from '@/format'

export interface PickedVariant {
  id: string; sku: string; name: string | null; price: number; cost: number | null; product_id: string; product_name: string
  label: string; currency?: string; stock?: { on_hand: number; reserved: number; available: number } | null; track_inventory?: boolean
}
const props = defineProps<{ label?: string; placeholder?: string; currency?: string; showCost?: boolean }>()
const emit = defineEmits<{ pick: [v: PickedVariant] }>()
const id = useId()
const q = ref('')
const results = ref<PickedVariant[]>([])
const open = ref(false)
const active = ref(0)
let timer: ReturnType<typeof setTimeout> | undefined
let seq = 0

watch(q, (text) => {
  clearTimeout(timer)
  if (!text.trim()) { results.value = []; return }
  timer = setTimeout(async () => {
    const mine = ++seq
    try {
      const r = await api.get('/products', { q: text.trim(), limit: 10 })
      if (mine !== seq) return
      results.value = r.items.flatMap((p: any) => p.variants.filter((v: any) => v.is_active).map((v: any) => ({
        ...v, product_id: p.id, product_name: p.name, label: v.name ? `${p.name} – ${v.name}` : p.name,
      }))).slice(0, 12)
      open.value = true; active.value = 0
    } catch { results.value = [] }
  }, 250)
})
function choose(v?: PickedVariant) {
  const t = v ?? results.value[active.value]
  if (!t) return
  emit('pick', t); q.value = ''; results.value = []; open.value = false
}
function key(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') { active.value = Math.min(active.value + 1, results.value.length - 1); e.preventDefault() }
  else if (e.key === 'ArrowUp') { active.value = Math.max(active.value - 1, 0); e.preventDefault() }
  else if (e.key === 'Enter') { e.preventDefault(); choose() }
  else if (e.key === 'Escape') open.value = false
}
void props
</script>

<template>
  <div class="picker">
    <label :for="id" class="label">{{ label ?? 'Add a product' }}</label>
    <input :id="id" v-model="q" type="search" autocomplete="off" :placeholder="placeholder ?? 'Search by name or SKU…'" role="combobox"
           :aria-expanded="open && results.length > 0" @keydown="key" @focus="open = true" @blur="open = false" />
    <ul v-if="open && results.length" class="list" role="listbox">
      <li v-for="(v, i) in results" :key="v.id" role="option" :aria-selected="i === active" :class="{ on: i === active }"
          @mousedown.prevent="choose(v)" @mouseenter="active = i">
        <span class="name"><strong>{{ v.label }}</strong><span class="caption mono">{{ v.sku }}</span></span>
        <span class="meta">
          <span>{{ showCost ? money(v.cost ?? 0, currency) : money(v.price, currency) }}</span>
          <span v-if="v.track_inventory && v.stock" class="caption">{{ v.stock.available }} available</span>
        </span>
      </li>
    </ul>
    <p v-else-if="open && q.trim() && !results.length" class="caption hint">No matching products.</p>
  </div>
</template>

<style scoped>
.picker { position: relative; display: flex; flex-direction: column; gap: 6px; }
input { width: 100%; padding: 10px 12px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); background: var(--bg-surface); font-size: 16px; }
@media (min-width: 768px) { input { font-size: 14px; } }
.list { position: absolute; z-index: 30; top: 100%; left: 0; right: 0; margin: 4px 0 0; padding: 6px; list-style: none; background: var(--bg-surface); border: 1px solid var(--border-default); border-radius: var(--radius-md); box-shadow: var(--shadow-overlay); max-height: 300px; overflow-y: auto; }
li { display: flex; justify-content: space-between; gap: 12px; padding: 8px 10px; border-radius: var(--radius-sm); cursor: pointer; }
li.on { background: var(--brand-soft); }
.name, .meta { display: flex; flex-direction: column; min-width: 0; }
.meta { align-items: flex-end; flex: none; }
.hint { margin-top: 2px; }
</style>
