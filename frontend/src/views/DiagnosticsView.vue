<script setup lang="ts">
import { ref } from 'vue'
import { api } from '@/api/client'
import type { SlowQueries } from '@/api/types'
import DataTable, { type Column } from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import StatCard from '@/components/StatCard.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiBadge from '@/components/UiBadge.vue'
import { useBusy, useLoader } from '@/composables'
import { dateTime, number, taskTone, title } from '@/format'
import type { Task } from '@/api/types'

const data = ref<SlowQueries | null>(null)
const { loading, error, reload } = useLoader(async () => { data.value = await api.slowQueries() })
const qCols: Column[] = [{ key: 'query', label: 'Statement', primary: true }, { key: 'mean_ms', label: 'Mean', align: 'right' }, { key: 'calls', label: 'Calls', align: 'right' }, { key: 'total_ms', label: 'Total', align: 'right', hideOnMobile: true }]
const tCols: Column[] = [{ key: 'relname', label: 'Table', primary: true }, { key: 'seq_scan', label: 'Seq scans', align: 'right' }, { key: 'idx_scan', label: 'Index scans', align: 'right' }, { key: 'n_live_tup', label: 'Rows', align: 'right' }]
const ms = (v: number) => (v >= 1000 ? `${(v / 1000).toFixed(1)} s` : `${v.toFixed(1)} ms`)

// Look up a background task by id (e.g. from an offboarding request or a dead letter).
const taskId = ref('')
const task = ref<Task | null>(null)
const { busy, run } = useBusy()
async function lookup() { task.value = null; await run('task', async () => { task.value = await api.platformTask(taskId.value.trim()) }) }
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Diagnostics</h1><p class="muted">Query statistics from PostgreSQL. Statements are normalised, so no tenant data appears here.</p></div>
      <div class="actions"><UiButton icon="refresh" :loading="loading" @click="reload">Refresh</UiButton></div>
    </div>
    <LoadState :loading="loading" :error="error" :ready="!!data" @retry="reload">
      <template v-if="data">
        <div class="stats">
          <StatCard label="Slow-query log threshold" :value="`${data.slow_query_log_threshold_ms} ms`" note="Statements slower than this are logged" />
          <StatCard label="Tracked statements" :value="data.pg_stat_statements ? String(data.pg_stat_statements.length) : 'Off'" note="Top statements by mean time" />
          <StatCard label="Scan-heavy tables" :value="String(data.seq_scan_heavy_tables.length)" note="More sequential than index scans" />
          <StatCard label="Slowest mean" :value="data.pg_stat_statements?.[0] ? ms(data.pg_stat_statements[0].mean_ms) : '—'" note="Per call" />
        </div>
        <UiCard title="Slowest statements" caption="pg_stat_statements, ranked by mean execution time">
          <EmptyState v-if="!data.pg_stat_statements" icon="activity" title="pg_stat_statements is not enabled"
                      body="Add it to shared_preload_libraries, run CREATE EXTENSION pg_stat_statements, and grant pg_read_all_stats to the app role." />
          <DataTable v-else :columns="qCols" :rows="data.pg_stat_statements.map((q, i) => ({ ...q, i }))" row-key="i" empty="No statements recorded yet.">
            <template #cell-query="{ row }"><code class="sql">{{ row.query }}</code></template>
            <template #cell-mean_ms="{ row }">{{ ms(row.mean_ms) }}</template>
            <template #cell-calls="{ row }">{{ number(row.calls) }}</template>
            <template #cell-total_ms="{ row }">{{ ms(row.total_ms) }}</template>
          </DataTable>
        </UiCard>
        <div class="split">
          <UiCard title="Tables that may need an index" caption="pg_stat_user_tables · over 1,000 rows">
            <DataTable :columns="tCols" :rows="data.seq_scan_heavy_tables as Record<string, any>[]" row-key="relname" empty="No scan-heavy tables. Indexes are doing their job.">
              <template #cell-relname="{ row }"><span class="mono">{{ row.schemaname }}.{{ row.relname }}</span></template>
              <template #cell-seq_scan="{ row }">{{ number(Number(row.seq_scan)) }}</template>
              <template #cell-idx_scan="{ row }">{{ number(Number(row.idx_scan ?? 0)) }}</template>
              <template #cell-n_live_tup="{ row }">{{ number(Number(row.n_live_tup)) }}</template>
            </DataTable>
          </UiCard>
          <UiCard title="Background task lookup" caption="Status of a platform task by id">
            <form class="card-body" @submit.prevent="lookup">
              <UiInput v-model="taskId" label="Task id" mono placeholder="00000000-0000-…" />
              <div><UiButton type="submit" :loading="busy === 'task'" :disabled="!/^[0-9a-f-]{36}$/i.test(taskId.trim())">Look up</UiButton></div>
              <template v-if="task">
                <div class="kv"><span>Kind</span><span>{{ task.kind }}</span></div>
                <div class="kv"><span>Status</span><UiBadge :tone="taskTone[task.status]">{{ title(task.status) }}</UiBadge></div>
                <div class="kv"><span>Attempts</span><span>{{ task.attempts ?? '—' }}</span></div>
                <div class="kv"><span>Created</span><span>{{ dateTime(task.created_at) }}</span></div>
                <div class="kv"><span>Finished</span><span>{{ dateTime(task.finished_at) }}</span></div>
                <p v-if="task.error" class="note danger">{{ task.error }}</p>
              </template>
            </form>
          </UiCard>
        </div>
      </template>
    </LoadState>
  </div>
</template>

<style scoped>
.sql { font-family: var(--font-mono); font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
