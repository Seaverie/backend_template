<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api } from '@/api/client'
import { hasModule } from '@/api/me'
import { num, str, useBusy, useLoader } from '@/composables'
import LoadState from '@/components/LoadState.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiSelect from '@/components/UiSelect.vue'

interface Status { code: string; label: string; color: string; stock_effect: string; is_initial: boolean; is_final: boolean; counts_as_sale: boolean }
const s = reactive({ tax: '0', inclusive: false, shipping: '0', free_over: '', storefront: false, on_paid: '', email: true })
const statuses = ref<Status[]>([])
const transitions = ref<Set<string>>(new Set())
const { loading, error, reload } = useLoader(async () => {
  const [o, lc] = await Promise.all([api.get('/orders/settings'), api.get('/orders/lifecycle')])
  Object.assign(s, { tax: str(o.tax_rate_percent), inclusive: o.prices_include_tax, shipping: str(o.shipping_flat), free_over: str(o.free_shipping_over), storefront: o.storefront_checkout, on_paid: o.status_on_paid ?? '', email: o.send_confirmation_email })
  statuses.value = lc.statuses.map((x: any) => ({ ...x, color: x.color ?? '#94a3b8' }))
  transitions.value = new Set(lc.transitions.map((t: any) => `${t.from}>${t.to}`))
})
const { busy, run } = useBusy()
async function saveCheckout() {
  await run('checkout', () => api.put('/orders/settings', {
    tax_rate_percent: num(s.tax) ?? 0, prices_include_tax: s.inclusive, shipping_flat: num(s.shipping) ?? 0,
    ...(num(s.free_over) === null ? { clear_free_shipping_over: true } : { free_shipping_over: num(s.free_over) }),
    storefront_checkout: s.storefront, ...(s.on_paid ? { status_on_paid: s.on_paid } : { clear_status_on_paid: true }), send_confirmation_email: s.email,
  }), 'Checkout settings saved.')
}

const has = (a: string, b: string) => transitions.value.has(`${a}>${b}`)
function flip(a: string, b: string) { const k = `${a}>${b}`; const n = new Set(transitions.value); if (n.has(k)) n.delete(k); else n.add(k); transitions.value = n }
function setInitial(i: number) { statuses.value.forEach((x, j) => { x.is_initial = i === j }) }
function addStatus() { statuses.value.push({ code: '', label: '', color: '#64748b', stock_effect: 'none', is_initial: false, is_final: false, counts_as_sale: false }) }
function removeStatus(i: number) {
  const code = statuses.value[i].code
  statuses.value.splice(i, 1)
  transitions.value = new Set([...transitions.value].filter((k) => !k.split('>').includes(code)))
}
function move(i: number, d: number) { const a = statuses.value; const j = i + d; if (j >= 0 && j < a.length) [a[i], a[j]] = [a[j], a[i]] }
const codeFrom = (l: string) => l.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').replace(/^(\d)/, 's_$1').slice(0, 32)
async function saveLifecycle() {
  statuses.value.forEach((x) => { if (!x.code) x.code = codeFrom(x.label) })
  const codes = new Set(statuses.value.map((x) => x.code))
  await run('lc', async () => {
    const r = await api.put('/orders/lifecycle', {
      statuses: statuses.value,
      transitions: [...transitions.value].map((k) => k.split('>')).filter(([a, b]) => codes.has(a) && codes.has(b)).map(([from, to]) => ({ from, to })),
    })
    statuses.value = r.statuses; transitions.value = new Set(r.transitions.map((t: any) => `${t.from}>${t.to}`))
  }, 'Order statuses saved. Existing orders keep their current status.')
}
</script>

<template>
  <LoadState :loading="loading" :error="error" :ready="statuses.length > 0" @retry="reload">
    <UiCard title="Checkout" caption="Tax and shipping for new orders from the dashboard, the shop and the API.">
      <form class="card-body" @submit.prevent="saveCheckout">
        <div class="grid3">
          <UiInput v-model="s.tax" label="Tax rate (%)" inputmode="decimal" hint="7 for Thai VAT." />
          <UiInput v-model="s.shipping" label="Flat shipping fee" inputmode="decimal" />
          <UiInput v-model="s.free_over" label="Free shipping over" inputmode="decimal" placeholder="Never" />
        </div>
        <UiCheck v-model="s.inclusive" label="Prices include tax" hint="Tax is worked out from the price instead of added on top." />
        <UiSelect v-if="hasModule('payments')" v-model="s.on_paid" label="When an order is paid in full, move it to" :options="[{ value: '', label: 'Leave the status as it is' }, ...statuses.map((x) => ({ value: x.code, label: x.label }))]" />
        <UiCheck v-model="s.storefront" toggle label="Allow checkout from the public shop API" hint="Your website or app can place orders without signing in." />
        <UiCheck v-if="hasModule('email')" v-model="s.email" toggle label="Email the customer an order confirmation" />
        <div><UiButton type="submit" variant="primary" :loading="busy === 'checkout'">Save</UiButton></div>
      </form>
    </UiCard>

    <UiCard title="Order statuses" caption="The steps an order goes through and what each step does to stock.">
      <template #actions><UiButton icon="plus" @click="addStatus">Add status</UiButton></template>
      <div class="statuses">
        <div v-for="(x, i) in statuses" :key="i" class="st">
          <input v-model="x.color" type="color" class="sw" :aria-label="`Colour of ${x.label}`" />
          <div class="grow grid2">
            <UiInput v-model="x.label" label="Name" />
            <UiSelect v-model="x.stock_effect" label="Stock" :options="[{ value: 'none', label: 'No change' }, { value: 'reserve', label: 'Reserve (hold for this order)' }, { value: 'deduct', label: 'Deduct (it left the shop)' }, { value: 'release', label: 'Release (give back)' }]" />
            <div class="flags">
              <label><input type="radio" name="initial" :checked="x.is_initial" @change="setInitial(i)" /> First status</label>
              <label><input v-model="x.is_final" type="checkbox" /> Final</label>
              <label><input v-model="x.counts_as_sale" type="checkbox" /> Counts as a sale</label>
              <span class="caption mono">{{ x.code || codeFrom(x.label) || 'code' }}</span>
            </div>
          </div>
          <div class="ord">
            <button type="button" aria-label="Move up" :disabled="i === 0" @click="move(i, -1)">▲</button>
            <button type="button" aria-label="Move down" :disabled="i === statuses.length - 1" @click="move(i, 1)">▼</button>
            <button type="button" aria-label="Remove status" @click="removeStatus(i)">✕</button>
          </div>
        </div>
      </div>
      <div class="card-body">
        <p class="section-label">Allowed moves (from row → to column)</p>
        <div class="tw">
          <table class="tm">
            <thead><tr><th /><th v-for="b in statuses" :key="b.code + b.label">{{ b.label || '…' }}</th></tr></thead>
            <tbody>
              <tr v-for="a in statuses" :key="a.code + a.label">
                <th>{{ a.label || '…' }}</th>
                <td v-for="b in statuses" :key="b.code + b.label"><input v-if="a !== b && a.code && b.code" type="checkbox" :checked="has(a.code, b.code)" :aria-label="`${a.label} to ${b.label}`" @change="flip(a.code, b.code)" /></td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="caption">New statuses get a code when saved; save once before allowing moves to them.</p>
        <div><UiButton variant="primary" :loading="busy === 'lc'" @click="saveLifecycle">Save statuses</UiButton></div>
      </div>
    </UiCard>
  </LoadState>
</template>

<style scoped>
.statuses { display: grid; gap: 0; }
.st { display: flex; gap: 12px; align-items: flex-start; padding: 14px 24px; border-top: 1px solid var(--border-default); }
.sw { width: 36px; height: 36px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); padding: 2px; margin-top: 22px; background: var(--bg-surface); flex: none; }
.grow { flex: 1; min-width: 0; }
.flags { grid-column: 1 / -1; display: flex; gap: 16px; flex-wrap: wrap; align-items: center; font-size: 13px; }
.flags label { display: flex; gap: 6px; align-items: center; }
.ord { display: flex; flex-direction: column; gap: 2px; margin-top: 20px; }
.ord button { border: 0; background: none; cursor: pointer; color: var(--text-secondary); font-size: 11px; padding: 2px 6px; }
.ord button:disabled { opacity: .3; }
.tw { overflow-x: auto; }
.tm { border-collapse: collapse; font-size: 12px; }
.tm th { font-weight: 500; color: var(--text-secondary); padding: 6px 8px; white-space: nowrap; }
.tm thead th { writing-mode: vertical-rl; transform: rotate(180deg); text-align: left; }
.tm tbody th { text-align: right; }
.tm td { text-align: center; padding: 4px 8px; border: 1px solid var(--border-default); }
.tm input { accent-color: var(--brand-primary); }
@media (max-width: 767px) { .st { padding: 12px 16px; } }
</style>
