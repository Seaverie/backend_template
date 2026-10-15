<script setup lang="ts">
// The centred card used by sign-in, password reset and invitation pages.
defineProps<{ title: string; caption?: string; error?: string }>()
</script>

<template>
  <main class="auth">
    <div class="card">
      <span class="mark" aria-hidden="true"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect width="7" height="9" x="3" y="3" rx="1"/><rect width="7" height="5" x="14" y="3" rx="1"/><rect width="7" height="9" x="14" y="12" rx="1"/><rect width="7" height="5" x="3" y="16" rx="1"/></svg></span>
      <div><h1>{{ title }}</h1><p v-if="caption" class="muted cap">{{ caption }}</p></div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <slot />
    </div>
    <div v-if="$slots.below" class="below"><slot name="below" /></div>
  </main>
</template>

<style scoped>
.auth { min-height: 100vh; min-height: 100dvh; display: grid; place-items: center; align-content: center; gap: 20px; padding: 32px 16px; background: var(--bg-canvas); }
.card { width: min(420px, 100%); background: var(--bg-surface); border: 1px solid var(--border-default); border-radius: var(--radius-lg); padding: 36px; display: flex; flex-direction: column; gap: 18px; box-shadow: 0 1px 2px rgba(16, 24, 40, .04); }
.mark { width: 40px; height: 40px; border-radius: var(--radius-md); background: var(--bg-sidebar); color: #fff; display: grid; place-items: center; }
.cap { margin-top: 4px; }
.error { color: var(--danger); background: var(--danger-bg); padding: 10px 12px; border-radius: var(--radius-md); font-size: 13px; }
.below { font-size: 13px; color: var(--text-secondary); text-align: center; }
:slotted(form) { display: flex; flex-direction: column; gap: 14px; }
@media (max-width: 479px) { .card { padding: 24px 20px; border: 0; background: transparent; box-shadow: none; } .auth { align-content: start; padding-top: 48px; } }
</style>
