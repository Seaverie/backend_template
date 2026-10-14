<script setup lang="ts">
defineProps<{ label: string; value: string; note?: string }>()
</script>

<template>
  <div class="stat">
    <p class="label-text">{{ label }}</p>
    <p class="value">{{ value }}</p>
    <p v-if="note" class="caption">{{ note }}</p>
  </div>
</template>

<style scoped>
.stat { background: var(--bg-surface); border: 1px solid var(--border-default); border-radius: var(--radius-lg); padding: 20px; display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.label-text { font-size: 13px; line-height: 18px; color: var(--text-secondary); }
.value { font-size: 28px; line-height: 36px; font-weight: 600; overflow-wrap: anywhere; }
@media (max-width: 767px) { .stat { padding: 14px; gap: 4px; } .value { font-size: 22px; line-height: 28px; } }
</style>
