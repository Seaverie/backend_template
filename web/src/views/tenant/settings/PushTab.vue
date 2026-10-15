<script setup lang="ts">
import { reactive } from 'vue'
import { api } from '@/api/client'
import { useBusy, useLoader } from '@/composables'
import { ask } from '@/ui'
import JsonField from '@/components/JsonField.vue'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'

const s = reactive({ loaded: false, configured: false, project_id: '', client_email: '', orders: true, deliveries: true, json: '' })
const { loading, error, reload } = useLoader(async () => {
  const r = await api.get('/notifications/settings')
  Object.assign(s, { loaded: true, configured: r.configured, project_id: r.project_id ?? '', client_email: r.client_email ?? '', orders: r.notify_order_updates, deliveries: r.notify_delivery_updates, json: '' })
})
const { busy, run } = useBusy()
async function save() {
  await run('save', async () => {
    await api.put('/notifications/settings', { service_account_json: s.json.trim() || undefined, notify_order_updates: s.orders, notify_delivery_updates: s.deliveries })
    await reload()
  }, 'Push settings saved.')
}
async function remove() {
  if (!await ask({ title: 'Remove the Firebase service account?', body: 'Push notifications stop until you add one again.', confirmLabel: 'Remove', danger: true })) return
  await run('save', async () => { await api.put('/notifications/settings', { service_account_json: '' }); await reload() }, 'Removed.')
}
</script>

<template>
  <LoadState :loading="loading" :error="error" :ready="s.loaded" @retry="reload">
    <UiCard title="Firebase Cloud Messaging" caption="Sends notifications to your Android and iOS app and to the web.">
      <template #actions><UiBadge :tone="s.configured ? 'success' : 'warning'">{{ s.configured ? 'Connected' : 'Not set up' }}</UiBadge></template>
      <form class="card-body" @submit.prevent="save">
        <template v-if="s.configured">
          <div class="kv"><span>Firebase project</span><span class="mono">{{ s.project_id }}</span></div>
          <div class="kv"><span>Service account</span><span class="mono">{{ s.client_email }}</span></div>
        </template>
        <JsonField v-model="s.json" :label="s.configured ? 'Replace the service account key (optional)' : 'Service account key (JSON)'" :rows="6" allow-empty
                   hint="Firebase console → Project settings → Service accounts → Generate new private key. Paste the whole file. It is stored encrypted and never shown again." />
        <UiCheck v-model="s.orders" toggle label="Tell signed-in customers when their order changes status" />
        <UiCheck v-model="s.deliveries" toggle label="Tell customers when their delivery is on the way or delivered" />
        <div class="acts"><UiButton type="submit" variant="primary" :loading="busy === 'save'">Save</UiButton><UiButton v-if="s.configured" variant="ghost" @click="remove">Remove</UiButton></div>
      </form>
    </UiCard>
  </LoadState>
</template>

<style scoped>.acts { display: flex; gap: 8px; flex-wrap: wrap; } .kv .mono { overflow-wrap: anywhere; }</style>
