<script setup lang="ts">
import AppIcon from './AppIcon.vue'
defineProps<{ icon?: string; title: string; body?: string }>()
</script>

<template>
  <div class="empty">
    <span class="ic"><AppIcon :name="icon ?? 'inbox'" :size="22" /></span>
    <p class="t">{{ title }}</p>
    <p v-if="body" class="muted">{{ body }}</p>
    <div v-if="$slots.default" class="act"><slot /></div>
  </div>
</template>

<style scoped>
.empty { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 6px; padding: 40px 16px; }
.ic { width: 44px; height: 44px; border-radius: 50%; background: var(--brand-soft); color: var(--brand-primary); display: grid; place-items: center; margin-bottom: 6px; }
.t { font-weight: 600; }
.muted { max-width: 420px; }
.act { margin-top: 10px; display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; }
</style>
