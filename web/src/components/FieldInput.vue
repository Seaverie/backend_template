<script setup lang="ts">
// One input for a custom-table field, chosen by its type. The model is the JSON value the API takes.
import { computed, onMounted, ref, useId } from 'vue'
import { api } from '@/api/client'
import { can, hasModule } from '@/api/me'

export interface FieldDef {
  key: string; label: string; type: string; required: boolean; choices: string[] | null; help_text: string | null
  reference_table: string | null; can_write: boolean; decimals?: number | null; min?: number | null; max?: number | null; max_length?: number | null
}
const props = defineProps<{ field: FieldDef; refLabel?: string | null; error?: string }>()
const model = defineModel<any>()
const id = useId()
const disabled = computed(() => !props.field.can_write)

// Text-ish inputs work on a string copy of the value.
const text = computed({
  get: () => (model.value === null || model.value === undefined ? '' : String(model.value)),
  set: (s: string) => {
    const t = props.field.type
    if (s === '') model.value = null
    else if (t === 'integer' || t === 'decimal') { const n = Number(s); model.value = Number.isFinite(n) && s.trim() !== '' && !s.endsWith('.') ? n : s }
    else model.value = s
  },
})
const dt = computed({
  get: () => { if (!model.value) return ''; const d = new Date(model.value); if (isNaN(d.getTime())) return ''; const p = (n: number) => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}` },
  set: (s: string) => { model.value = s ? new Date(s).toISOString() : null },
})
const multi = computed(() => (Array.isArray(model.value) ? model.value as string[] : []))
function toggle(c: string) { const a = multi.value; model.value = a.includes(c) ? a.filter((x) => x !== c) : [...a, c] }

// Reference / user pickers load their options on demand.
const options = ref<{ value: string; label: string }[]>([])
const search = ref('')
async function loadRefs() {
  if (props.field.type === 'reference' && props.field.reference_table) {
    const r = await api.get(`/data/tables/${props.field.reference_table}/rows`, { q: search.value || undefined, limit: 50 }).catch(() => null)
    const table = await api.get(`/data/tables/${props.field.reference_table}`).catch(() => null)
    const df = table?.display_field
    options.value = (r?.items ?? []).map((row: any) => ({ value: row.id, label: df && row.data[df] != null ? String(row.data[df]) : row.id.slice(0, 8) }))
  } else if (props.field.type === 'user' && can('users')) {
    const r = await api.get('/users').catch(() => null)
    options.value = (r?.items ?? []).map((u: any) => ({ value: u.id, label: u.full_name || u.email }))
  }
  if (model.value && !options.value.some((o) => o.value === model.value)) options.value.unshift({ value: model.value, label: props.refLabel || String(model.value).slice(0, 8) })
}
onMounted(() => { if (props.field.type === 'reference' || props.field.type === 'user') loadRefs() })
const uploading = ref(false)
async function upload(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (!f) return
  uploading.value = true
  try { const fd = new FormData(); fd.append('file', f); const r = await api.upload('/files?purpose=data', fd); model.value = r.id; options.value = [{ value: r.id, label: r.filename }] }
  finally { uploading.value = false }
}
</script>

<template>
  <div class="field">
    <label v-if="field.type !== 'boolean'" :for="id" class="label">{{ field.label }}<span v-if="field.required" class="req" aria-hidden="true"> *</span></label>
    <textarea v-if="field.type === 'long_text'" :id="id" v-model="text" rows="3" :disabled="disabled" :class="{ bad: error }" />
    <label v-else-if="field.type === 'boolean'" class="check"><input :id="id" type="checkbox" :checked="!!model" :disabled="disabled" @change="model = ($event.target as HTMLInputElement).checked" /> {{ field.label }}</label>
    <select v-else-if="field.type === 'select'" :id="id" v-model="text" :disabled="disabled" :class="{ bad: error }">
      <option value="">—</option><option v-for="c in field.choices ?? []" :key="c" :value="c">{{ c }}</option>
    </select>
    <div v-else-if="field.type === 'multi_select'" class="multi">
      <button v-for="c in field.choices ?? []" :key="c" type="button" class="chip" :class="{ on: multi.includes(c) }" :disabled="disabled" @click="toggle(c)">{{ c }}</button>
    </div>
    <input v-else-if="field.type === 'date'" :id="id" v-model="text" type="date" :disabled="disabled" :class="{ bad: error }" />
    <input v-else-if="field.type === 'datetime'" :id="id" v-model="dt" type="datetime-local" :disabled="disabled" :class="{ bad: error }" />
    <template v-else-if="field.type === 'reference' || field.type === 'user'">
      <input v-if="field.type === 'reference'" v-model="search" type="search" class="small" placeholder="Search…" :disabled="disabled" @input="loadRefs" />
      <select :id="id" v-model="text" :disabled="disabled" :class="{ bad: error }">
        <option value="">—</option><option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option>
      </select>
    </template>
    <div v-else-if="field.type === 'file'" class="filerow">
      <input :id="id" v-model="text" class="mono" placeholder="File ID" :disabled="disabled" />
      <label v-if="hasModule('files') && can('files', 'create') && !disabled" class="up">{{ uploading ? 'Uploading…' : 'Upload' }}<input type="file" class="sr-only" @change="upload" /></label>
    </div>
    <input v-else :id="id" v-model="text" :type="field.type === 'email' ? 'email' : field.type === 'url' ? 'url' : field.type === 'phone' ? 'tel' : 'text'"
           :inputmode="field.type === 'integer' ? 'numeric' : field.type === 'decimal' ? 'decimal' : undefined" :maxlength="field.max_length ?? undefined"
           :disabled="disabled" :class="{ bad: error }" />
    <p v-if="error" class="caption err">{{ error }}</p>
    <p v-else-if="field.help_text" class="caption">{{ field.help_text }}</p>
  </div>
</template>

<style scoped>
.field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.req, .err { color: var(--danger); }
input, select, textarea { width: 100%; padding: 10px 12px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); background: var(--bg-surface); font-size: 14px; font-family: inherit; }
input[type=checkbox] { width: 16px; height: 16px; padding: 0; accent-color: var(--brand-primary); }
.small { padding: 6px 10px; font-size: 13px; }
.bad { border-color: var(--danger); }
.check { display: flex; gap: 8px; align-items: center; cursor: pointer; }
.multi { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { border: 1px solid var(--border-strong); background: var(--bg-surface); cursor: pointer; padding: 4px 10px; }
.chip.on { background: var(--brand-soft); border-color: var(--brand-primary); color: var(--brand-primary); }
.filerow { display: flex; gap: 8px; align-items: center; }
.up { cursor: pointer; color: var(--brand-primary); font-size: 13px; white-space: nowrap; }
</style>
