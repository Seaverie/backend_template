<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api } from '@/api/client'
import type { Webhook, WebhookDelivery } from '@/api/types'
import DataTable, { type Column } from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import { useBusy, useLoader } from '@/composables'
import { deliveryTone, when } from '@/format'
import { ask, copy, errText, toast } from '@/ui'

// Mirrors EVENT_TYPES in app/services/outbox.py.
const EVENTS = ['order.created', 'order.status_changed', 'payment.succeeded', 'payment.failed', 'payment.refunded', 'rma.status_changed',
  'data.created', 'data.updated', 'data.deleted', 'import.completed', 'import.failed', 'delivery.ping']

const hooks = ref<Webhook[]>([])
const selected = ref<Webhook | null>(null)
const deliveries = ref<WebhookDelivery[]>([])
const secret = ref('')
const { busy, run } = useBusy()

const { loading, error, reload } = useLoader(async () => {
  hooks.value = (await api.webhooks()).items
  const keep = hooks.value.find((h) => h.id === selected.value?.id) ?? hooks.value[0]
  if (keep) await pick(keep); else { selected.value = null; deliveries.value = [] }
})
async function pick(h: Webhook) {
  if (selected.value?.id !== h.id) secret.value = ''
  selected.value = h
  try { deliveries.value = (await api.deliveries(h.id)).items } catch (e) { deliveries.value = []; toast(errText(e), 'err') }
}
async function refresh() { if (selected.value) await pick(selected.value) }
async function redeliver(d: WebhookDelivery) { if (await run(`re-${d.id}`, () => api.redeliver(d.id), 'Queued for redelivery.')) await refresh() }
async function rotate() {
  if (!selected.value) return
  const h = selected.value
  if (!(await ask({ title: 'Rotate the signing secret?', body: 'Requests are signed with the new secret straight away. Update the receiving endpoint at the same time.', confirmLabel: 'Rotate secret' }))) return
  await run('rotate', async () => { secret.value = (await api.rotateSecret(h.id)).signing_secret }, 'Secret rotated. Copy it now.')
  await reload()
}
async function setActive(h: Webhook, on: boolean) {
  if (await run(`act-${h.id}`, () => api.updateWebhook(h.id, { is_active: on }), on ? 'Endpoint enabled.' : 'Endpoint disabled.')) await reload()
}
async function remove(h: Webhook) {
  if (!(await ask({ title: 'Delete this endpoint?', body: `${h.url}\n\nPending deliveries to it are dropped.`, confirmLabel: 'Delete endpoint', danger: true }))) return
  if (await run(`del-${h.id}`, () => api.deleteWebhook(h.id), 'Endpoint deleted.')) { selected.value = null; await reload() }
}

const form = reactive({ open: false, id: '', url: '', description: '', events: [] as string[], secret: '' })
function openCreate() { Object.assign(form, { open: true, id: '', url: 'https://', description: '', events: ['order.created'], secret: '' }) }
function openEdit(h: Webhook) { Object.assign(form, { open: true, id: h.id, url: h.url, description: h.description ?? '', events: [...h.event_types], secret: '' }) }
function toggle(e: string) { const i = form.events.indexOf(e); if (i >= 0) form.events.splice(i, 1); else form.events.push(e) }
const urlOk = (u: string) => /^https:\/\/[^\s/]+\.[^\s]+$/.test(u)
async function submit() {
  if (form.id) {
    const id = form.id
    if (await run('save', () => api.updateWebhook(id, { url: form.url.trim(), event_types: form.events }), 'Endpoint updated.')) { form.open = false; await reload() }
  } else {
    let h: Webhook | undefined
    if (await run('save', async () => { h = await api.createWebhook(form.url.trim(), form.events, form.description.trim()) }) && h) {
      form.secret = h.signing_secret ?? ''; await reload(); const created = hooks.value.find((x) => x.id === h!.id); if (created) await pick(created)
    }
  }
}

const hookCols: Column[] = [
  { key: 'url', label: 'Endpoint', primary: true }, { key: 'events', label: 'Events' }, { key: 'status', label: 'Status' },
  { key: 'fails', label: 'Failures in a row', align: 'right', hideOnMobile: true }, { key: 'act', label: '', align: 'right' },
]
const delCols: Column[] = [
  { key: 'event', label: 'Event', primary: true }, { key: 'status', label: 'Status' }, { key: 'attempts', label: 'Attempts', align: 'right' },
  { key: 'code', label: 'HTTP', align: 'right' }, { key: 'action', label: '', align: 'right' },
]
const deliveryLabel = (d: WebhookDelivery) => (d.status === 'succeeded' ? 'Delivered' : d.status === 'pending' ? (d.attempts ? 'Retrying' : 'Queued') : 'Dead')
const inMinutes = (t: string | null) => (t ? Math.max(0, Math.round((new Date(t).getTime() - Date.now()) / 60_000)) : 0)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Webhooks</h1><p class="muted">Get notified when things happen. Requests are signed with HMAC-SHA256 and retried with backoff (at-least-once).</p></div>
      <div class="actions"><UiButton variant="primary" icon="plus" @click="openCreate">Add endpoint</UiButton></div>
    </div>

    <LoadState :loading="loading" :error="error" :ready="!!hooks.length" @retry="reload">
      <UiCard title="Endpoints" :caption="`${hooks.length} endpoints · HTTPS only · private and internal addresses are blocked`">
        <EmptyState v-if="!hooks.length" icon="webhook" title="No endpoints yet" body="Add an HTTPS URL and choose which events it should receive.">
          <UiButton variant="primary" icon="plus" @click="openCreate">Add endpoint</UiButton>
        </EmptyState>
        <DataTable v-else :columns="hookCols" :rows="hooks" row-key="id" :selected="selected?.id" clickable @select="pick">
          <template #cell-url="{ row }"><span class="url">{{ row.url }}</span><div class="caption">{{ row.description }}</div></template>
          <template #cell-events="{ row }"><span class="chips"><span v-for="e in row.event_types" :key="e" class="chip">{{ e }}</span></span></template>
          <template #cell-status="{ row }">
            <UiBadge :tone="row.is_active ? 'success' : 'danger'">{{ row.is_active ? 'Active' : 'Disabled' }}</UiBadge>
            <div v-if="row.disabled_reason" class="caption">{{ row.disabled_reason }}</div>
          </template>
          <template #cell-fails="{ row }">{{ row.consecutive_failures }}</template>
          <template #cell-act="{ row }">
            <div class="row-actions" @click.stop>
              <UiButton variant="ghost" :loading="busy === `act-${row.id}`" @click="setActive(row, !row.is_active)">{{ row.is_active ? 'Disable' : 'Enable' }}</UiButton>
              <UiButton variant="ghost" icon="edit" :aria-label="`Edit ${row.url}`" @click="openEdit(row)" />
              <UiButton variant="ghost" icon="trash" :aria-label="`Delete ${row.url}`" :loading="busy === `del-${row.id}`" @click="remove(row)" />
            </div>
          </template>
        </DataTable>
      </UiCard>

      <div v-if="selected" class="split">
        <UiCard :title="`Deliveries · ${selected.description || selected.url}`" caption="Newest first">
          <template #actions><UiButton variant="ghost" icon="refresh" @click="refresh">Refresh</UiButton></template>
          <DataTable :columns="delCols" :rows="deliveries" row-key="id" empty="No deliveries yet. They appear when a subscribed event happens.">
            <template #cell-event="{ row }"><span>{{ row.event_type }}</span><div class="caption mono">evt {{ row.event_id.slice(0, 8) }} · {{ when(row.created_at) }}</div></template>
            <template #cell-status="{ row }">
              <UiBadge :tone="row.status === 'pending' && !row.attempts ? 'neutral' : deliveryTone[row.status]">{{ deliveryLabel(row) }}</UiBadge>
              <div v-if="row.last_error && row.status !== 'succeeded'" class="caption">{{ row.last_error }}</div>
            </template>
            <template #cell-code="{ row }">{{ row.last_status_code ?? '—' }}</template>
            <template #cell-action="{ row }">
              <UiButton v-if="row.status === 'dead'" variant="ghost" :loading="busy === `re-${row.id}`" @click="redeliver(row)">Redeliver</UiButton>
              <span v-else-if="row.status === 'pending' && row.next_attempt_at" class="caption">next try in {{ inMinutes(row.next_attempt_at) }} min</span>
            </template>
          </DataTable>
        </UiCard>

        <UiCard title="Signing secret" :caption="`Version ${selected.secret_version} · stored encrypted, shown only when created or rotated`">
          <div class="card-body">
            <div v-if="secret" class="secret-out"><code>{{ secret }}</code><UiButton icon="copy" @click="copy(secret)">Copy</UiButton></div>
            <p v-if="secret" class="caption warn">Copy it now: it will not be shown again.</p>
            <UiButton icon="refresh" block :loading="busy === 'rotate'" @click="rotate">Rotate secret</UiButton>
          </div>
          <div class="verify">
            <p class="label">Verify each request</p>
            <pre class="code">X-TenantCore-Event-Id: 7c1e…
X-TenantCore-Event-Type: order.created
X-TenantCore-Signature: t=…,v1=…

v1 = HMAC_SHA256(secret, f"{t}.{body}")
reject stale t (e.g. older than 5 min)
dedupe on Event-Id</pre>
          </div>
        </UiCard>
      </div>
    </LoadState>

    <UiModal :open="form.open" :title="form.secret ? 'Endpoint created' : form.id ? 'Edit endpoint' : 'Add endpoint'" :submit-label="form.secret ? undefined : form.id ? 'Save changes' : 'Add endpoint'"
             :busy="busy === 'save'" :submit-disabled="!urlOk(form.url) || !form.events.length" @close="form.open = false" @submit="submit">
      <template v-if="!form.secret">
        <UiInput v-model="form.url" label="URL" type="url" required mono :error="form.url.length > 8 && !urlOk(form.url) ? 'Must be a public https:// URL.' : ''" />
        <UiInput v-if="!form.id" v-model="form.description" label="Description" :maxlength="200" placeholder="e.g. ERP order sync" />
        <p class="section-label">Events</p>
        <div class="events"><UiCheck v-for="e in EVENTS" :key="e" :model-value="form.events.includes(e)" :label="e" @update:model-value="toggle(e)" /></div>
      </template>
      <template v-else>
        <p class="note warn">Copy the signing secret now. It is not shown again; rotate it if it is lost.</p>
        <div class="secret-out"><code>{{ form.secret }}</code><UiButton icon="copy" @click="copy(form.secret)">Copy</UiButton></div>
      </template>
    </UiModal>
  </div>
</template>

<style scoped>
.url { overflow-wrap: anywhere; }
.warn { color: var(--warning); }
.verify { padding: 20px 24px; border-top: 1px solid var(--border-default); display: grid; gap: 10px; }
.code { margin: 0; background: var(--bg-sidebar); color: var(--text-sidebar); border-radius: var(--radius-md); padding: 12px 14px; font-family: var(--font-mono); font-size: 12px; line-height: 18px; overflow-x: auto; }
.events { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 16px; }
@media (max-width: 767px) { .verify { padding: 16px; } }
@media (max-width: 479px) { .events { grid-template-columns: minmax(0, 1fr); } }
</style>
