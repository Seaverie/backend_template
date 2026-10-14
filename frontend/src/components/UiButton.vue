<script setup lang="ts">
import AppIcon from './AppIcon.vue'
withDefaults(defineProps<{ variant?: 'primary' | 'secondary' | 'danger' | 'ghost'; icon?: string; block?: boolean; loading?: boolean; disabled?: boolean; type?: 'button' | 'submit' }>(),
  { variant: 'secondary', type: 'button' })
</script>

<template>
  <button :type="type" class="btn" :class="[variant, { block }]" :disabled="loading || disabled" :aria-busy="loading || undefined">
    <AppIcon v-if="icon" :name="icon" :size="16" />
    <span><slot /></span>
  </button>
</template>

<style scoped>
.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: 8px; min-height: 36px;
  padding: 8px 16px; border-radius: var(--radius-md); border: 1px solid transparent; font-weight: 500;
  cursor: pointer; white-space: nowrap; transition: background 0.15s, border-color 0.15s;
}
.btn:disabled { opacity: 0.6; cursor: progress; }
.block { width: 100%; }
.primary { background: var(--brand-primary); color: var(--text-inverse); }
.primary:hover { background: var(--brand-primary-hover); }
.secondary { background: var(--bg-surface); color: var(--text-primary); border-color: var(--border-strong); }
.secondary:hover { background: var(--bg-subtle); }
.danger { background: var(--danger); color: var(--text-inverse); }
.danger:hover { filter: brightness(0.92); }
.ghost { background: transparent; color: var(--text-secondary); }
.ghost:hover { background: var(--bg-subtle); color: var(--text-primary); }
@media (pointer: coarse) { .btn { min-height: 44px; } }
</style>
