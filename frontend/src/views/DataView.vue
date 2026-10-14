<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import type { DataTable as Table, FieldDef } from '@/api/types'
import DataTable, { type Column } from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiSelect from '@/components/UiSelect.vue'
import { useBusy, useLoader } from '@/composables'
import { dateTime, title } from '@/format'
import { ask, download, errText, toast, toCsv } from '@/ui'

type Row = Record<string, unknown> & { id: string }
const router = useRouter()
const tables = ref<Table[]>([])
const current = ref('')
const rows = ref<Row[]>([])
const q = ref('')
const sort = ref('-created_at')
const offset = ref(0)
const next = ref<number | null>(null)
const pageSize = 25
const rowsLoading = ref(false)
const rowsError = ref('')
const { busy, run } = useBusy()

const { loading, error, reload } = useLoader(async () => {
  tables.value = (await api.tables()).items
  if (!tables.value.some((t) => t.name === current.value)) current.value = tables.value[0]?.name ?? ''
  await loadRows()
})
const table = computed(() => tables.value.find((t) => t.name === current.value) ?? null)
const can = (a: string) => !!table.value?.actions.includes(a)

async function loadRows() {
  if (!table.value) { rows.value = []; return }
  rowsLoading.value = true; rowsError.value = ''
  try {
    const page = await api.rows(table.value.name, { q: q.value.trim() || undefined, offset: offset.value, limit: pageSize, sort: sort.value })
    rows.value = page.items as Row[]; next.value = page.next_offset
  } catch (e) { rowsError.value = errText(e); rows.value = [] } finally { rowsLoading.value = false }
}
watch(current, () => { q.value = ''; offset.value = 0; sort.value = '-created_at'; loadRows() })
let t: ReturnType<typeof setTimeout> | undefined
watch(q, () => { clearTimeout(t); t = setTimeout(() => { offset.value = 0; loadRows() }, 300) })
function page(dir: 1 | -1) { offset.value = Math.max(0, offset.value + dir * pageSize); loadRows() }

const visible = computed(() => (table.value?.fields ?? []).slice(0, 6))
const columns = computed<Column[]>(() => [
  ...visible.value.map((f, i) => ({ key: f.name, label: title(f.name), primary: i === 0, align: ['integer', 'bigint', 'numeric'].includes(f.type) ? 'right' as const : 'left' as const, hideOnMobile: i > 3 })),
  { key: '__updated', label: 'Updated', hideOnMobile: true },
])
const sortOptions = computed(() => [
  { value: '-created_at', label: 'Newest first' }, { value: 'created_at', label: 'Oldest first' },
  ...(table.value?.fields ?? []).filter((f) => !['json', 'text'].includes(f.type)).flatMap((f) => [{ value: f.name, label: `${title(f.name)} A→Z` }, { value: `-${f.name}`, label: `${title(f.name)} Z→A` }]),
])
watch(sort, () => { offset.value = 0; loadRows() })
const show = (v: unknown, f?: FieldDef) => {
  if (v === null || v === undefined || v === '') return '—'
  if (typeof v === 'boolean') return v ? 'Yes' : 'No'
  if (f?.type === 'timestamp' && typeof v === 'string') return dateTime(v)
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

// -- create / edit form, generated from the field definitions --
const form = reactive({ open: false, id: '', values: {} as Record<string, string | boolean>, errors: {} as Record<string, string> })
const writable = computed(() => (table.value?.fields ?? []).filter((f) => f.writable))
const readOnly = computed(() => !!form.id && !can('update'))
function toInput(f: FieldDef, v: unknown): string | boolean {
  if (f.type === 'boolean') return v === true
  if (v === null || v === undefined) return ''
  if (f.type === 'json') return JSON.stringify(v, null, 2)
  if (f.type === 'timestamp' && typeof v === 'string') return v.slice(0, 16)
  return String(v)
}
function openForm(row?: Row) {
  const values: Record<string, string | boolean> = {}
  for (const f of table.value?.fields ?? []) values[f.name] = toInput(f, row?.[f.name])
  Object.assign(form, { open: true, id: row?.id ?? '', values, errors: {} })
}
function parse(f: FieldDef, raw: string | boolean): { ok: true; value: unknown } | { ok: false; error: string } {
  if (f.type === 'boolean') return { ok: true, value: raw === true }
  const s = String(raw).trim()
  if (s === '') return f.nullable ? { ok: true, value: null } : { ok: false, error: 'Required' }
  switch (f.type) {
    case 'integer': case 'bigint': return /^-?\d+$/.test(s) ? { ok: true, value: Number(s) } : { ok: false, error: 'Whole number' }
    case 'numeric': return /^-?\d+(\.\d+)?$/.test(s) ? { ok: true, value: s } : { ok: false, error: 'Number, e.g. 12.50' }
    case 'json': try { return { ok: true, value: JSON.parse(s) } } catch { return { ok: false, error: 'Invalid JSON' } }
    case 'timestamp': return { ok: true, value: new Date(s).toISOString() }
    case 'string': return f.max_length && s.length > f.max_length ? { ok: false, error: `At most ${f.max_length} characters` } : { ok: true, value: s }
    default: return { ok: true, value: s }
  }
}
async function submitForm() {
  if (!table.value) return
  const body: Record<string, unknown> = {}
  form.errors = {}
  for (const f of writable.value) {
    const p = parse(f, form.values[f.name])
    if (!p.ok) { form.errors[f.name] = p.error; continue }
    if (form.id) {
      const before = rows.value.find((r) => r.id === form.id)?.[f.name] ?? null
      if (JSON.stringify(before) !== JSON.stringify(p.value)) body[f.name] = p.value
    } else if (p.value !== null) body[f.name] = p.value
  }
  if (Object.keys(form.errors).length) return
  if (form.id && !Object.keys(body).length) { form.open = false; return }
  const name = table.value.name, id = form.id
  const ok = await run('save', () => (id ? api.updateRow(name, id, body) : api.createRow(name, body)), id ? 'Record updated.' : 'Record created.')
  if (ok) { form.open = false; await loadRows() }
}
async function remove(row: Row) {
  if (!table.value) return
  const name = table.value.name
  if (!(await ask({ title: 'Delete this record?', body: `This permanently removes the record from “${name}”.`, confirmLabel: 'Delete', danger: true }))) return
  if (await run(`del-${row.id}`, () => api.deleteRow(name, row.id), 'Record deleted.')) { form.open = false; await loadRows() }
}
function exportCsv() {
  if (!table.value) return
  download(`${table.value.name}-page-${offset.value / pageSize + 1}.csv`, toCsv(rows.value, ['id', ...table.value.fields.map((f) => f.name)]), 'text/csv')
  toast('Exported the rows on this page.')
}
const enumOptions = (f: FieldDef) => [...(f.nullable ? [{ value: '', label: '—' }] : []), ...(f.enum_values ?? []).map((v) => ({ value: v, label: v }))]
const inputType = (f: FieldDef) => (f.type === 'date' ? 'date' : f.type === 'timestamp' ? 'datetime-local' : 'text')
const hint = (f: FieldDef) => [title(f.type), f.nullable ? 'optional' : 'required', f.is_unique && 'unique', f.is_sensitive && 'sensitive', f.ref_table && `→ ${f.ref_table}`].filter(Boolean).join(' · ')
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Data tables</h1><p class="muted">Records in the custom tables your platform admin defined. You only see the fields your role can read.</p></div>
      <div class="actions">
        <UiButton icon="upload" :disabled="!can('create')" @click="router.push({ path: '/imports', query: { table: current } })">Import</UiButton>
        <UiButton icon="download" :disabled="!rows.length" @click="exportCsv">Export CSV</UiButton>
        <UiButton variant="primary" icon="plus" :disabled="!can('create')" @click="openForm()">New record</UiButton>
      </div>
    </div>

    <LoadState :loading="loading" :error="error" :ready="!!tables.length" @retry="reload">
      <EmptyState v-if="!tables.length" icon="database" title="No tables available" body="Either no tables have been defined yet, or your role has no read access to any of them." />
      <template v-else>
        <div class="tabs" role="tablist" aria-label="Tables">
          <button v-for="tb in tables" :key="tb.name" role="tab" class="tab" :class="{ on: current === tb.name }" :aria-selected="current === tb.name" @click="current = tb.name">{{ title(tb.name) }}</button>
        </div>
        <UiCard v-if="table" :title="title(table.name)" :caption="`${table.fields.length} fields · you can ${table.actions.join(', ')}`">
          <template #actions><UiButton variant="ghost" icon="refresh" :loading="rowsLoading" @click="loadRows">Refresh</UiButton></template>
          <div class="toolbar">
            <input v-if="table.searchable.length" v-model="q" class="search-input" type="search" :placeholder="`Search ${table.searchable.join(', ')}`" aria-label="Search records" />
            <div class="sort"><UiSelect v-model="sort" label="Sort" :options="sortOptions" /></div>
          </div>
          <p v-if="rowsError" class="note danger toolbar-note" role="alert">{{ rowsError }}</p>
          <DataTable v-else :columns="columns" :rows="rows" row-key="id" clickable :empty="q ? 'No records match your search.' : 'No records yet.'" @select="openForm">
            <template v-for="f in visible" :key="f.name" #[`cell-${f.name}`]="{ row }">
              <UiBadge v-if="f.type === 'enum' && row[f.name]" tone="brand">{{ row[f.name] }}</UiBadge>
              <span v-else :class="{ strong: f === visible[0] }">{{ show(row[f.name], f) }}</span>
            </template>
            <template #cell-__updated="{ row }">{{ dateTime(row.updated_at as string) }}</template>
          </DataTable>
          <div class="pager">
            <span class="caption">{{ rows.length ? `Rows ${offset + 1}–${offset + rows.length}` : '' }}</span>
            <div class="row-actions">
              <UiButton :disabled="offset === 0 || rowsLoading" @click="page(-1)">Previous</UiButton>
              <UiButton :disabled="next === null || rowsLoading" @click="page(1)">Next</UiButton>
            </div>
          </div>
        </UiCard>
      </template>
    </LoadState>

    <UiModal :open="form.open" :title="form.id ? (readOnly ? 'Record' : 'Edit record') : `New ${title(current)} record`" :caption="form.id ? `id ${form.id}` : ''"
             :submit-label="readOnly ? undefined : form.id ? 'Save changes' : 'Create record'" :busy="busy === 'save'" wide @close="form.open = false" @submit="submitForm">
      <div class="grid2">
        <template v-for="f in table?.fields ?? []" :key="f.name">
          <UiCheck v-if="f.type === 'boolean'" v-model="form.values[f.name] as boolean" :label="title(f.name)" :hint="hint(f)" :disabled="readOnly || !f.writable" />
          <UiSelect v-else-if="f.type === 'enum'" v-model="form.values[f.name] as string" :label="title(f.name)" :options="enumOptions(f)" :hint="hint(f)" :disabled="readOnly || !f.writable" :required="!f.nullable" />
          <UiInput v-else v-model="form.values[f.name] as string" :label="title(f.name)" :type="inputType(f)" :hint="hint(f)" :error="form.errors[f.name]"
                   :multiline="f.type === 'json' || f.type === 'text'" :mono="f.type === 'json' || f.type === 'uuid' || f.type === 'reference'"
                   :inputmode="['integer', 'bigint'].includes(f.type) ? 'numeric' : f.type === 'numeric' ? 'decimal' : undefined"
                   :disabled="readOnly || !f.writable" :required="!f.nullable" :class="{ wide: f.type === 'json' || f.type === 'text' }" />
        </template>
      </div>
      <p v-if="form.id && writable.length < (table?.fields.length ?? 0)" class="caption">Greyed-out fields are read-only for your role.</p>
      <template #footer-start>
        <UiButton v-if="form.id && can('delete')" variant="ghost" icon="trash" :loading="busy === `del-${form.id}`" @click="remove(rows.find((r) => r.id === form.id)!)">Delete</UiButton>
      </template>
    </UiModal>
  </div>
</template>

<style scoped>
.sort { width: 220px; }
.sort :deep(.label) { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
.toolbar-note { margin: 0 24px 16px; }
.pager { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 24px; border-top: 1px solid var(--border-default); flex-wrap: wrap; }
.strong { font-weight: 600; }
.wide { grid-column: 1 / -1; }
@media (max-width: 767px) { .sort { width: 100%; } .pager { padding: 12px 16px; } .toolbar-note { margin: 0 16px 14px; } }
</style>
