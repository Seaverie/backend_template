<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { Webhook, WebhookDelivery } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import DataTable, { type Column } from '@/components/DataTable.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import { deliveryTone, when } from '@/format'

const hooks = ref<Webhook[]>([])
const selected = ref<Webhook | null>(null)
const deliveries = ref<WebhookDelivery[]>([])
const secret = ref('')
const rotating = ref(false)

async function pick(h: Webhook) { selected.value = h; secret.value = ''; deliveries.value = (await api.deliveries(h.id)).items }
async function refresh() { if (selected.value) deliveries.value = (await api.deliveries(selected.value.id)).items }
async function redeliver(d: WebhookDelivery) { await api.redeliver(d.id); await refresh() }
async function rotate() {
  if (!selected.value) return
  rotating.value = true
  try { secret.value = (await api.rotateSecret(selected.value.id)).signing_secret } finally { rotating.value = false }
}
onMounted(async () => { hooks.value = (await api.webhooks()).items; if (hooks.value[0]) await pick(hooks.value[0]) })

const hookCols: Column[] = [
  { key: 'url', label: 'Endpoint', primary: true }, { key: 'events', label: 'Events' }, { key: 'status', label: 'Status' }, { key: 'fails', label: 'Failures in a row', align: 'right' },
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
      <div class="titles"><h1>Webhooks</h1><p class="muted">Signed with HMAC-SHA256 · at-least-once delivery with retries · POST /webhooks</p></div>
      <div class="actions"><UiButton variant="primary" icon="plus">Add endpoint</UiButton></div>
    </div>

    <UiCard title="Endpoints" :caption="`${hooks.length} endpoints · HTTPS only · private and internal addresses are blocked`">
      <DataTable :columns="hookCols" :rows="hooks" row-key="id" :selected="selected?.id" clickable @select="pick">
        <template #cell-url="{ row }"><span class="url">{{ row.url }}</span><div class="caption">{{ row.description }}</div></template>
        <template #cell-events="{ row }"><span class="events">{{ row.event_types.join(', ') }}</span></template>
        <template #cell-status="{ row }">
          <UiBadge :tone="row.is_active ? 'success' : 'danger'">{{ row.is_active ? 'Active' : 'Disabled' }}</UiBadge>
          <div v-if="row.disabled_reason" class="caption">{{ row.disabled_reason }}</div>
        </template>
        <template #cell-fails="{ row }">{{ row.consecutive_failures }}</template>
      </DataTable>
    </UiCard>

    <div class="split">
      <UiCard :title="`Deliveries · ${selected?.description ?? ''}`" caption="GET /webhooks/{id}/deliveries · newest first">
        <template #actions><UiButton variant="ghost" icon="refresh" @click="refresh">Refresh</UiButton></template>
        <DataTable :columns="delCols" :rows="deliveries" row-key="id">
          <template #cell-event="{ row }"><span>{{ row.event_type }}</span><div class="caption mono">evt {{ row.event_id }} · {{ when(row.created_at) }}</div></template>
          <template #cell-status="{ row }">
            <UiBadge :tone="row.status === 'pending' && !row.attempts ? 'neutral' : deliveryTone[row.status]">{{ deliveryLabel(row) }}</UiBadge>
          </template>
          <template #cell-code="{ row }">{{ row.last_status_code ?? '—' }}</template>
          <template #cell-action="{ row }">
            <UiButton v-if="row.status === 'dead'" variant="ghost" @click="redeliver(row)">Redeliver</UiButton>
            <span v-else-if="row.status === 'pending'" class="caption">next try in {{ inMinutes(row.next_attempt_at) }} min</span>
          </template>
        </DataTable>
      </UiCard>

      <UiCard title="Signing secret" caption="Shown once when created or rotated. Stored encrypted.">
        <div class="secret">
          <div class="secret-box"><AppIcon name="key" :size="16" /><code>{{ secret || 'whsec_••••••••••••••••••••' }}</code></div>
          <p v-if="secret" class="caption warn">Copy it now: it will not be shown again.</p>
          <UiButton icon="refresh" block :loading="rotating" @click="rotate">Rotate secret</UiButton>
        </div>
        <div class="verify">
          <p class="label">Verify each request</p>
          <pre class="code">X-TenantCore-Event-Id: 7c1e…
X-TenantCore-Event-Type: order.created
X-TenantCore-Signature:
  t=1797224460,v1=5f2b…

v1 = HMAC_SHA256(secret, f"{t}.{body}")
reject stale t (e.g. older than 5 min)
dedupe on Event-Id</pre>
        </div>
      </UiCard>
    </div>
  </div>
</template>

<style scoped>
.url { overflow-wrap: anywhere; }
.events { font-size: 13px; }
.secret { padding: 0 24px 20px; display: grid; gap: 12px; }
.secret-box { display: flex; align-items: center; gap: 8px; background: var(--bg-subtle); border-radius: var(--radius-md); padding: 10px 12px; color: var(--text-secondary); min-width: 0; }
.secret-box code { font-family: var(--font-mono); font-size: 13px; color: var(--text-primary); overflow-wrap: anywhere; }
.warn { color: var(--warning); }
.verify { padding: 20px 24px; border-top: 1px solid var(--border-default); display: grid; gap: 10px; }
.code { margin: 0; background: var(--bg-sidebar); color: var(--text-sidebar); border-radius: var(--radius-md); padding: 12px 14px; font-family: var(--font-mono); font-size: 12px; line-height: 18px; overflow-x: auto; }
@media (max-width: 767px) { .secret { padding: 0 16px 16px; } .verify { padding: 16px; } }
</style>
