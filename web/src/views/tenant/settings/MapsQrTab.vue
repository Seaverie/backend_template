<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { api } from '@/api/client'
import { can, hasModule } from '@/api/me'
import { useBusy, useLoader } from '@/composables'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiSelect from '@/components/UiSelect.vue'

const showMaps = hasModule('maps') && can('maps', 'manage')
const showQr = hasModule('qr_codes') && can('settings', 'manage')
const maps = reactive({ provider: 'nominatim', key: '', key_set: false, language: '' })
const qr = reactive({ id: '', name: '' })
const { loading, error, reload } = useLoader(async () => {
  if (showMaps) { const m = await api.get('/maps/settings'); Object.assign(maps, { provider: m.provider, key_set: m.google_key_set, language: m.language ?? '', key: '' }) }
  if (showQr) { const q = await api.get('/qr/settings'); Object.assign(qr, { id: q.promptpay_id ?? '', name: q.promptpay_name ?? '' }) }
})
const { busy, run } = useBusy()
async function saveMaps() {
  await run('maps', async () => {
    const m = await api.put('/maps/settings', { provider: maps.provider, google_api_key: maps.key ? maps.key.trim() : undefined, language: maps.language || undefined })
    Object.assign(maps, { provider: m.provider, key_set: m.google_key_set, key: '' })
  }, 'Map settings saved.')
}
async function removeKey() { await run('maps', async () => { const m = await api.put('/maps/settings', { provider: 'nominatim', google_api_key: '' }); Object.assign(maps, { provider: m.provider, key_set: m.google_key_set }) }, 'Google key removed.') }
const test = reactive({ q: '', results: [] as any[] })
async function tryGeocode() { await run('geo', async () => { test.results = (await api.get('/maps/geocode', { q: test.q, limit: 3 })).items }) }
async function saveQr() { await run('qr', () => api.put('/qr/settings', { promptpay_id: qr.id.trim(), promptpay_name: qr.name.trim() }), 'PromptPay saved.') }
const qrText = ref('')
const qrImg = ref('')
async function makeQr() {
  await run('qrimg', async () => { if (qrImg.value) URL.revokeObjectURL(qrImg.value); qrImg.value = await api.blobUrl('/qr', { data: qrText.value, format: 'png', size: 320 }) })
}
onBeforeUnmount(() => { if (qrImg.value) URL.revokeObjectURL(qrImg.value) })
</script>

<template>
  <LoadState :loading="loading" :error="error" :ready="true" @retry="reload">
    <UiCard v-if="showMaps" title="Maps and addresses" caption="Turns addresses into map pins for deliveries.">
      <template #actions><UiBadge :tone="maps.provider === 'google' ? 'brand' : 'neutral'">{{ maps.provider === 'google' ? 'Google' : 'OpenStreetMap' }}</UiBadge></template>
      <form class="card-body" @submit.prevent="saveMaps">
        <UiSelect v-model="maps.provider" label="Provider" :options="[{ value: 'nominatim', label: 'OpenStreetMap (free, light use)' }, { value: 'google', label: 'Google Maps (your own API key)' }]" />
        <UiInput v-if="maps.provider === 'google'" v-model="maps.key" label="Google Maps API key" type="password" mono autocomplete="off" :placeholder="maps.key_set ? 'Saved – leave empty to keep' : 'AIza…'" hint="Enable the Geocoding API for this key in Google Cloud." />
        <UiSelect v-model="maps.language" label="Result language" :options="[{ value: '', label: 'Provider default' }, { value: 'th', label: 'Thai' }, { value: 'en', label: 'English' }]" />
        <div class="acts"><UiButton type="submit" variant="primary" :loading="busy === 'maps'">Save</UiButton><UiButton v-if="maps.key_set" variant="ghost" @click="removeKey">Remove Google key</UiButton></div>
        <div class="row"><UiInput v-model="test.q" label="Try an address" placeholder="Siam Paragon" /><UiButton :loading="busy === 'geo'" :disabled="test.q.trim().length < 3" @click="tryGeocode">Find</UiButton></div>
        <p v-for="(r, i) in test.results" :key="i" class="caption">{{ r.label }} · {{ r.lat }}, {{ r.lng }}</p>
      </form>
    </UiCard>
    <UiCard v-if="showQr" title="PromptPay" caption="Show a PromptPay QR code on Thai baht orders so customers can pay from any banking app.">
      <form class="card-body" @submit.prevent="saveQr">
        <div class="grid2">
          <UiInput v-model="qr.id" label="PromptPay ID" placeholder="Phone (0812345678), national ID or tax ID" hint="Leave empty to switch PromptPay off." />
          <UiInput v-model="qr.name" label="Account name (shown to staff)" />
        </div>
        <div><UiButton type="submit" variant="primary" :loading="busy === 'qr'">Save</UiButton></div>
      </form>
      <div class="card-body sep">
        <p class="section-label">Make a QR code</p>
        <div class="row"><UiInput v-model="qrText" label="Text or link" placeholder="https://…" /><UiButton :loading="busy === 'qrimg'" :disabled="!qrText.trim()" @click="makeQr">Make</UiButton></div>
        <img v-if="qrImg" :src="qrImg" alt="QR code" width="200" height="200" class="qr" />
      </div>
    </UiCard>
  </LoadState>
</template>

<style scoped>
.acts { display: flex; gap: 8px; flex-wrap: wrap; }
.row { display: flex; gap: 8px; align-items: flex-end; flex-wrap: wrap; }
.row > :first-child { flex: 1 1 220px; }
.sep { border-top: 1px solid var(--border-default); padding-top: 16px; }
.qr { border: 1px solid var(--border-default); border-radius: var(--radius-md); }
</style>
