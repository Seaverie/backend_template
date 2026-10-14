<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import type { FieldSpecIn, FieldType, SystemTable } from '@/api/types'
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
import { title } from '@/format'
import { ask } from '@/ui'

const tables = ref<SystemTable[]>([])
const selectedId = ref('')
const { busy, run } = useBusy()
const { loading, error, reload } = useLoader(async () => {
  tables.value = (await api.systemTables()).items
  if (!tables.value.some((t) => t.id === selectedId.value)) selectedId.value = tables.value[0]?.id ?? ''
})
const selected = computed(() => tables.value.find((t) => t.id === selectedId.value) ?? null)
const tableCols: Column[] = [{ key: 'name', label: 'Table', primary: true }, { key: 'fields', label: 'Fields', align: 'right' }, { key: 'version', label: 'Version', align: 'right' }, { key: 'mode', label: 'API access' }]
const fieldCols: Column[] = [{ key: 'name', label: 'Field', primary: true }, { key: 'type', label: 'Type' }, { key: 'flags', label: 'Rules' }, { key: 'act', label: '', align: 'right' }]

// -- field builder --
const TYPES: FieldType[] = ['string', 'text', 'integer', 'bigint', 'numeric', 'boolean', 'date', 'timestamp', 'uuid', 'json', 'enum', 'reference']
const TEXTY = ['string', 'text', 'enum']
interface Draft { name: string; type: FieldType; required: boolean; unique: boolean; searchable: boolean; sensitive: boolean; enums: string; ref: string; maxLength: string }
const blank = (): Draft => ({ name: '', type: 'string', required: false, unique: false, searchable: false, sensitive: false, enums: '', ref: '', maxLength: '' })
const ident = (s: string) => /^[a-z][a-z0-9_]{0,62}$/.test(s)
function draftError(d: Draft, all: Draft[]): string {
  if (!ident(d.name)) return 'Name: lowercase letters, digits and _, starting with a letter.'
  if (all.filter((x) => x.name === d.name).length > 1) return 'Field names must be unique.'
  if (d.type === 'enum') { const v = d.enums.split(',').map((s) => s.trim()).filter(Boolean); if (!v.length || v.some((x) => !/^[A-Za-z0-9_-]{1,64}$/.test(x))) return 'Enum values: comma-separated, letters/digits/_/-.' }
  if (d.type === 'reference' && !d.ref) return 'Choose the table this field points to.'
  if (d.maxLength && (!/^\d+$/.test(d.maxLength) || Number(d.maxLength) < 1 || Number(d.maxLength) > 10000)) return 'Max length: 1–10000.'
  if (d.unique && ['json', 'text'].includes(d.type)) return 'json and text fields cannot be unique.'
  return ''
}
const toSpec = (d: Draft, forceNullable = false): FieldSpecIn => ({
  name: d.name, type: d.type, nullable: forceNullable || !d.required, unique: d.unique, searchable: TEXTY.includes(d.type) && d.searchable, sensitive: d.sensitive,
  ...(d.type === 'string' && d.maxLength ? { max_length: Number(d.maxLength) } : {}),
  ...(d.type === 'enum' ? { enum_values: d.enums.split(',').map((s) => s.trim()).filter(Boolean) } : {}),
  ...(d.type === 'reference' ? { ref_table: d.ref } : {}),
})

const create = reactive({ open: false, name: '', fields: [blank()] })
const createErrors = computed(() => create.fields.map((d) => draftError(d, create.fields)))
const createValid = computed(() => ident(create.name) && create.name.length <= 60 && create.fields.length > 0 && createErrors.value.every((e) => !e))
async function submitCreate() {
  const name = create.name
  if (await run('create', () => api.createSystemTable(name, create.fields.map((d) => toSpec(d))), `Table ${name} created. Grant roles access with the data.${name} resource.`)) {
    create.open = false; await reload(); selectedId.value = tables.value.find((t) => t.name === name)?.id ?? selectedId.value
  }
}
const addF = reactive({ open: false, fields: [blank()] })
const addErrors = computed(() => addF.fields.map((d) => draftError(d, addF.fields) || (selected.value?.fields.some((f) => f.name === d.name) ? 'A field with this name already exists.' : '')))
async function submitAdd() {
  if (!selected.value) return
  const id = selected.value.id
  if (await run('add', () => api.addSystemFields(id, addF.fields.map((d) => toSpec(d, true))), 'Fields added.')) { addF.open = false; await reload() }
}
async function dropField(name: string) {
  if (!selected.value) return
  const t = selected.value
  if (!(await ask({ title: `Drop field ${name}?`, body: `The column and its data are removed from every tenant's ${t.name} table.`, confirmLabel: 'Drop field', danger: true, typeToConfirm: name }))) return
  if (await run(`drop-${name}`, () => api.dropSystemFields(t.id, [name]), `Field ${name} dropped.`)) await reload()
}
async function deleteTable() {
  if (!selected.value) return
  const t = selected.value
  if (!(await ask({ title: `Delete table ${t.name}?`, body: 'The table and every tenant\'s records in it are permanently deleted.', confirmLabel: 'Delete table', danger: true, typeToConfirm: t.name }))) return
  if (await run('delete', () => api.deleteSystemTable(t.id, t.name), `Table ${t.name} deleted.`)) await reload()
}
const refOptions = computed(() => tables.value.map((t) => ({ value: t.name, label: t.name })))
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Schema &amp; tables</h1><p class="muted">Custom tables shared by every tenant. Each tenant only ever sees its own rows; DDL is generated from validated definitions, never from raw SQL.</p></div>
      <div class="actions"><UiButton variant="primary" icon="plus" @click="Object.assign(create, { open: true, name: '', fields: [blank()] })">New table</UiButton></div>
    </div>
    <LoadState :loading="loading" :error="error" :ready="!!tables.length" @retry="reload">
      <EmptyState v-if="!tables.length" icon="database" title="No custom tables yet" body="Define a table and its fields; tenants can then store records in it through the API and the dashboard.">
        <UiButton variant="primary" icon="plus" @click="Object.assign(create, { open: true, name: '', fields: [blank()] })">New table</UiButton>
      </EmptyState>
      <div v-else class="split">
        <UiCard title="Tables" :caption="`${tables.length} tables`">
          <DataTable :columns="tableCols" :rows="tables" row-key="id" :selected="selectedId" clickable @select="(t) => (selectedId = t.id)">
            <template #cell-name="{ row }"><strong class="mono">{{ row.name }}</strong></template>
            <template #cell-fields="{ row }">{{ row.fields.length }}</template>
            <template #cell-mode="{ row }"><UiBadge :tone="row.crud_mode === 'full' ? 'success' : row.crud_mode === 'disabled' ? 'neutral' : 'info'">{{ title(row.crud_mode) }}</UiBadge></template>
          </DataTable>
        </UiCard>
        <UiCard v-if="selected" :title="selected.name" :caption="`Version ${selected.version} · role resource data.${selected.name}`">
          <template #actions>
            <UiButton icon="plus" :disabled="loading" @click="Object.assign(addF, { open: true, fields: [blank()] })">Add fields</UiButton>
            <UiButton variant="ghost" icon="trash" :loading="busy === 'delete'" :disabled="loading" @click="deleteTable">Delete</UiButton>
          </template>
          <DataTable :columns="fieldCols" :rows="selected.fields" row-key="name">
            <template #cell-name="{ row }"><span class="mono">{{ row.name }}</span></template>
            <template #cell-type="{ row }">{{ row.type }}<span v-if="row.max_length" class="caption"> ({{ row.max_length }})</span><span v-if="row.ref_table" class="caption"> → {{ row.ref_table }}</span></template>
            <template #cell-flags="{ row }">
              <span class="chips">
                <span v-if="!row.nullable" class="chip">required</span><span v-if="row.is_unique" class="chip">unique</span>
                <span v-if="row.is_searchable" class="chip">searchable</span><span v-if="row.is_sensitive" class="chip">sensitive</span>
                <span v-if="row.enum_values" class="chip">{{ row.enum_values.join(' | ') }}</span>
              </span>
            </template>
            <template #cell-act="{ row }"><UiButton variant="ghost" icon="trash" :aria-label="`Drop ${row.name}`" :loading="busy === `drop-${row.name}`" :disabled="selected.fields.length === 1" @click="dropField(row.name)" /></template>
          </DataTable>
        </UiCard>
      </div>
    </LoadState>

    <UiModal :open="create.open" title="New table" submit-label="Create table" wide :busy="busy === 'create'" :submit-disabled="!createValid" @close="create.open = false" @submit="submitCreate">
      <UiInput v-model="create.name" label="Table name" required mono placeholder="customers" hint="Lowercase letters, digits and _, up to 60 characters." :error="create.name && !ident(create.name) ? 'Invalid name.' : ''" />
      <p class="section-label">Fields</p>
      <div v-for="(d, i) in create.fields" :key="i" class="fdraft">
        <div class="frow">
          <UiInput v-model="d.name" label="Name" mono />
          <UiSelect v-model="d.type" label="Type" :options="TYPES" />
          <UiButton variant="ghost" icon="trash" :aria-label="`Remove field ${i + 1}`" :disabled="create.fields.length === 1" @click="create.fields.splice(i, 1)" />
        </div>
        <UiInput v-if="d.type === 'enum'" v-model="d.enums" label="Allowed values" placeholder="standard, gold, vip" />
        <UiSelect v-if="d.type === 'reference'" v-model="d.ref" label="Points to table" :options="refOptions" placeholder="Choose a table" />
        <UiInput v-if="d.type === 'string'" v-model="d.maxLength" label="Max length" inputmode="numeric" placeholder="255" />
        <div class="flags">
          <UiCheck v-model="d.required" label="Required" /><UiCheck v-model="d.unique" label="Unique" />
          <UiCheck v-if="TEXTY.includes(d.type)" v-model="d.searchable" label="Searchable" /><UiCheck v-model="d.sensitive" label="Sensitive (masked in logs)" />
        </div>
        <p v-if="d.name && createErrors[i]" class="caption err">{{ createErrors[i] }}</p>
      </div>
      <div><UiButton variant="ghost" icon="plus" @click="create.fields.push(blank())">Add field</UiButton></div>
    </UiModal>

    <UiModal :open="addF.open" :title="`Add fields to ${selected?.name ?? ''}`" caption="New fields are always optional, because existing rows have no value for them." submit-label="Add fields" wide
             :busy="busy === 'add'" :submit-disabled="addErrors.some((e) => !!e)" @close="addF.open = false" @submit="submitAdd">
      <div v-for="(d, i) in addF.fields" :key="i" class="fdraft">
        <div class="frow">
          <UiInput v-model="d.name" label="Name" mono />
          <UiSelect v-model="d.type" label="Type" :options="TYPES" />
          <UiButton variant="ghost" icon="trash" :aria-label="`Remove field ${i + 1}`" :disabled="addF.fields.length === 1" @click="addF.fields.splice(i, 1)" />
        </div>
        <UiInput v-if="d.type === 'enum'" v-model="d.enums" label="Allowed values" placeholder="a, b, c" />
        <UiSelect v-if="d.type === 'reference'" v-model="d.ref" label="Points to table" :options="refOptions" placeholder="Choose a table" />
        <div class="flags"><UiCheck v-model="d.unique" label="Unique" /><UiCheck v-if="TEXTY.includes(d.type)" v-model="d.searchable" label="Searchable" /><UiCheck v-model="d.sensitive" label="Sensitive" /></div>
        <p v-if="d.name && addErrors[i]" class="caption err">{{ addErrors[i] }}</p>
      </div>
      <div><UiButton variant="ghost" icon="plus" @click="addF.fields.push(blank())">Another field</UiButton></div>
    </UiModal>
  </div>
</template>

<style scoped>
.fdraft { border: 1px solid var(--border-default); border-radius: var(--radius-md); padding: 12px; display: grid; gap: 10px; }
.frow { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr) auto; gap: 10px; align-items: end; }
.flags { display: flex; flex-wrap: wrap; gap: 8px 18px; }
.err { color: var(--danger); }
</style>
