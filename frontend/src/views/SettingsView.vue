<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { api, BASE } from '@/api/client'
import { session } from '@/api/session'
import type { Gateway, Me } from '@/api/types'
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
import { dateTime, title } from '@/format'
import { copy } from '@/ui'

const me = ref<Me | null>(null)
const gateways = ref<Gateway[]>([])
const gatewayError = ref('')
const { busy, run } = useBusy()
const { loading, error, reload } = useLoader(async () => {
  me.value = await api.me()
  try { gateways.value = (await api.gateways()).items; gatewayError.value = '' } catch (e) { gatewayError.value = e instanceof Error ? e.message : 'Not available.' }
})

// -- rate limit (tenant admins only; the platform sets the ceiling) --
const rate = reactive({ value: '', saved: null as null | { rate_limit_per_min: number; platform_max_per_min: number } })
async function saveRate() {
  const n = Number(rate.value)
  await run('rate', async () => { rate.saved = await api.setRateLimit(n) }, `Rate limit set to ${n} requests per minute.`)
}

// -- payment gateways --
const randomSecret = () => Array.from(crypto.getRandomValues(new Uint8Array(24)), (b) => b.toString(16).padStart(2, '0')).join('')
const RULES: Record<string, Record<string, RegExp>> = {
  sandbox: { webhook_secret: /^[A-Za-z0-9_-]{24,200}$/ },
  stripe: { api_key: /^sk_(test|live)_[A-Za-z0-9]{10,200}$/, webhook_secret: /^whsec_[A-Za-z0-9+/=]{10,200}$/ },
}
const HINTS: Record<string, string> = {
  api_key: 'Secret key from the Stripe dashboard (sk_test_… or sk_live_…).',
  webhook_secret: 'Signing secret used to verify payment webhooks.',
}
const gw = reactive({ open: false, name: '', provider: 'sandbox', secrets: {} as Record<string, string>, is_default: true })
function openGateway() { Object.assign(gw, { open: true, name: 'Sandbox', provider: 'sandbox', secrets: { webhook_secret: randomSecret() }, is_default: !gateways.value.length }) }
function setProvider(p: string) { gw.provider = p; gw.name = title(p); gw.secrets = p === 'sandbox' ? { webhook_secret: randomSecret() } : { api_key: '', webhook_secret: '' } }
const gwValid = computed(() => gw.name.trim() && Object.entries(RULES[gw.provider]).every(([k, re]) => re.test(gw.secrets[k] ?? '')))
async function submitGateway() {
  if (await run('gw', () => api.createGateway({ name: gw.name.trim(), provider: gw.provider, secrets: { ...gw.secrets }, is_default: gw.is_default }), `Gateway “${gw.name}” added. Secrets are stored encrypted.`)) {
    gw.open = false; await reload()
  }
}
const rot = reactive({ open: false, g: null as Gateway | null, secrets: {} as Record<string, string> })
function openRotate(g: Gateway) { rot.g = g; rot.secrets = Object.fromEntries(Object.keys(RULES[g.provider] ?? { webhook_secret: /./ }).map((k) => [k, g.provider === 'sandbox' ? randomSecret() : ''])); rot.open = true }
const rotValid = computed(() => !!rot.g && Object.entries(RULES[rot.g.provider] ?? {}).every(([k, re]) => re.test(rot.secrets[k] ?? '')))
async function submitRotate() {
  if (!rot.g) return
  const id = rot.g.id
  if (await run('rot', () => api.rotateGatewaySecrets(id, { ...rot.secrets }), 'Secrets replaced.')) { rot.open = false; await reload() }
}
const hookUrl = (g: Gateway) => `${location.origin}${BASE}/webhooks/payments/${g.id}`
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Settings</h1><p class="muted">Workspace limits and payment providers.</p></div>
    </div>
    <LoadState :loading="loading" :error="error" :ready="!!me" @retry="reload">
      <div class="grid">
        <UiCard title="Your account">
          <div class="card-body">
            <div class="kv"><span>Signed in as</span><span>{{ session.email }}</span></div>
            <div class="kv"><span>Workspace</span><span>{{ session.tenant }}</span></div>
            <div class="kv"><span>Access</span><UiBadge :tone="me?.is_tenant_admin ? 'brand' : 'neutral'">{{ me?.is_tenant_admin ? 'Tenant admin' : 'Member' }}</UiBadge></div>
            <div v-if="me && !me.is_tenant_admin" class="kv"><span>Permissions</span><span class="chips perms"><span v-for="p in me.permissions" :key="p" class="chip mono">{{ p }}</span></span></div>
          </div>
        </UiCard>

        <UiCard title="API rate limit" caption="Requests per minute for this workspace, shared by all users and API keys.">
          <form class="card-body" @submit.prevent="saveRate">
            <UiInput v-model="rate.value" label="Requests per minute" inputmode="numeric" placeholder="e.g. 1200" :disabled="!me?.is_tenant_admin"
                     :hint="me?.is_tenant_admin ? 'Cannot exceed the ceiling the platform sets for you.' : 'Only tenant admins can change this.'" />
            <p v-if="rate.saved" class="note">Now {{ rate.saved.rate_limit_per_min }}/min · platform ceiling {{ rate.saved.platform_max_per_min }}/min</p>
            <div><UiButton type="submit" variant="primary" :loading="busy === 'rate'" :disabled="!me?.is_tenant_admin || !/^\d+$/.test(rate.value) || Number(rate.value) < 1">Save limit</UiButton></div>
          </form>
        </UiCard>
      </div>

      <UiCard title="Payment gateways" caption="Used by Take payment on orders. Secrets are encrypted and never shown again.">
        <template #actions><UiButton variant="primary" icon="plus" :disabled="!!gatewayError" @click="openGateway">Add gateway</UiButton></template>
        <p v-if="gatewayError" class="note warn inset">{{ gatewayError }}</p>
        <EmptyState v-else-if="!gateways.length" icon="card" title="No payment gateway" body="Add Stripe, or the sandbox provider for testing, to take payments on orders." />
        <ul v-else class="gws">
          <li v-for="g in gateways" :key="g.id">
            <div class="gw-top">
              <div><strong>{{ g.name }}</strong> <UiBadge tone="neutral">{{ g.provider }}</UiBadge> <UiBadge v-if="g.is_default" tone="brand">Default</UiBadge></div>
              <UiButton variant="ghost" icon="key" @click="openRotate(g)">Replace secrets</UiButton>
            </div>
            <p class="caption">Added {{ dateTime(g.created_at) }} · secret version {{ g.secret_version ?? 1 }}</p>
            <div class="secret-out small"><code>{{ hookUrl(g) }}</code><UiButton variant="ghost" icon="copy" aria-label="Copy webhook URL" @click="copy(hookUrl(g))" /></div>
            <p class="caption">Point the provider's payment webhooks at this URL.</p>
          </li>
        </ul>
      </UiCard>
    </LoadState>

    <UiModal :open="gw.open" title="Add payment gateway" submit-label="Add gateway" :busy="busy === 'gw'" :submit-disabled="!gwValid" @close="gw.open = false" @submit="submitGateway">
      <UiSelect :model-value="gw.provider" label="Provider" :options="[{ value: 'sandbox', label: 'Sandbox (testing, no real money)' }, { value: 'stripe', label: 'Stripe' }]" @update:model-value="setProvider" />
      <UiInput v-model="gw.name" label="Name" required :maxlength="100" />
      <UiInput v-for="(re, k) in RULES[gw.provider]" :key="k" v-model="gw.secrets[k]" :label="title(String(k))" type="password" autocomplete="off" mono required
               :hint="HINTS[k]" :error="gw.secrets[k] && !re.test(gw.secrets[k]) ? 'Does not look like a valid value.' : ''" />
      <UiCheck v-model="gw.is_default" label="Use as the default gateway" />
      <p v-if="gw.provider === 'sandbox'" class="caption">The sandbox only works when the server allows it (development and test environments).</p>
    </UiModal>
    <UiModal :open="rot.open" :title="`Replace secrets · ${rot.g?.name ?? ''}`" submit-label="Replace secrets" :busy="busy === 'rot'" :submit-disabled="!rotValid" @close="rot.open = false" @submit="submitRotate">
      <UiInput v-for="(re, k) in RULES[rot.g?.provider ?? 'sandbox']" :key="k" v-model="rot.secrets[k]" :label="title(String(k))" type="password" autocomplete="off" mono required
               :hint="HINTS[k]" :error="rot.secrets[k] && !re.test(rot.secrets[k]) ? 'Does not look like a valid value.' : ''" />
    </UiModal>
  </div>
</template>

<style scoped>
.grid { display: grid; gap: var(--space-24); grid-template-columns: repeat(2, minmax(0, 1fr)); }
.perms { justify-content: flex-end; }
.inset { margin: 0 24px 20px; }
.gws { list-style: none; margin: 0; padding: 0; }
.gws li { padding: 14px 24px; border-top: 1px solid var(--border-default); display: grid; gap: 6px; }
.gw-top { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.secret-out.small { padding: 4px 4px 4px 12px; }
.secret-out.small code { font-size: 12px; }
@media (max-width: 1023px) { .grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 767px) { .grid { gap: var(--space-16); } .gws li { padding: 12px 16px; } .inset { margin: 0 16px 16px; } }
</style>
