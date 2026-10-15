<script setup lang="ts">
// A JSON text box that reports whether its content parses. v-model is the text.
import { computed, useId } from 'vue'
const props = defineProps<{ label: string; hint?: string; rows?: number; allowEmpty?: boolean }>()
const model = defineModel<string>({ default: '' })
const id = useId()
const error = computed(() => {
  if (!model.value.trim()) return props.allowEmpty ? '' : 'Required.'
  try { JSON.parse(model.value); return '' } catch (e) { return (e as Error).message }
})
defineExpose({ error })
</script>

<template>
  <div class="field">
    <label :for="id" class="label">{{ label }}</label>
    <textarea :id="id" v-model="model" :rows="rows ?? 8" spellcheck="false" class="mono" :class="{ bad: !!error }" />
    <p v-if="error" class="caption err">{{ error }}</p>
    <p v-else-if="hint" class="caption">{{ hint }}</p>
  </div>
</template>

<style scoped>
.field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
textarea { width: 100%; padding: 10px 12px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); background: var(--bg-surface); font-size: 13px; resize: vertical; }
.bad { border-color: var(--danger); }
.err { color: var(--danger); }
</style>
