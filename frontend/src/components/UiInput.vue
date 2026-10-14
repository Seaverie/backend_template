<script setup lang="ts">
import { useId } from 'vue'
defineProps<{
  label: string; type?: string; placeholder?: string; autocomplete?: string; inputmode?: 'text' | 'numeric' | 'decimal' | 'email' | 'url'
  required?: boolean; hint?: string; multiline?: boolean; rows?: number; disabled?: boolean; maxlength?: number; error?: string; mono?: boolean
}>()
const model = defineModel<string>({ default: '' })
const id = useId()
</script>

<template>
  <div class="field">
    <label :for="id" class="label">{{ label }}<span v-if="required" class="req" aria-hidden="true"> *</span></label>
    <textarea v-if="multiline" :id="id" v-model="model" :rows="rows ?? 3" :placeholder="placeholder" :required="required"
              :disabled="disabled" :maxlength="maxlength" :class="{ mono, bad: !!error }" />
    <input v-else :id="id" v-model="model" :type="type ?? 'text'" :placeholder="placeholder" :autocomplete="autocomplete"
           :inputmode="inputmode" :required="required" :disabled="disabled" :maxlength="maxlength" :class="{ mono, bad: !!error }" />
    <p v-if="error" class="caption err">{{ error }}</p>
    <p v-else-if="hint" class="caption">{{ hint }}</p>
  </div>
</template>

<style scoped>
.field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.req { color: var(--danger); }
input, textarea { width: 100%; padding: 10px 12px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); background: var(--bg-surface); font-size: 16px; font-family: inherit; resize: vertical; }
input:focus, textarea:focus { outline: 2px solid var(--brand-soft); border-color: var(--brand-primary); }
input:disabled, textarea:disabled { background: var(--bg-subtle); color: var(--text-secondary); }
.mono { font-family: var(--font-mono); }
.bad { border-color: var(--danger); }
.err { color: var(--danger); }
@media (min-width: 768px) { input, textarea { font-size: 14px; } }
</style>
