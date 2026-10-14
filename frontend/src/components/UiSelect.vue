<script setup lang="ts">
import { useId } from 'vue'
export interface Option { value: string; label: string; disabled?: boolean }
defineProps<{ label: string; options: (Option | string)[]; hint?: string; disabled?: boolean; required?: boolean; placeholder?: string }>()
const model = defineModel<string>({ default: '' })
const id = useId()
const norm = (o: Option | string): Option => (typeof o === 'string' ? { value: o, label: o } : o)
</script>

<template>
  <div class="field">
    <label :for="id" class="label">{{ label }}<span v-if="required" class="req" aria-hidden="true"> *</span></label>
    <select :id="id" v-model="model" :disabled="disabled" :required="required">
      <option v-if="placeholder" value="" disabled>{{ placeholder }}</option>
      <option v-for="o in options.map(norm)" :key="o.value" :value="o.value" :disabled="o.disabled">{{ o.label }}</option>
    </select>
    <p v-if="hint" class="caption">{{ hint }}</p>
  </div>
</template>

<style scoped>
.field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.req { color: var(--danger); }
select {
  width: 100%; padding: 10px 36px 10px 12px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); font-size: 16px;
  background: var(--bg-surface) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' fill='none' stroke='%23667085' stroke-width='2' viewBox='0 0 24 24'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E") no-repeat right 12px center;
  appearance: none; cursor: pointer;
}
select:focus { outline: 2px solid var(--brand-soft); border-color: var(--brand-primary); }
select:disabled { background-color: var(--bg-subtle); color: var(--text-secondary); cursor: default; }
@media (min-width: 768px) { select { font-size: 14px; } }
</style>
