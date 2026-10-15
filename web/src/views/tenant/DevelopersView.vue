<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { can, hasModule } from '@/api/me'
import { num, opt, useBusy, useLoader } from '@/composables'
import { dateTime, when } from '@/format'
import { ask, copy } from '@/ui'
import DataTable from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiSelect from '@/components/UiSelect.vue'
import UiTabs from '@/components/UiTabs.vue'

const props = defineProps<{ tab?: string }>()
const router = useRouter()
const tabs = computed(() => [...(can('api_keys') ? [{ key: 'keys', label: 'API keys' }] : []), ...(hasModule('webhooks') && can('webhooks') ? [{ key: 'webhooks', label: 'Webhooks' }] : [])])
const tab = ref(props.tab && tabs.value.some((t) => t.key === props.tab) ? props.tab : tabs.value[0]?.key ?? 'keys')
watch(tab, (t) => router.replace(`/developers/${t}`))
if (tabs.value.length && props.tab !== tab.value) router.replace(`/developers/${tab.value}`)
const { busy, run } = useBusy()
const apiBase = `${location.origin}/api/v1`

// ---- API keys -----------------------------------------------------------------------------------
const keys = ref<any[]>([])
const roles = ref<any[]>([])
const keyLoad = useLoader(async () => {
  if (!can('api_keys')) return
  const [k, r] = await Promise.all([api.get('/api-keys'), can('roles') ? api.get('/roles') : Promise.resolve({ items: [] })])
  keys.value = k.items; roles.value = r.items
})
const kf = reactive({ open: false, name: '', role: '', days: '', created: '' })
async function createKey() {
  await run('key', async () => {
    const r = await api.post('/api-keys', { name: kf.name.trim(), role_id: kf.role || null, expires_in_days: num(kf.days) })
    kf.created = r.key; await keyLoad.reload()
  })
}
async function revoke(k: any) {
  if (!await ask({ title: `Revoke “${k.name}”?`, body: 'Anything using this key stops working immediately.', confirmLabel: 'Revoke', danger: true })) return
  await run('rev', () => api.del(`/api-keys/${k.id}`), 'Key revoked.'); keyLoad.reload()
}

// ---- Webhooks -----------------------------------------------------------------------------------
const hooks = ref<any[]>([])
const eventTypes = ref<{ items: string[]; wildcards: string[] }>({ items: [], wildcards: [] })
const hookLoad = useLoader(async () => {
  if (!hasModule('webhooks') || !can('webhooks')) return
  const [h, e] = await Promise.all([api.get('/webhooks'), api.get('/webhooks/event-types')])
  hooks.value = h.items; eventTypes.value = e
})
const wf = reactive({ open: false, id: '', url: '', description: '', events: [] as string[], active: true, secret: '' })
function editHook(h?: any) { Object.assign(wf, { open: true, id: h?.id ?? '', url: h?.url ?? 'https://', description: h?.description ?? '', events: [...(h?.events ?? ['order.*'])], active: h?.is_active ?? true, secret: '' }) }
function toggleEvent(e: string) { const i = wf.events.indexOf(e); if (i >= 0) wf.events.splice(i, 1); else wf.events.push(e) }
async function saveHook() {
  await run('hook', async () => {
    if (wf.id) { await api.patch(`/webhooks/${wf.id}`, { url: wf.url.trim(), description: opt(wf.description), events: wf.events, is_active: wf.active || undefined }); wf.open = false }
    else { const r = await api.post('/webhooks', { url: wf.url.trim(), description: opt(wf.description), events: wf.events }); wf.secret = r.secret; wf.id = r.id }
    await hookLoad.reload()
  }, wf.id ? 'Webhook saved.' : undefined)
}
async function deleteHook() {
  if (!await ask({ title: 'Delete this webhook?', confirmLabel: 'Delete', danger: true })) return
  if (await run('hook', () => api.del(`/webhooks/${wf.id}`), 'Webhook deleted.')) { wf.open = false; hookLoad.reload() }
}
async function rotate() {
  if (!await ask({ title: 'Make a new signing secret?', body: 'The old secret stops working at once. Update your receiver with the new one.', confirmLabel: 'Rotate' })) return
  await run('rot', async () => { wf.secret = (await api.post(`/webhooks/${wf.id}/rotate-secret`)).secret })
}
async function test(h: any) { await run('test:' + h.id, () => api.post(`/webhooks/${h.id}/test`), 'Test event queued. Check the deliveries in a moment.') }
const dl = reactive({ open: false, hook: null as any, items: [] as any[], sel: null as any })
async function deliveries(h: any) { Object.assign(dl, { open: true, hook: h, items: (await api.get(`/webhooks/${h.id}/deliveries`)).items, sel: null }) }
async function redeliver(d: any) { await run('re:' + d.id, () => api.post(`/webhooks/deliveries/${d.id}/redeliver`), 'Queued again.'); dl.items = (await api.get(`/webhooks/${dl.hook.id}/deliveries`)).items }
</script>

<template>
  <div class="page">
    <PageHeader title="Developers" caption="Connect your website, app or other systems to this workspace." />
    <UiTabs v-if="tabs.length > 1" v-model="tab" :tabs="tabs" />
    <EmptyState v-if="!tabs.length" icon="lock" title="Nothing here for your role" />

    <template v-if="tab === 'keys' && can('api_keys')">
      <UiCard title="API keys" :caption="`Send as “Authorization: Bearer <key>” to ${apiBase}. A key can do what its role allows, nothing more.`">
        <template #actions><UiButton v-if="can('api_keys', 'manage')" icon="plus" variant="primary" @click="Object.assign(kf, { open: true, name: '', role: '', days: '', created: '' })">New key</UiButton></template>
        <LoadState :loading="keyLoad.loading.value" :error="keyLoad.error.value" :ready="keys.length > 0" @retry="keyLoad.reload">
          <DataTable :columns="[{ key: 'name', label: 'Key', primary: true }, { key: 'role', label: 'Role' }, { key: 'used', label: 'Last used' }, { key: 'acts', label: '' }]" :rows="keys" row-key="id" empty="No keys yet.">
            <template #cell-name="{ row }"><strong>{{ row.name }}</strong><span class="caption mono">{{ row.hint }} · made {{ when(row.created_at) }}</span></template>
            <template #cell-role="{ row }"><span v-if="row.role_name">{{ row.role_name }}</span><UiBadge v-else tone="warning">No role – can do nothing</UiBadge></template>
            <template #cell-used="{ row }">
              <UiBadge v-if="row.revoked_at">Revoked</UiBadge>
              <UiBadge v-else-if="row.expires_at && new Date(row.expires_at) < new Date()">Expired</UiBadge>
              <span v-else>{{ when(row.last_used_at) }}<span v-if="row.expires_at" class="caption">expires {{ dateTime(row.expires_at) }}</span></span>
            </template>
            <template #cell-acts="{ row }"><div class="row-actions"><UiButton v-if="!row.revoked_at && can('api_keys', 'manage')" variant="ghost" @click="revoke(row)">Revoke</UiButton></div></template>
          </DataTable>
        </LoadState>
      </UiCard>
    </template>

    <template v-if="tab === 'webhooks' && hasModule('webhooks') && can('webhooks')">
      <UiCard title="Webhooks" caption="We POST signed JSON to your address when things happen. Retries back off for up to a day.">
        <template #actions><UiButton v-if="can('webhooks', 'manage')" icon="plus" variant="primary" @click="editHook()">Add endpoint</UiButton></template>
        <LoadState :loading="hookLoad.loading.value" :error="hookLoad.error.value" :ready="hooks.length > 0" @retry="hookLoad.reload">
          <DataTable :columns="[{ key: 'url', label: 'Endpoint', primary: true }, { key: 'state', label: 'State' }, { key: 'acts', label: '' }]" :rows="hooks" row-key="id" empty="No endpoints yet.">
            <template #cell-url="{ row }"><strong class="mono url">{{ row.url }}</strong><span class="caption">{{ row.description ?? '' }} {{ row.events.join(', ') }}</span></template>
            <template #cell-state="{ row }">
              <UiBadge v-if="row.is_active" tone="success">Active</UiBadge><UiBadge v-else tone="danger">Off</UiBadge>
              <span v-if="row.disabled_reason" class="caption">{{ row.disabled_reason }}</span>
              <span v-else-if="row.failure_streak" class="caption">{{ row.failure_streak }} failures in a row</span>
            </template>
            <template #cell-acts="{ row }">
              <div class="row-actions">
                <UiButton variant="ghost" @click="deliveries(row)">Deliveries</UiButton>
                <template v-if="can('webhooks', 'manage')">
                  <UiButton variant="ghost" :loading="busy === 'test:' + row.id" @click="test(row)">Test</UiButton>
                  <UiButton variant="ghost" icon="edit" @click="editHook(row)">Edit</UiButton>
                </template>
              </div>
            </template>
          </DataTable>
        </LoadState>
      </UiCard>
      <UiCard title="Checking the signature">
        <div class="card-body">
          <p class="muted">Each request has <code>TenantCore-Signature: t=&lt;unix time&gt;,v1=&lt;hex&gt;</code>. Compute HMAC-SHA256 of <code>&lt;t&gt;.&lt;raw body&gt;</code> with your endpoint's secret and compare; reject times older than five minutes. An event can arrive more than once: the <code>id</code> in the body stays the same, so skip ids you have already handled.</p>
        </div>
      </UiCard>
    </template>

    <UiModal :open="kf.open" :title="kf.created ? 'Copy your key now' : 'New API key'" :submit-label="kf.created ? undefined : 'Create key'" :busy="busy === 'key'" :submit-disabled="!kf.name.trim()" @close="kf.open = false" @submit="createKey">
      <template v-if="!kf.created">
        <UiInput v-model="kf.name" label="Name" placeholder="Website, POS, Zapier…" />
        <UiSelect v-model="kf.role" label="Role" hint="The key gets exactly this role's permissions. Make a narrow role for each key." :options="[{ value: '', label: 'No role (does nothing yet)' }, ...roles.map((r) => ({ value: r.id, label: r.name }))]" />
        <UiInput v-model="kf.days" label="Expires after (days)" inputmode="numeric" placeholder="Never" />
      </template>
      <template v-else>
        <p class="note warn">This is the only time the key is shown. Store it in your app's secret settings.</p>
        <div class="secret-out"><code>{{ kf.created }}</code><UiButton variant="ghost" icon="copy" aria-label="Copy key" @click="copy(kf.created)" /></div>
      </template>
    </UiModal>

    <UiModal :open="wf.open" :title="wf.secret ? 'Signing secret' : wf.id ? 'Edit webhook' : 'Add webhook endpoint'" wide :submit-label="wf.secret && !wf.open ? undefined : wf.secret ? undefined : 'Save'"
             :busy="busy === 'hook'" :submit-disabled="!wf.events.length || !wf.url.startsWith('https://')" @close="wf.open = false" @submit="saveHook">
      <template v-if="wf.secret">
        <p class="note warn">Copy the secret now; it is not shown again. Use it to check each request's signature.</p>
        <div class="secret-out"><code>{{ wf.secret }}</code><UiButton variant="ghost" icon="copy" aria-label="Copy secret" @click="copy(wf.secret)" /></div>
      </template>
      <template v-else>
        <UiInput v-model="wf.url" label="Address" type="url" mono hint="Must be https and reachable from the internet." />
        <UiInput v-model="wf.description" label="Description" />
        <div>
          <p class="label">Events</p>
          <div class="chips ev">
            <button v-for="e in eventTypes.wildcards.filter((w) => !w.includes('<'))" :key="e" type="button" class="chip pick" :class="{ on: wf.events.includes(e) }" @click="toggleEvent(e)">{{ e }}</button>
          </div>
          <div class="chips ev">
            <button v-for="e in eventTypes.items" :key="e" type="button" class="chip pick" :class="{ on: wf.events.includes(e) }" @click="toggleEvent(e)">{{ e }}</button>
          </div>
        </div>
        <UiCheck v-if="wf.id" v-model="wf.active" label="Active" hint="Switching on again resets the failure count." />
      </template>
      <template v-if="wf.id && !wf.secret" #footer-start>
        <UiButton variant="ghost" icon="trash" @click="deleteHook">Delete</UiButton>
        <UiButton variant="ghost" icon="refresh" :loading="busy === 'rot'" @click="rotate">New secret</UiButton>
      </template>
    </UiModal>

    <UiModal :open="dl.open" :title="`Deliveries · ${dl.hook?.url ?? ''}`" wide @close="dl.open = false">
      <p v-if="!dl.items.length" class="muted">Nothing sent yet.</p>
      <div v-for="d in dl.items" :key="d.id" class="dl" @click="dl.sel = dl.sel?.id === d.id ? null : d">
        <div class="grow"><strong>{{ d.event_type }}</strong><span class="caption"> · {{ when(d.created_at) }} · {{ d.attempts }} attempt{{ d.attempts === 1 ? '' : 's' }}<template v-if="d.last_status_code"> · HTTP {{ d.last_status_code }}</template><template v-if="d.duration_ms"> · {{ d.duration_ms }} ms</template></span>
          <p v-if="dl.sel?.id === d.id && (d.last_error || d.last_response)" class="mono resp">{{ d.last_error ?? d.last_response }}</p></div>
        <StatusBadge :status="d.status" />
        <UiButton v-if="can('webhooks', 'manage') && d.status !== 'pending'" variant="ghost" :loading="busy === 're:' + d.id" @click.stop="redeliver(d)">Send again</UiButton>
      </div>
    </UiModal>
  </div>
</template>

<style scoped>
.url { overflow-wrap: anywhere; font-size: 13px; }
.ev { margin-top: 8px; }
.pick { border: 1px solid var(--border-strong); background: var(--bg-surface); cursor: pointer; padding: 3px 10px; font-family: var(--font-mono); font-size: 12px; }
.pick.on { background: var(--brand-soft); border-color: var(--brand-primary); color: var(--brand-primary); }
.dl { display: flex; gap: 10px; align-items: center; padding: 10px 0; border-top: 1px solid var(--border-default); cursor: pointer; flex-wrap: wrap; }
.grow { flex: 1 1 260px; min-width: 0; }
.resp { margin-top: 6px; white-space: pre-wrap; background: var(--bg-subtle); padding: 8px; border-radius: var(--radius-sm); max-height: 160px; overflow: auto; }
code { font-family: var(--font-mono); font-size: 12px; background: var(--bg-subtle); padding: 1px 4px; border-radius: 4px; }
</style>
