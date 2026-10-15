<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/api/client'
import { can } from '@/api/me'
import { useBusy, useList, useLoader } from '@/composables'
import { dateTime, when } from '@/format'
import { download, toCsv } from '@/ui'
import DataTable from '@/components/DataTable.vue'
import FileButton from '@/components/FileButton.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiModal from '@/components/UiModal.vue'
import UiPager from '@/components/UiPager.vue'
import UiSelect from '@/components/UiSelect.vue'

const route = useRoute()
const tables = ref<any[]>([])
useLoader(async () => { tables.value = (await api.get('/data/tables')).items.filter((t: any) => can(`data.${t.key}`, 'create')) })
const list = useList((q) => api.get('/data/imports', q), {})
const { busy, run } = useBusy()

// Wizard: 1 choose table + file → 2 map columns → started (progress shown in the list)
const wiz = reactive({ open: false, step: 1, table: typeof route.query.table === 'string' ? route.query.table : '', imp: null as any,
  mapping: {} as Record<string, string>, mode: 'insert', match: '', date: 'iso', dry: false })
if (wiz.table) wiz.open = true
const target = computed(() => tables.value.find((t) => t.key === wiz.table))
const fields = computed(() => (target.value?.fields ?? []).filter((f: any) => f.can_write))
const uniqueFields = computed(() => fields.value.filter((f: any) => f.unique))
function startWizard() { Object.assign(wiz, { open: true, step: 1, imp: null, mapping: {}, mode: 'insert', match: '', date: 'iso', dry: false }) }
async function upload(files: File[]) {
  await run('upload', async () => {
    const fd = new FormData(); fd.append('file', files[0])
    wiz.imp = await api.upload(`/data/tables/${wiz.table}/imports`, fd)
    wiz.mapping = Object.fromEntries(wiz.imp.headers.map((h: string) => [h, wiz.imp.suggested_mapping?.[h] ?? '']))
    wiz.step = 2
  })
}
async function start() {
  const mapping = Object.fromEntries(Object.entries(wiz.mapping).filter(([, v]) => v))
  const ok = await run('start', async () => {
    await api.post(`/data/imports/${wiz.imp.id}/start`, { mapping, mode: wiz.mode, match_field: wiz.mode === 'upsert' ? wiz.match : null, date_format: wiz.date, dry_run: wiz.dry })
  }, wiz.dry ? 'Checking every row. Nothing is saved.' : 'Import started.')
  if (ok) { wiz.open = false; await list.reload() }
}
async function cancel(i: any) { await run('cancel:' + i.id, () => api.post(`/data/imports/${i.id}/cancel`), 'Import cancelled.'); list.reload() }
function errorsCsv(i: any) { download(`import-${i.id.slice(0, 8)}-errors.csv`, toCsv(i.errors.map((e: any) => ({ row: e.row, field: e.field ?? '', message: e.message }))), 'text/csv') }

// Refresh while something is running.
let timer: ReturnType<typeof setTimeout> | undefined
function poll() {
  clearTimeout(timer)
  timer = setTimeout(async () => {
    if (list.items.value.some((i: any) => ['queued', 'running'].includes(i.status))) await list.reload()
  }, 1500)
}
watch(() => list.items.value, () => poll())
onBeforeUnmount(() => clearTimeout(timer))
const pct = (i: any) => (i.total_rows ? Math.round((i.processed_rows / i.total_rows) * 100) : 0)
const details = ref<any>(null)
</script>

<template>
  <div class="page">
    <PageHeader title="Imports" caption="Load rows into a table from a CSV or Excel file.">
      <UiButton v-if="tables.length" icon="upload" variant="primary" @click="startWizard">New import</UiButton>
    </PageHeader>
    <UiCard>
      <LoadState :loading="list.loading.value" :error="list.error.value" :ready="list.items.value.length > 0" @retry="list.reload">
        <DataTable :columns="[{ key: 'file', label: 'File', primary: true }, { key: 'status', label: 'Status' }, { key: 'rows', label: 'Rows', align: 'right' }, { key: 'acts', label: '' }]"
                   :rows="list.items.value" row-key="id" clickable empty="No imports yet." @select="(i) => (details = i)">
          <template #cell-file="{ row }"><strong>{{ row.filename }}</strong><span class="caption">into {{ row.table }} · {{ when(row.created_at) }}{{ row.dry_run ? ' · check only' : '' }}</span></template>
          <template #cell-status="{ row }">
            <StatusBadge :status="row.status" />
            <div v-if="row.status === 'running'" class="bar"><span :style="{ width: pct(row) + '%' }" /></div>
          </template>
          <template #cell-rows="{ row }"><span>{{ row.inserted_rows + row.updated_rows }} saved</span><span v-if="row.failed_rows" class="caption err">{{ row.failed_rows }} failed</span></template>
          <template #cell-acts="{ row }">
            <div class="row-actions" @click.stop>
              <UiButton v-if="['uploaded', 'queued', 'running'].includes(row.status)" variant="ghost" :loading="busy === 'cancel:' + row.id" @click="cancel(row)">Cancel</UiButton>
              <UiButton v-if="row.errors?.length" variant="ghost" icon="download" @click="errorsCsv(row)">Errors</UiButton>
            </div>
          </template>
        </DataTable>
        <UiPager v-model="list.offset.value" :total="list.total.value" :limit="list.limit" :count="list.items.value.length" />
      </LoadState>
    </UiCard>

    <UiModal :open="wiz.open" :title="wiz.step === 1 ? 'Import rows' : `Match columns · ${wiz.imp?.filename}`" wide :submit-label="wiz.step === 2 ? (wiz.dry ? 'Check rows' : 'Start import') : undefined"
             :busy="busy === 'start'" :submit-disabled="wiz.mode === 'upsert' && !wiz.match" @close="wiz.open = false" @submit="start">
      <template v-if="wiz.step === 1">
        <UiSelect v-model="wiz.table" label="Into table" :options="[{ value: '', label: 'Choose a table' }, ...tables.map((t) => ({ value: t.key, label: t.name }))]" />
        <p class="caption">CSV (UTF-8 or Thai Windows-874) or Excel .xlsx. The first row must be the column names.</p>
        <div><FileButton accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" :loading="busy === 'upload'" variant="primary" @pick="upload">{{ wiz.table ? 'Choose file' : 'Choose a table first' }}</FileButton></div>
      </template>
      <template v-else-if="wiz.imp">
        <p class="caption">{{ wiz.imp.total_rows }} rows found. Pick the field each column goes into; leave a column on “Skip” to ignore it.</p>
        <div class="map">
          <div v-for="h in wiz.imp.headers" :key="h" class="maprow">
            <div class="col"><strong>{{ h }}</strong><span class="caption">{{ (wiz.imp.preview ?? []).slice(0, 3).map((r: string[]) => r[wiz.imp.headers.indexOf(h)]).filter(Boolean).join(' · ') }}</span></div>
            <select v-model="wiz.mapping[h]" :aria-label="`Field for ${h}`"><option value="">Skip</option><option v-for="f in fields" :key="f.key" :value="f.key">{{ f.label }}</option></select>
          </div>
        </div>
        <div class="grid3">
          <UiSelect v-model="wiz.mode" label="Rows" :options="[{ value: 'insert', label: 'Add as new rows' }, { value: 'upsert', label: 'Update matching rows, add the rest' }]" />
          <UiSelect v-if="wiz.mode === 'upsert'" v-model="wiz.match" label="Match on" :options="[{ value: '', label: uniqueFields.length ? 'Choose a unique field' : 'No unique fields' }, ...uniqueFields.map((f: any) => ({ value: f.key, label: f.label }))]" />
          <UiSelect v-model="wiz.date" label="Dates look like" :options="[{ value: 'iso', label: '2026-12-31' }, { value: 'dmy', label: '31/12/2026' }, { value: 'mdy', label: '12/31/2026' }]" />
        </div>
        <UiCheck v-model="wiz.dry" label="Check only" hint="Validates every row and reports problems without saving anything." />
      </template>
    </UiModal>

    <UiModal :open="!!details" :title="details?.filename ?? ''" @close="details = null">
      <template v-if="details">
        <div class="kv"><span>Table</span><span>{{ details.table }}</span></div>
        <div class="kv"><span>Status</span><StatusBadge :status="details.status" /></div>
        <div class="kv"><span>Rows</span><span>{{ details.processed_rows }} / {{ details.total_rows }}</span></div>
        <div class="kv"><span>Added</span><span>{{ details.inserted_rows }}</span></div>
        <div class="kv"><span>Updated</span><span>{{ details.updated_rows }}</span></div>
        <div class="kv"><span>Failed</span><span>{{ details.failed_rows }}</span></div>
        <div class="kv"><span>Finished</span><span>{{ dateTime(details.finished_at) }}</span></div>
        <p v-if="details.last_error" class="note danger">{{ details.last_error }}</p>
        <div v-if="details.errors?.length" class="errs">
          <p v-for="(e, i) in details.errors.slice(0, 20)" :key="i" class="caption">Row {{ e.row }}<template v-if="e.field"> · {{ e.field }}</template>: {{ e.message }}</p>
          <p v-if="details.errors.length > 20" class="caption">…and {{ details.errors.length - 20 }} more. Download the errors file for all of them.</p>
        </div>
      </template>
    </UiModal>
  </div>
</template>

<style scoped>
.bar { height: 4px; background: var(--bg-subtle); border-radius: 2px; margin-top: 4px; width: 100px; overflow: hidden; }
.bar span { display: block; height: 100%; background: var(--brand-primary); }
.err { color: var(--danger); }
.map { display: grid; gap: 8px; max-height: 340px; overflow-y: auto; }
.maprow { display: flex; gap: 12px; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--border-default); }
.maprow .col { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.maprow .col .caption { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.maprow select { flex: 0 0 200px; padding: 8px 10px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); background: var(--bg-surface); }
.errs { display: grid; gap: 4px; max-height: 200px; overflow-y: auto; }
</style>
