<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { DeadLetter, Tenant } from '@/api/types'
import DataTable, { type Column } from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiModal from '@/components/UiModal.vue'
import { useBusy, useLoader } from '@/composables'
import { dateTime, shortId, when } from '@/format'

const items = ref<DeadLetter[]>([])
const tenants = ref<Tenant[]>([])
const includeRequeued = ref(false)
const open = ref<DeadLetter | null>(null)
const { busy, run } = useBusy()
const { loading, error, reload } = useLoader(async () => {
  const [d, t] = await Promise.all([api.deadLetters(includeRequeued.value), tenants.value.length ? Promise.resolve({ items: tenants.value }) : api.tenants().catch(() => ({ items: [] as Tenant[] }))])
  items.value = d.items; tenants.value = t.items
})
const tenantName = (id: string | null) => (id ? tenants.value.find((t) => t.id === id)?.slug ?? shortId(id) : 'Platform')
async function requeue(d: DeadLetter) {
  if (await run(d.id, () => api.requeueDeadLetter(d.id), d.source === 'task' ? 'Task queued again.' : 'Delivery queued again.')) { open.value = null; await reload() }
}
async function requeueAll() {
  const pending = items.value.filter((d) => !d.requeued_at)
  await run('all', async () => { for (const d of pending) await api.requeueDeadLetter(d.id) }, `${pending.length} items queued again.`)
  await reload()
}
const pendingCount = computed(() => items.value.filter((d) => !d.requeued_at).length)
const columns: Column[] = [
  { key: 'what', label: 'Job', primary: true }, { key: 'tenant', label: 'Tenant' }, { key: 'error', label: 'Error', hideOnMobile: true },
  { key: 'created_at', label: 'Failed' }, { key: 'act', label: '', align: 'right' },
]
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Dead letters</h1><p class="muted">Background jobs and webhook deliveries that failed permanently. Fix the cause, then requeue them.</p></div>
      <div class="actions"><UiButton variant="primary" icon="refresh" :disabled="!pendingCount" :loading="busy === 'all'" @click="requeueAll">Requeue all ({{ pendingCount }})</UiButton></div>
    </div>
    <LoadState :loading="loading" :error="error" :ready="!!items.length" @retry="reload">
      <UiCard title="Failed jobs" caption="Newest first">
        <template #actions><UiCheck v-model="includeRequeued" label="Include requeued" @update:model-value="reload" /><UiButton variant="ghost" icon="refresh" :loading="loading" @click="reload">Refresh</UiButton></template>
        <EmptyState v-if="!items.length" icon="check" title="Nothing has failed" body="Jobs land here only after exhausting their retries." />
        <DataTable v-else :columns="columns" :rows="items" row-key="id" clickable @select="(d) => (open = d)">
          <template #cell-what="{ row }"><UiBadge :tone="row.source === 'task' ? 'info' : 'brand'">{{ row.source }}</UiBadge> <span class="mono">{{ row.task_name ?? shortId(row.reference_id) }}</span></template>
          <template #cell-tenant="{ row }">{{ tenantName(row.tenant_id) }}</template>
          <template #cell-error="{ row }"><span class="err">{{ row.error }}</span></template>
          <template #cell-created_at="{ row }">{{ when(row.created_at) }}<div v-if="row.requeued_at" class="caption">requeued {{ when(row.requeued_at) }}</div></template>
          <template #cell-act="{ row }"><UiButton v-if="!row.requeued_at" variant="ghost" :loading="busy === row.id" @click.stop="requeue(row)">Requeue</UiButton></template>
        </DataTable>
      </UiCard>
    </LoadState>
    <UiModal :open="!!open" :title="open?.task_name ?? `${open?.source} failure`" :caption="open ? dateTime(open.created_at) : ''" :submit-label="open && !open.requeued_at ? 'Requeue' : undefined"
             :busy="busy === open?.id" @close="open = null" @submit="open && requeue(open)">
      <template v-if="open">
        <div class="kv"><span>Source</span><span>{{ open.source }}</span></div>
        <div class="kv"><span>Tenant</span><span>{{ tenantName(open.tenant_id) }}</span></div>
        <div class="kv"><span>Reference</span><span class="mono">{{ open.reference_id }}</span></div>
        <p class="section-label">Error</p>
        <pre class="json">{{ open.error }}</pre>
      </template>
    </UiModal>
  </div>
</template>

<style scoped>
.err { font-size: 13px; color: var(--danger); overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.json { margin: 0; background: var(--bg-subtle); border-radius: var(--radius-md); padding: 12px; font-family: var(--font-mono); font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 300px; overflow: auto; }
.kv .mono { overflow-wrap: anywhere; }
</style>
