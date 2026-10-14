<script setup lang="ts">
import { useId } from 'vue'
defineProps<{ label: string; type?: string; placeholder?: string; autocomplete?: string; inputmode?: 'text' | 'numeric' | 'email'; required?: boolean; hint?: string }>()
const model = defineModel<string>({ default: '' })
const id = useId()
</script>

<template>
  <div class="field">
    <label :for="id" class="label">{{ label }}</label>
    <input :id="id" v-model="model" :type="type ?? 'text'" :placeholder="placeholder" :autocomplete="autocomplete"
           :inputmode="inputmode" :required="required" />
    <p v-if="hint" class="caption">{{ hint }}</p>
  </div>
</template>

<style scoped>
.field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
input { width: 100%; padding: 10px 12px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); background: var(--bg-surface); font-size: 16px; }
input:focus { outline: 2px solid var(--brand-soft); border-color: var(--brand-primary); }
@media (min-width: 768px) { input { font-size: 14px; } }
</style>
