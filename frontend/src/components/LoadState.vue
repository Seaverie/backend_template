<script setup lang="ts">
// Wraps page content: a spinner while the first load runs, an error with Retry, otherwise the content.
import AppIcon from './AppIcon.vue'
import UiButton from './UiButton.vue'
defineProps<{ loading: boolean; error: string; ready?: boolean }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <div v-if="error" class="state err" role="alert">
    <AppIcon name="alert" :size="28" />
    <div><p class="t">Could not load this</p><p class="muted">{{ error }}</p></div>
    <UiButton icon="refresh" @click="$emit('retry')">Try again</UiButton>
  </div>
  <div v-else-if="loading && !ready" class="state" aria-busy="true"><span class="spin" aria-hidden="true" /><span class="muted">Loading…</span></div>
  <slot v-else />
</template>

<style scoped>
.state { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 48px 16px; text-align: center; background: var(--bg-surface); border: 1px solid var(--border-default); border-radius: var(--radius-lg); }
.err { color: var(--danger); }
.err .muted { overflow-wrap: anywhere; max-width: 520px; }
.t { font-weight: 600; color: var(--text-primary); margin-bottom: 4px; }
.spin { width: 24px; height: 24px; border-radius: 50%; border: 3px solid var(--border-default); border-top-color: var(--brand-primary); animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
