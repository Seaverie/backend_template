<script setup lang="ts">
defineProps<{ label: string; hint?: string; disabled?: boolean; toggle?: boolean }>()
const model = defineModel<boolean>({ default: false })
</script>

<template>
  <label class="check" :class="{ disabled, toggle }">
    <input v-model="model" type="checkbox" :disabled="disabled" :role="toggle ? 'switch' : undefined" />
    <span v-if="toggle" class="track" aria-hidden="true"><span class="thumb" /></span>
    <span class="text"><span>{{ label }}</span><span v-if="hint" class="caption">{{ hint }}</span></span>
  </label>
</template>

<style scoped>
.check { display: flex; gap: 10px; align-items: flex-start; font-size: 14px; cursor: pointer; min-height: 24px; }
.check.disabled { opacity: 0.6; cursor: default; }
.check input { width: 16px; height: 16px; margin: 2px 0 0; flex: none; accent-color: var(--brand-primary); }
.text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.toggle input { position: absolute; opacity: 0; width: 1px; height: 1px; }
.track { width: 34px; height: 20px; border-radius: 999px; background: var(--border-strong); position: relative; flex: none; transition: background 0.15s; }
.thumb { position: absolute; top: 2px; left: 2px; width: 16px; height: 16px; border-radius: 50%; background: #fff; transition: transform 0.15s; box-shadow: 0 1px 2px rgba(0,0,0,.2); }
.toggle input:checked + .track { background: var(--brand-primary); }
.toggle input:checked + .track .thumb { transform: translateX(14px); }
.toggle input:focus-visible + .track { outline: 2px solid var(--brand-primary); outline-offset: 2px; }
</style>
