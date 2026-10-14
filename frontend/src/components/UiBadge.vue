<script setup lang="ts">
import type { Tone } from '@/format'
withDefaults(defineProps<{ tone?: Tone }>(), { tone: 'neutral' })
</script>

<template>
  <span class="badge" :class="tone"><span class="dot" aria-hidden="true" /><slot /></span>
</template>

<style scoped>
.badge { display: inline-flex; align-items: center; gap: 6px; padding: 2px 8px; border-radius: var(--radius-full); font-size: 12px; line-height: 16px; font-weight: 500; white-space: nowrap; }
.dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.success { background: var(--success-bg); color: var(--success); }
.warning { background: var(--warning-bg); color: var(--warning); }
.danger { background: var(--danger-bg); color: var(--danger); }
.info { background: var(--info-bg); color: var(--info); }
.neutral { background: var(--bg-subtle); color: var(--text-secondary); }
.brand { background: var(--brand-soft); color: var(--brand-primary); }
</style>
