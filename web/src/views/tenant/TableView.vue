<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, api, idemKey } from '@/api/client'
import { can, hasModule } from '@/api/me'
import { num, opt, str, useBusy, useLoader } from '@/composables'
import { dateTime, title } from '@/format'
import { ask, errText, toast } from '@/ui'
import DataTable from '@/components/DataTable.vue'
import FieldInput, { type FieldDef } from '@/components/FieldInput.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiPager from '@/components/UiPager.vue'
import UiSelect from '@/components/UiSelect.vue'
import UiTabs from '@/components/UiTabs.vue'

const props = defineProps<{ tableKey: string }>()
const tableKey = computed(() => props.tableKey)
const route = useRoute()
const router = useRouter()
const tab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'rows')
const table = ref<any>(null)
const allTables = ref<any[]>([])
const { busy, run } = useBusy()
const res = computed(() => `data.${table.value?.key ?? ''}`)
const schemaAdmin = computed(() => can('data_schema', 'manage'))
const readable = computed<FieldDef[]>(() => (table.value?.fields ?? []).filter((f: any) => f.can_read))

const meta = useLoader(async () => {
  table.value = await api.get(`/data/tables/${tableKey.value}`)
  if (schemaAdmin.value) allTables.value = (await api.get('/data/tables')).items
})

// ---- rows ---------------------------------------------------------------------------------------
const rows = ref<any[]>([])
const total = ref<number | null>(null)
const offset = ref(0)
const limit = 50
const q = ref('')
const sort = reactive({ field: '', dir: 'desc' })
const filters = ref<{ field: string; op: string; value: string }[]>([])
const ops = [
  { value: 'eq', label: 'is' }, { value: 'ne', label: 'is not' }, { value: 'contains', label: 'contains' }, { value: 'starts_with', label: 'starts with' },
  { value: 'gt', label: '>' }, { value: 'gte', label: '≥' }, { value: 'lt', label: '<' }, { value: 'lte', label: '≤' },
  { value: 'is_empty', label: 'is empty' }, { value: 'is_not_empty', label: 'is not empty' }, { value: 'has', label: 'has option' },
]
function filterValue(f: { field: string; op: string; value: string }) {
  if (f.op === 'is_empty' || f.op === 'is_not_empty') return null
  const def = readable.value.find((x) => x.key === f.field)
  if (def && ['integer', 'decimal'].includes(def.type)) return num(f.value)
  if (def?.type === 'boolean') return f.value === 'true'
  return f.value
}
const list = useLoader(async () => {
  if (!table.value) await meta.reload()
  const body = {
    q: opt(q.value), limit, offset: offset.value,
    sort: sort.field ? [{ field: sort.field, direction: sort.dir }] : [],
    filters: filters.value.filter((f) => f.field && f.op).map((f) => ({ field: f.field, op: f.op, value: filterValue(f) })),
  }
  const r = await api.post(`/data/tables/${tableKey.value}/rows/query`, body)
  rows.value = r.items; total.value = r.total ?? null
})
let timer: ReturnType<typeof setTimeout> | undefined
watch(q, () => { clearTimeout(timer); timer = setTimeout(() => { offset.value = 0; list.reload() }, 300) })
watch(() => [sort.field, sort.dir], () => { offset.value = 0; list.reload() })
watch(offset, () => list.reload())
const columns = computed(() => [
  ...readable.value.slice(0, 6).map((f, i) => ({ key: f.key, label: f.label, primary: i === 0, align: (['integer', 'decimal'].includes(f.type) ? 'right' : 'left') as 'right' | 'left' })),
  { key: '_updated', label: 'Updated', hideOnMobile: true },
])
function show(row: any, f: FieldDef) {
  const v = row.data[f.key]
  if (v === null || v === undefined || v === '') return '—'
  if (row.refs?.[f.key]) return row.refs[f.key].display ?? String(v).slice(0, 8)
  if (f.type === 'boolean') return v ? 'Yes' : 'No'
  if (f.type === 'datetime') return dateTime(v)
  if (Array.isArray(v)) return v.join(', ')
  if (['reference', 'user', 'file'].includes(f.type)) return String(v).slice(0, 8)
  return String(v)
}

// ---- row editor ---------------------------------------------------------------------------------
const editor = reactive({ open: false, id: '', version: 0, data: {} as Record<string, any>, refs: {} as Record<string, any>, errors: {} as Record<string, string>, meta: null as any })
function openRow(row?: any) {
  const data: Record<string, any> = {}
  for (const f of readable.value) data[f.key] = row ? row.data[f.key] ?? null : (table.value.fields.find((x: any) => x.key === f.key)?.default_value ?? null)
  Object.assign(editor, { open: true, id: row?.id ?? '', version: row?.version ?? 0, data, refs: row?.refs ?? {}, errors: {}, meta: row ?? null })
}
async function saveRow() {
  editor.errors = {}
  const writable = readable.value.filter((f) => f.can_write).map((f) => f.key)
  const data = Object.fromEntries(Object.entries(editor.data).filter(([k]) => writable.includes(k)))
  busy.value = 'row'
  try {
    if (editor.id) await api.patch(`/data/tables/${tableKey.value}/rows/${editor.id}`, { data, expected_version: editor.version })
    else await api.post(`/data/tables/${tableKey.value}/rows`, { data }, { 'Idempotency-Key': idemKey() })
    toast(editor.id ? 'Row saved.' : 'Row added.')
    editor.open = false
    await list.reload()
  } catch (e) {
    if (e instanceof ApiError && e.status === 422) { editor.errors = e.fields; toast(e.message, 'err') }
    else if (e instanceof ApiError && e.status === 409) toast('Someone else changed this row while you were editing. Close and open it again to see their change.', 'err')
    else toast(errText(e), 'err')
  } finally { busy.value = '' }
}
async function deleteRow() {
  if (!await ask({ title: 'Delete this row?', confirmLabel: 'Delete', danger: true })) return
  const ok = await run('row', () => api.del(`/data/tables/${tableKey.value}/rows/${editor.id}`), 'Row deleted.')
  if (ok) { editor.open = false; list.reload() }
}
async function exportCsv() { await run('export', () => api.download(`/data/tables/${tableKey.value}/export`, `${tableKey.value}.csv`, { q: opt(q.value) })) }

// ---- fields -------------------------------------------------------------------------------------
const types = ['text', 'long_text', 'integer', 'decimal', 'boolean', 'date', 'datetime', 'email', 'url', 'phone', 'select', 'multi_select', 'reference', 'file', 'user']
const fe = reactive({ open: false, existing: false, key: '', label: '', type: 'text', required: false, unique: false, choices: '', min: '', max: '', min_length: '', max_length: '', decimals: '', reference_table: '', on_delete: 'restrict', help_text: '', default_value: '' })
function editField(f?: any) {
  Object.assign(fe, {
    open: true, existing: !!f, key: f?.key ?? '', label: f?.label ?? '', type: f?.type ?? 'text', required: f?.required ?? false, unique: f?.unique ?? false,
    choices: (f?.choices ?? []).join('\n'), min: str(f?.min), max: str(f?.max), min_length: str(f?.min_length), max_length: str(f?.max_length), decimals: str(f?.decimals),
    reference_table: f?.reference_table ?? '', on_delete: f?.on_delete ?? 'restrict', help_text: f?.help_text ?? '',
    default_value: f?.default_value === null || f?.default_value === undefined ? '' : typeof f.default_value === 'string' ? f.default_value : JSON.stringify(f.default_value),
  })
}
const fieldKeyFrom = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').replace(/^(\d)/, 'f_$1').slice(0, 63)
function parseDefault() {
  const t = fe.default_value.trim()
  if (!t) return null
  if (['integer', 'decimal'].includes(fe.type)) return num(t)
  if (fe.type === 'boolean') return t === 'true'
  if (fe.type === 'multi_select') { try { return JSON.parse(t) } catch { return t.split(',').map((x) => x.trim()) } }
  return t
}
async function saveField() {
  const common = {
    label: fe.label.trim(), required: fe.required, unique: fe.unique, help_text: opt(fe.help_text),
    choices: ['select', 'multi_select'].includes(fe.type) ? fe.choices.split('\n').map((c) => c.trim()).filter(Boolean) : undefined,
    min: num(fe.min) ?? undefined, max: num(fe.max) ?? undefined, min_length: num(fe.min_length) ?? undefined, max_length: num(fe.max_length) ?? undefined,
    decimals: num(fe.decimals) ?? undefined,
  }
  const def = parseDefault()
  const ok = await run('field', async () => {
    if (fe.existing) await api.patch(`/data/tables/${tableKey.value}/fields/${fe.key}`, { ...common, ...(def === null ? { clear_default: true } : { default_value: def }) })
    else await api.post(`/data/tables/${tableKey.value}/fields`, { ...common, key: fe.key || fieldKeyFrom(fe.label), type: fe.type, default_value: def,
      reference_table: fe.type === 'reference' ? fe.reference_table : undefined, on_delete: fe.type === 'reference' ? fe.on_delete : undefined })
    await meta.reload()
  }, 'Field saved.')
  if (ok) fe.open = false
}
async function deleteField() {
  if (!await ask({ title: `Delete the field “${fe.label}”?`, body: 'Its value is removed from every row. This cannot be undone.', confirmLabel: 'Delete field', danger: true, typeToConfirm: fe.key })) return
  const ok = await run('field', async () => { await api.del(`/data/tables/${tableKey.value}/fields/${fe.key}`, { confirm: fe.key }); await meta.reload() }, 'Field deleted.')
  if (ok) fe.open = false
}

// ---- table settings -----------------------------------------------------------------------------
const ts = reactive({ name: '', description: '', display_field: '' })
watch(table, (t) => { if (t) Object.assign(ts, { name: t.name, description: t.description ?? '', display_field: t.display_field ?? '' }) }, { immediate: true })
async function saveTable() {
  await run('table', async () => { table.value = await api.patch(`/data/tables/${tableKey.value}`, { name: ts.name.trim(), description: ts.description, display_field: ts.display_field || undefined }) }, 'Table saved.')
}
async function deleteTable() {
  if (!await ask({ title: `Delete the table “${table.value.name}”?`, body: `All ${table.value.row_count} rows are deleted. This cannot be undone.`, confirmLabel: 'Delete table', danger: true, typeToConfirm: table.value.key })) return
  if (await run('table', () => api.del(`/data/tables/${tableKey.value}`, { confirm: table.value.key }), 'Table deleted.')) router.push('/data')
}
</script>

<template>
  <div class="page">
    <LoadState :loading="meta.loading.value" :error="meta.error.value" :ready="!!table" @retry="meta.reload">
      <template v-if="table">
        <PageHeader :title="table.name" :caption="table.description ?? undefined" back="/data">
          <UiButton icon="download" :loading="busy === 'export'" @click="exportCsv">Export CSV</UiButton>
          <RouterLink v-if="hasModule('imports') && can('imports', 'create')" :to="{ path: '/imports', query: { table: table.key } }"><UiButton icon="import">Import</UiButton></RouterLink>
          <UiButton v-if="can(res, 'create')" icon="plus" variant="primary" @click="openRow()">Add row</UiButton>
        </PageHeader>
        <UiTabs v-model="tab" :tabs="[{ key: 'rows', label: `Rows (${total ?? table.row_count})` }, ...(schemaAdmin ? [{ key: 'fields', label: 'Fields' }, { key: 'settings', label: 'Settings' }] : [])]" />

        <UiCard v-if="tab === 'rows'">
          <div class="toolbar" style="padding-top: 16px">
            <input v-model="q" class="search-input" type="search" placeholder="Search text fields…" aria-label="Search rows" />
            <select v-model="sort.field" class="search-input narrow" aria-label="Sort by"><option value="">Newest first</option><option v-for="f in readable" :key="f.key" :value="f.key">Sort: {{ f.label }}</option></select>
            <select v-if="sort.field" v-model="sort.dir" class="search-input narrow" aria-label="Direction"><option value="asc">Ascending</option><option value="desc">Descending</option></select>
            <UiButton icon="settings" variant="ghost" @click="filters.push({ field: readable[0]?.key ?? '', op: 'eq', value: '' })">Filter</UiButton>
          </div>
          <div v-if="filters.length" class="filters">
            <div v-for="(f, i) in filters" :key="i" class="frow">
              <select v-model="f.field" aria-label="Field"><option v-for="x in readable" :key="x.key" :value="x.key">{{ x.label }}</option></select>
              <select v-model="f.op" aria-label="Condition"><option v-for="o in ops" :key="o.value" :value="o.value">{{ o.label }}</option></select>
              <input v-if="!['is_empty', 'is_not_empty'].includes(f.op)" v-model="f.value" aria-label="Value" placeholder="Value" />
              <UiButton variant="ghost" icon="close" aria-label="Remove filter" @click="filters.splice(i, 1); list.reload()" />
            </div>
            <div><UiButton variant="primary" @click="offset = 0; list.reload()">Apply filters</UiButton></div>
          </div>
          <LoadState :loading="list.loading.value" :error="list.error.value" :ready="rows.length > 0" @retry="list.reload">
            <DataTable :columns="columns" :rows="rows" row-key="id" clickable empty="No rows." @select="openRow">
              <template v-for="f in readable.slice(0, 6)" :key="f.key" #[`cell-${f.key}`]="{ row }">
                <UiBadge v-if="f.type === 'select' && row.data[f.key]" tone="brand">{{ row.data[f.key] }}</UiBadge>
                <span v-else>{{ show(row, f) }}</span>
              </template>
              <template #cell-_updated="{ row }"><span class="caption">{{ dateTime(row.updated_at) }}</span></template>
            </DataTable>
            <UiPager v-model="offset" :total="total" :limit="limit" :count="rows.length" />
          </LoadState>
        </UiCard>

        <UiCard v-else-if="tab === 'fields'" title="Fields" caption="Each row has these fields. Field-level access is set per role.">
          <template #actions><UiButton icon="plus" variant="primary" @click="editField()">Add field</UiButton></template>
          <DataTable :columns="[{ key: 'label', label: 'Field', primary: true }, { key: 'type', label: 'Type' }, { key: 'rules', label: 'Rules' }]" :rows="(table.fields as any[])" row-key="key" clickable @select="editField">
            <template #cell-label="{ row }"><strong>{{ row.label }}</strong><span class="caption mono">{{ row.key }}</span></template>
            <template #cell-type="{ row }">{{ title(row.type) }}<template v-if="row.reference_table"> → {{ row.reference_table }}</template></template>
            <template #cell-rules="{ row }">
              <div class="chips"><span v-if="row.required" class="chip">Required</span><span v-if="row.unique" class="chip">Unique</span><span v-if="row.key === table.display_field" class="chip">Display</span><span v-if="row.choices" class="chip">{{ row.choices.length }} options</span></div>
            </template>
          </DataTable>
        </UiCard>

        <div v-else class="settings">
          <UiCard title="Table">
            <form class="card-body" @submit.prevent="saveTable">
              <UiInput v-model="ts.name" label="Name" />
              <UiInput v-model="ts.description" label="Description" multiline :rows="2" />
              <UiSelect v-model="ts.display_field" label="Display field" hint="Shown when another table links to a row of this one." :options="[{ value: '', label: 'None' }, ...table.fields.map((f: any) => ({ value: f.key, label: f.label }))]" />
              <div><UiButton type="submit" variant="primary" :loading="busy === 'table'">Save</UiButton></div>
            </form>
          </UiCard>
          <UiCard title="Delete table" tone="danger" caption="Deletes the table, its fields and every row.">
            <div class="card-body"><div><UiButton variant="danger" icon="trash" @click="deleteTable">Delete table</UiButton></div></div>
          </UiCard>
        </div>
      </template>
    </LoadState>

    <UiModal :open="editor.open" :title="editor.id ? 'Edit row' : 'New row'" wide :submit-label="editor.id ? (can(res, 'update') ? 'Save' : undefined) : 'Add row'" :busy="busy === 'row'" @close="editor.open = false" @submit="saveRow">
      <div class="grid2">
        <FieldInput v-for="f in readable" :key="f.key" v-model="editor.data[f.key]" :field="f" :ref-label="editor.refs[f.key]?.display" :error="editor.errors[f.key]"
                    :class="{ wide: f.type === 'long_text' || f.type === 'multi_select' }" />
      </div>
      <p v-if="editor.meta" class="caption">Version {{ editor.meta.version }} · created {{ dateTime(editor.meta.created_at) }} · updated {{ dateTime(editor.meta.updated_at) }}</p>
      <template v-if="editor.id && can(res, 'delete')" #footer-start><UiButton variant="ghost" icon="trash" @click="deleteRow">Delete</UiButton></template>
    </UiModal>

    <UiModal :open="fe.open" :title="fe.existing ? `Field: ${fe.label}` : 'New field'" wide submit-label="Save field" :busy="busy === 'field'" :submit-disabled="!fe.label.trim()" @close="fe.open = false" @submit="saveField">
      <div class="grid3">
        <UiInput v-model="fe.label" label="Label" required />
        <UiInput v-model="fe.key" label="Key" mono :disabled="fe.existing" :placeholder="fieldKeyFrom(fe.label) || 'made from the label'" />
        <UiSelect v-model="fe.type" label="Type" :disabled="fe.existing" :options="types.map((t) => ({ value: t, label: title(t) }))" />
      </div>
      <UiInput v-if="['select', 'multi_select'].includes(fe.type)" v-model="fe.choices" label="Options" multiline :rows="4" hint="One per line." />
      <div v-if="fe.type === 'reference'" class="grid2">
        <UiSelect v-model="fe.reference_table" label="Links to table" :disabled="fe.existing" :options="[{ value: '', label: 'Choose' }, ...allTables.map((t) => ({ value: t.key, label: t.name }))]" />
        <UiSelect v-model="fe.on_delete" label="When the linked row is deleted" :disabled="fe.existing" :options="[{ value: 'restrict', label: 'Block the delete' }, { value: 'set_null', label: 'Clear this field' }]" />
      </div>
      <div v-if="['integer', 'decimal'].includes(fe.type)" class="grid3">
        <UiInput v-model="fe.min" label="Minimum" inputmode="decimal" /><UiInput v-model="fe.max" label="Maximum" inputmode="decimal" />
        <UiInput v-if="fe.type === 'decimal'" v-model="fe.decimals" label="Decimal places" inputmode="numeric" />
      </div>
      <div v-if="['text', 'long_text'].includes(fe.type)" class="grid2"><UiInput v-model="fe.min_length" label="Min length" inputmode="numeric" /><UiInput v-model="fe.max_length" label="Max length" inputmode="numeric" /></div>
      <UiInput v-if="!['reference', 'file', 'user'].includes(fe.type)" v-model="fe.default_value" label="Default value" :hint="fe.type === 'boolean' ? 'true or false' : fe.type === 'multi_select' ? 'Comma-separated options' : undefined" />
      <UiInput v-model="fe.help_text" label="Help text" />
      <UiCheck v-model="fe.required" label="Required" />
      <UiCheck v-if="!['long_text', 'boolean', 'multi_select'].includes(fe.type)" v-model="fe.unique" label="Unique" hint="No two rows can have the same value." />
      <template v-if="fe.existing" #footer-start><UiButton variant="ghost" icon="trash" @click="deleteField">Delete field</UiButton></template>
    </UiModal>
  </div>
</template>

<style scoped>
.narrow { flex: 0 1 180px !important; }
.filters { display: grid; gap: 8px; padding: 0 24px 16px; }
.frow { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.frow select, .frow input { padding: 8px 10px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); background: var(--bg-surface); font-size: 14px; }
.frow input { flex: 1 1 160px; }
.wide { grid-column: 1 / -1; }
.settings { display: grid; gap: 24px; max-width: 720px; }
</style>
