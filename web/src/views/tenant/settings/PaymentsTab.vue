<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api } from '@/api/client'
import { useBusy, useLoader } from '@/composables'
import { copy } from '@/ui'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'

const gateways = ref<any[]>([])
const forms = reactive<Record<string, { enabled: boolean; pk: string; sk: string; wh: string }>>({})
const { loading, error, reload } = useLoader(async () => {
  gateways.value = (await api.get('/payments/gateways')).items
  for (const g of gateways.value) forms[g.provider] = { enabled: g.is_enabled, pk: g.publishable_key ?? '', sk: '', wh: '' }
})
const { busy, run } = useBusy()
async function save(g: any) {
  const f = forms[g.provider]
  await run(g.provider, async () => {
    await api.put(`/payments/gateways/${g.provider}`, { is_enabled: f.enabled, publishable_key: g.provider === 'stripe' ? f.pk.trim() : undefined,
      secret_key: f.sk ? f.sk.trim() : undefined, webhook_secret: f.wh ? f.wh.trim() : undefined })
    await reload()
  }, `${g.label} saved.`)
}
async function clearSecret(g: any, which: 'secret_key' | 'webhook_secret') { await run(g.provider, async () => { await api.put(`/payments/gateways/${g.provider}`, { [which]: '' }); await reload() }, 'Removed.') }
const origin = location.origin
</script>

<template>
  <LoadState :loading="loading" :error="error" :ready="gateways.length > 0" @retry="reload">
    <UiCard v-for="g in gateways" :key="g.provider" :title="g.label">
      <template #actions><UiBadge :tone="g.is_enabled ? 'success' : 'neutral'">{{ g.is_enabled ? 'On' : 'Off' }}</UiBadge></template>
      <form class="card-body" @submit.prevent="save(g)">
        <template v-if="g.provider === 'stripe'">
          <UiInput v-model="forms[g.provider].pk" label="Publishable key" mono placeholder="pk_live_…" />
          <div class="grid2">
            <UiInput v-model="forms[g.provider].sk" label="Secret key" type="password" mono autocomplete="off" :placeholder="g.secret_set ? 'Saved – leave empty to keep' : 'sk_live_…'" />
            <UiInput v-model="forms[g.provider].wh" label="Webhook signing secret" type="password" mono autocomplete="off" :placeholder="g.webhook_secret_set ? 'Saved – leave empty to keep' : 'whsec_…'" />
          </div>
          <div v-if="g.webhook_path">
            <p class="label">Webhook address for the Stripe dashboard</p>
            <div class="secret-out"><code>{{ origin + g.webhook_path }}</code><UiButton variant="ghost" icon="copy" aria-label="Copy" @click="copy(origin + g.webhook_path)" /></div>
            <p class="caption">Send the payment_intent.succeeded, payment_intent.payment_failed, payment_intent.canceled and refund.updated events. Use your public address (the Cloudflare Tunnel one), not localhost.</p>
          </div>
        </template>
        <p v-else class="caption">Pretend card payments for trying the checkout. Each payment waits until you click Succeed or Fail on the order.</p>
        <UiCheck v-model="forms[g.provider].enabled" toggle :label="`Take payments with ${g.label.split(' (')[0]}`" />
        <div class="acts">
          <UiButton type="submit" variant="primary" :loading="busy === g.provider">Save</UiButton>
          <UiButton v-if="g.secret_set" variant="ghost" @click="clearSecret(g, 'secret_key')">Remove secret key</UiButton>
          <UiButton v-if="g.webhook_secret_set" variant="ghost" @click="clearSecret(g, 'webhook_secret')">Remove webhook secret</UiButton>
        </div>
      </form>
    </UiCard>
  </LoadState>
</template>

<style scoped>.acts { display: flex; gap: 8px; flex-wrap: wrap; }</style>
