<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import { can, hasModule, me } from '@/api/me'
import { opt, useBusy, useList, useLoader } from '@/composables'
import { dateTime, title, when } from '@/format'
import { copy } from '@/ui'
import DataTable from '@/components/DataTable.vue'
import DetailPanel from '@/components/DetailPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiPager from '@/components/UiPager.vue'
import UiSelect from '@/components/UiSelect.vue'

const list = useList((q) => api.get('/deliveries', q), { status: '', mine: false })
const users = ref<any[]>([])
useLoader(async () => { if (can('users')) users.value = (await api.get('/users')).items })
const userName = (id: string | null) => (id ? (users.value.find((u) => u.id === id)?.full_name || users.value.find((u) => u.id === id)?.email || 'Assigned') : 'Nobody yet')
const sel = ref<any>(null)
const { busy, run } = useBusy()
async function open(d: any) { sel.value = await api.get(`/deliveries/${d.id}`) }
async function patch(body: Record<string, unknown>, msg: string) {
  await run('patch', async () => { sel.value = await api.patch(`/deliveries/${sel.value.id}`, body); await list.reload() }, msg)
}
const next: Record<string, string[]> = { assigned: ['picked_up', 'failed', 'cancelled'], picked_up: ['in_transit', 'delivered', 'failed'], in_transit: ['delivered', 'failed'], failed: ['assigned'] }
const trackUrl = computed(() => sel.value && me.tenant ? `${location.origin}/api/v1/public/shop/${me.tenant.tenant.slug}/deliveries/${sel.value.id}?token=${encodeURIComponent(sel.value.tracking_token)}` : '')
const osm = (p: { lat: number; lng: number }) => `https://www.openstreetmap.org/?mlat=${p.lat}&mlon=${p.lng}#map=17/${p.lat}/${p.lng}`

const dest = reactive({ open: false, label: '', note: '', lat: null as number | null, lng: null as number | null, results: [] as any[] })
function startDest() { Object.assign(dest, { open: true, label: sel.value.destination_label ?? '', note: sel.value.note ?? '', lat: sel.value.destination?.lat ?? null, lng: sel.value.destination?.lng ?? null, results: [] }) }
async function geocode() {
  await run('geo', async () => { dest.results = (await api.get('/maps/geocode', { q: dest.label, limit: 5 })).items ?? [] })
}
async function saveDest() {
  await patch({ destination_label: opt(dest.label), note: dest.note, destination_lat: dest.lat, destination_lng: dest.lng }, 'Destination saved.')
  dest.open = false
}
const driver = ref('')
</script>

<template>
  <div class="page">
    <PageHeader title="Deliveries" caption="Orders on their way, who is driving and where they are." />
    <div class="split">
      <UiCard>
        <div class="toolbar" style="padding-top: 16px">
          <select v-model="list.filters.status" class="search-input" aria-label="Status">
            <option value="">All deliveries</option>
            <option v-for="s in ['assigned', 'picked_up', 'in_transit', 'delivered', 'failed', 'cancelled']" :key="s" :value="s">{{ title(s) }}</option>
          </select>
          <label class="mine"><input v-model="list.filters.mine" type="checkbox" /> Only mine</label>
        </div>
        <LoadState :loading="list.loading.value" :error="list.error.value" :ready="list.items.value.length > 0" @retry="list.reload">
          <DataTable :columns="[{ key: 'order', label: 'Order', primary: true }, { key: 'driver', label: 'Driver' }, { key: 'status', label: 'Status' }]"
                     :rows="list.items.value" row-key="id" clickable :selected="sel?.id" empty="No deliveries. Create one from an order." @select="open">
            <template #cell-order="{ row }"><strong>#{{ row.order_number }}</strong><span class="caption">{{ row.destination_label || 'No address' }} · {{ when(row.created_at) }}</span></template>
            <template #cell-driver="{ row }">{{ userName(row.driver_user_id) }}</template>
            <template #cell-status="{ row }"><StatusBadge :status="row.status" /></template>
          </DataTable>
          <UiPager v-model="list.offset.value" :total="list.total.value" :limit="list.limit" :count="list.items.value.length" />
        </LoadState>
      </UiCard>
      <DetailPanel :open="!!sel" label="Delivery" @close="sel = null">
        <EmptyState v-if="!sel" icon="truck" title="Pick a delivery" body="Its driver, status and last known location show here." />
        <div v-else class="detail">
          <div><h2>Order #{{ sel.order_number }}</h2><p class="caption"><RouterLink :to="`/orders/${sel.order_id}`">Open order</RouterLink> · created {{ dateTime(sel.created_at) }}</p></div>
          <StatusBadge :status="sel.status" />
          <div class="kv"><span>Driver</span><span>{{ userName(sel.driver_user_id) }}</span></div>
          <div class="kv"><span>Destination</span><span>{{ sel.destination_label || '—' }}</span></div>
          <div v-if="sel.destination" class="kv"><span>Pin</span><a :href="osm(sel.destination)" target="_blank" rel="noopener noreferrer">{{ sel.destination.lat.toFixed(5) }}, {{ sel.destination.lng.toFixed(5) }}</a></div>
          <div class="kv"><span>Last seen</span><span v-if="sel.last_location"><a :href="osm(sel.last_location)" target="_blank" rel="noopener noreferrer">{{ when(sel.last_location.at) }}</a></span><span v-else>No location yet</span></div>
          <p v-if="sel.note" class="note">{{ sel.note }}</p>
          <template v-if="can('deliveries', 'update')">
            <div class="acts">
              <UiButton v-for="s in next[sel.status] ?? []" :key="s" :variant="s === 'delivered' ? 'primary' : 'secondary'" :loading="busy === 'patch'" @click="patch({ status: s }, `Marked ${title(s)}.`)">{{ title(s) }}</UiButton>
            </div>
            <div v-if="users.length" class="assign">
              <UiSelect v-model="driver" label="Driver" :options="[{ value: '', label: 'Choose a team member' }, ...users.filter((u) => u.is_active).map((u) => ({ value: u.id, label: u.full_name || u.email }))]" />
              <UiButton :disabled="!driver" @click="patch({ driver_user_id: driver }, 'Driver assigned.')">Assign</UiButton>
              <UiButton v-if="sel.driver_user_id" variant="ghost" @click="patch({ unassign_driver: true }, 'Driver removed.')">Unassign</UiButton>
            </div>
            <UiButton icon="map" @click="startDest">Edit destination</UiButton>
          </template>
          <p class="section-label">Customer tracking link</p>
          <div class="secret-out"><code>{{ trackUrl }}</code><UiButton variant="ghost" icon="copy" aria-label="Copy link" @click="copy(trackUrl)" /></div>
          <p class="caption">Returns status and the driver's last position as JSON, for your shop or app to show on a map.</p>
        </div>
      </DetailPanel>
    </div>

    <UiModal :open="dest.open" title="Destination" submit-label="Save" :busy="busy === 'patch'" @close="dest.open = false" @submit="saveDest">
      <UiInput v-model="dest.label" label="Address" multiline :rows="2" />
      <UiButton v-if="hasModule('maps') && dest.label.trim()" icon="search" :loading="busy === 'geo'" @click="geocode">Find on the map</UiButton>
      <div v-for="(r, i) in dest.results" :key="i" class="hit" :class="{ on: dest.lat === r.lat && dest.lng === r.lng }" @click="Object.assign(dest, { lat: r.lat, lng: r.lng, label: r.label ?? r.display_name ?? dest.label })">
        <strong>{{ r.label ?? r.display_name }}</strong><span class="caption">{{ r.lat }}, {{ r.lng }}</span>
      </div>
      <p v-if="dest.lat !== null" class="caption">Pin: {{ dest.lat }}, {{ dest.lng }}</p>
      <UiInput v-model="dest.note" label="Note for the driver" multiline :rows="2" />
    </UiModal>
  </div>
</template>

<style scoped>
.detail { padding: 24px; display: grid; gap: 10px; }
.acts { display: flex; gap: 8px; flex-wrap: wrap; }
.assign { display: flex; gap: 8px; align-items: flex-end; flex-wrap: wrap; }
.assign > :first-child { flex: 1 1 180px; }
.mine { display: flex; gap: 6px; align-items: center; font-size: 13px; }
.hit { display: flex; flex-direction: column; padding: 8px 10px; border: 1px solid var(--border-default); border-radius: var(--radius-md); cursor: pointer; }
.hit.on { border-color: var(--brand-primary); background: var(--brand-soft); }
</style>
