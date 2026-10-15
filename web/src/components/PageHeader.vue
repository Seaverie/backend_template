<script setup lang="ts">
import AppIcon from './AppIcon.vue'
defineProps<{ title: string; caption?: string; back?: string }>()
</script>

<template>
  <header class="page-header">
    <RouterLink v-if="back" :to="back" class="back" aria-label="Back"><AppIcon name="arrowLeft" /></RouterLink>
    <div class="titles"><h1>{{ title }}</h1><p v-if="caption" class="muted">{{ caption }}</p><slot name="sub" /></div>
    <div v-if="$slots.default" class="actions"><slot /></div>
  </header>
</template>

<style scoped>
.back { width: 36px; height: 36px; display: grid; place-items: center; border-radius: var(--radius-md); color: var(--text-secondary); border: 1px solid var(--border-strong); background: var(--bg-surface); flex: none; }
.back:hover { text-decoration: none; background: var(--bg-subtle); }
</style>
