<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/api/client'
import type { DataTable, Task } from '@/api/types'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiSelect from '@/components/UiSelect.vue'
import { useBusy, useLoader } from '@/composables'
import { dateTime, taskTone, title } from '@/format'
import { download, toast } from '@/ui'

// The API has no "list my imports" endpoint, so this tab remembers the imports started here.
interface Tracked { id: string; table: string; filename: string; task: Task | null }
const KEY = 'tc.imports'
const load = (): Tracked[] => { try { return JSON.parse(sessionStorage.getItem(KEY) ?? '[]') as Tracked[] } catch { return [] } }
const save = () => { try { sessionStorage.setItem(KEY, JSON.stringify(tracked.value.slice(0, 20))) } catch { /* storage unavailable */ } }

const route = useRoute()
const tables = ref<DataTable[]>([])
const table = ref(typeof route.query.table === 'string' ? route.query.table : '')
const mode = ref('all_or_nothing')
const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const tracked = ref<Tracked[]>(load())
const { busy, run } = useBusy()

const { loading, error, reload } = useLoader(async () => {
  tables.value = (await api.tables()).items.filter((t) => t.actions.includes('create'))
  if (!tables.value.some((t) => t.name === table.value)) table.value = tables.value[0]?.name ?? ''
  poll()
})
const selected = computed(() => tables.value.find((t) => t.name === table.value))
const MAX_MB = 10
function onFile(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0] ?? null
  if (f && f.size > MAX_MB * 1024 * 1024) { toast(`Files are limited to ${MAX_MB} MB.`, 'err'); file.value = null; return }
  file.value = f
}
async function upload() {
  if (!file.value || !table.value) return
  const f = file.value, name = table.value
  let task: Task | undefined
  if (await run('upload', async () => { task = await api.importFile(name, f, mode.value) }, 'Upload accepted. The import runs in the background.') && task) {
    tracked.value.unshift({ id: task.id, table: name, filename: f.name, task }); save()
    file.value = null; if (fileInput.value) fileInput.value.value = ''
    poll()
  }
}
function template() {
  if (!selected.value) return
  download(`${selected.value.name}-template.csv`, selected.value.fields.filter((f) => f.writable).map((f) => f.name).join(',') + '\r\n', 'text/csv')
}

let timer: ReturnType<typeof setTimeout> | undefined
async function poll() {
  clearTimeout(timer)
  const open = tracked.value.filter((t) => !t.task || ['queued', 'running'].includes(t.task.status))
  await Promise.all(open.map(async (t) => { try { t.task = await api.task(t.id) } catch { /* keep the last known state */ } }))
  save()
  if (tracked.value.some((t) => !t.task || ['queued', 'running'].includes(t.task.status))) timer = setTimeout(poll, 2000)
}
onBeforeUnmount(() => clearTimeout(timer))
function forget() { tracked.value = tracked.value.filter((t) => t.task && ['queued', 'running'].includes(t.task.status)); save() }
const errorsOf = (t: Task) => ((t.result?.errors ?? (t.result?.details as { errors?: unknown[] } | undefined)?.errors ?? []) as { row: number; errors: unknown }[]).slice(0, 5)
const stale = (t: Tracked) => t.task?.status === 'queued' && Date.now() - new Date(t.task.created_at).getTime() > 30_000
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Imports</h1><p class="muted">Bulk-load records from a CSV or Excel file. Every row is validated against the table definition and your permissions.</p></div>
    </div>

    <LoadState :loading="loading" :error="error" :ready="!!tables.length" @retry="reload">
      <div class="split">
        <UiCard title="Recent imports" caption="Imports started in this browser tab. Status refreshes automatically.">
          <template #actions><UiButton variant="ghost" :disabled="!tracked.length" @click="forget">Clear finished</UiButton></template>
          <EmptyState v-if="!tracked.length" icon="import" title="No imports yet" body="Upload a file to see its progress here." />
          <ul v-else class="list">
            <li v-for="t in tracked" :key="t.id">
              <div class="top">
                <div><strong>{{ t.filename }}</strong><p class="caption">into {{ title(t.table) }} · {{ dateTime(t.task?.created_at) }}</p></div>
                <UiBadge :tone="taskTone[t.task?.status ?? 'queued']">{{ title(t.task?.status ?? 'queued') }}</UiBadge>
              </div>
              <p v-if="t.task?.status === 'succeeded'" class="caption ok">Inserted {{ t.task.result?.inserted ?? 0 }} rows<template v-if="Number(t.task.result?.invalid)"> · skipped {{ t.task.result?.invalid }} invalid rows</template>.</p>
              <p v-if="t.task?.error" class="caption bad">{{ t.task.error }}</p>
              <ul v-if="t.task && errorsOf(t.task).length" class="errs">
                <li v-for="e in errorsOf(t.task)" :key="e.row" class="caption">Row {{ e.row }}: {{ typeof e.errors === 'string' ? e.errors : JSON.stringify(e.errors) }}</li>
              </ul>
              <p v-if="stale(t)" class="caption warn">Still queued: a background worker must be running to process imports.</p>
            </li>
          </ul>
        </UiCard>

        <UiCard title="Upload a file">
          <form class="card-body" @submit.prevent="upload">
            <EmptyState v-if="!tables.length" icon="database" title="Nothing to import into" body="Your role cannot create records in any table." />
            <template v-else>
              <UiSelect v-model="table" label="Table" :options="tables.map((t) => ({ value: t.name, label: title(t.name) }))" />
              <div class="field">
                <label class="label" for="file">File</label>
                <input id="file" ref="fileInput" type="file" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="onFile" />
                <p class="caption">CSV or XLSX, first row is the column names, up to {{ MAX_MB }} MB.</p>
              </div>
              <UiSelect v-model="mode" label="If a row is invalid" :options="[{ value: 'all_or_nothing', label: 'Reject the whole file' }, { value: 'skip_invalid', label: 'Skip that row and import the rest' }]" />
              <div class="row-actions">
                <UiButton icon="download" :disabled="!selected" @click="template">Download template</UiButton>
                <UiButton type="submit" variant="primary" icon="upload" :loading="busy === 'upload'" :disabled="!file">Upload</UiButton>
              </div>
              <p v-if="selected" class="caption">Columns: {{ selected.fields.filter((f) => f.writable).map((f) => f.name + (f.nullable ? '' : '*')).join(', ') }} (* required)</p>
            </template>
          </form>
        </UiCard>
      </div>
    </LoadState>
  </div>
</template>

<style scoped>
.list { list-style: none; margin: 0; padding: 0; }
.list > li { padding: 14px 24px; border-top: 1px solid var(--border-default); display: grid; gap: 6px; }
.top { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; }
.top strong { overflow-wrap: anywhere; }
.errs { margin: 0; padding-left: 18px; }
.ok { color: var(--success); }
.bad { color: var(--danger); overflow-wrap: anywhere; }
.warn { color: var(--warning); }
.field { display: grid; gap: 6px; }
input[type='file'] { padding: 10px; border: 1px dashed var(--border-strong); border-radius: var(--radius-md); background: var(--bg-canvas); max-width: 100%; }
@media (max-width: 767px) { .list > li { padding: 12px 16px; } }
</style>
