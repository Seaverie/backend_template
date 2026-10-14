<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { AuditEntry } from '@/api/types'
import DataTable, { type Column } from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiModal from '@/components/UiModal.vue'
import { useLoader } from '@/composables'
import { dateTime, shortId } from '@/format'
import { download, toCsv } from '@/ui'

const PAGE = 50
const entries = ref<AuditEntry[]>([])
const offset = ref(0)
const q = ref('')
const open = ref<AuditEntry | null>(null)
const { loading, error, reload } = useLoader(async () => { entries.value = (await api.auditLogs(offset.value, PAGE)).items })
function page(dir: 1 | -1) { offset.value = Math.max(0, offset.value + dir * PAGE); reload() }

const shown = computed(() => {
  const s = q.value.trim().toLowerCase()
  return s ? entries.value.filter((e) => `${e.action} ${e.resource} ${e.resource_id ?? ''} ${e.actor_id ?? ''}`.toLowerCase().includes(s)) : entries.value
})
const tone = (a: string) => (/delete|revoke|offboard|erase/.test(a) ? 'danger' : /create|import/.test(a) ? 'success' : /login|auth/.test(a) ? 'info' : 'neutral')
const columns: Column[] = [
  { key: 'action', label: 'Action', primary: true }, { key: 'resource', label: 'Resource' }, { key: 'actor', label: 'Actor', hideOnMobile: true },
  { key: 'created_at', label: 'When' },
]
function exportCsv() {
  download(`audit-log-${new Date().toISOString().slice(0, 10)}.csv`, toCsv(entries.value.map((e) => ({ ...e, detail: e.detail ? JSON.stringify(e.detail) : '' }))), 'text/csv')
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Audit log</h1><p class="muted">Every change made in this workspace, by whom and when. Entries cannot be edited or deleted.</p></div>
      <div class="actions"><UiButton icon="download" :disabled="!entries.length" @click="exportCsv">Export CSV</UiButton></div>
    </div>
    <LoadState :loading="loading" :error="error" :ready="!!entries.length" @retry="reload">
      <UiCard title="Events" caption="Newest first · click an entry for details">
        <template #actions><UiButton variant="ghost" icon="refresh" :loading="loading" @click="reload">Refresh</UiButton></template>
        <div class="toolbar"><input v-model="q" class="search-input" type="search" placeholder="Filter this page by action, resource or id" aria-label="Filter events" /></div>
        <DataTable :columns="columns" :rows="shown" row-key="id" clickable :empty="q ? 'No entries on this page match.' : 'No activity recorded yet.'" @select="(e) => (open = e)">
          <template #cell-action="{ row }"><UiBadge :tone="tone(row.action)">{{ row.action }}</UiBadge></template>
          <template #cell-resource="{ row }"><span>{{ row.resource }}</span><div v-if="row.resource_id" class="caption mono">{{ shortId(row.resource_id) }}</div></template>
          <template #cell-actor="{ row }"><span>{{ row.actor_type }}</span><div class="caption mono">{{ shortId(row.actor_id) }}</div></template>
          <template #cell-created_at="{ row }">{{ dateTime(row.created_at) }}</template>
        </DataTable>
        <div class="pager">
          <span class="caption">{{ entries.length ? `Entries ${offset + 1}–${offset + entries.length}` : '' }}</span>
          <div class="row-actions">
            <UiButton :disabled="offset === 0 || loading" @click="page(-1)">Newer</UiButton>
            <UiButton :disabled="entries.length < PAGE || loading" @click="page(1)">Older</UiButton>
          </div>
        </div>
      </UiCard>
    </LoadState>

    <UiModal :open="!!open" :title="open?.action ?? ''" :caption="open ? dateTime(open.created_at) : ''" @close="open = null">
      <template v-if="open">
        <div class="kv"><span>Resource</span><span>{{ open.resource }}</span></div>
        <div class="kv"><span>Resource id</span><span class="mono">{{ open.resource_id ?? '—' }}</span></div>
        <div class="kv"><span>Actor</span><span>{{ open.actor_type }} <span class="mono">{{ open.actor_id ?? '' }}</span></span></div>
        <div class="kv"><span>Request id</span><span class="mono">{{ open.request_id ?? '—' }}</span></div>
        <p class="section-label">Detail</p>
        <pre class="json">{{ open.detail ? JSON.stringify(open.detail, null, 2) : 'No extra detail.' }}</pre>
        <p class="caption">Sensitive values are masked before they are written to the log.</p>
      </template>
    </UiModal>
  </div>
</template>

<style scoped>
.pager { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 24px; border-top: 1px solid var(--border-default); flex-wrap: wrap; }
.json { margin: 0; background: var(--bg-subtle); border-radius: var(--radius-md); padding: 12px; font-family: var(--font-mono); font-size: 12px; overflow: auto; max-height: 320px; white-space: pre-wrap; overflow-wrap: anywhere; }
.kv .mono { overflow-wrap: anywhere; }
@media (max-width: 767px) { .pager { padding: 12px 16px; } }
</style>
