<script setup lang="ts">
// Toasts and the shared confirm dialog (see src/ui.ts).
import { computed } from 'vue'
import { confirmState, dismiss, toasts } from '@/ui'
import AppIcon from './AppIcon.vue'
import UiInput from './UiInput.vue'
import UiModal from './UiModal.vue'

const blocked = computed(() => !!confirmState.typeToConfirm && confirmState.typed !== confirmState.typeToConfirm)
</script>

<template>
  <div class="toasts" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.tone" :role="t.tone === 'err' ? 'alert' : 'status'">
        <AppIcon :name="t.tone === 'ok' ? 'check' : 'alert'" :size="16" />
        <span>{{ t.text }}</span>
        <button type="button" aria-label="Dismiss" @click="dismiss(t.id)"><AppIcon name="close" :size="14" /></button>
      </div>
    </TransitionGroup>
  </div>
  <UiModal on-top :open="confirmState.open" :title="confirmState.title" :submit-label="confirmState.confirmLabel" :danger="confirmState.danger"
           :submit-disabled="blocked" @close="confirmState.resolve(false)" @submit="confirmState.resolve(true)">
    <p v-if="confirmState.body" class="muted body">{{ confirmState.body }}</p>
    <UiInput v-if="confirmState.typeToConfirm" v-model="confirmState.typed" :label="`Type ${confirmState.typeToConfirm} to confirm`" autocomplete="off" mono />
  </UiModal>
</template>

<style scoped>
.toasts { position: fixed; z-index: 100; right: 16px; bottom: calc(16px + env(safe-area-inset-bottom, 0px)); display: grid; gap: 8px; width: min(380px, calc(100vw - 32px)); }
.toast { display: flex; align-items: flex-start; gap: 10px; padding: 12px 12px 12px 14px; border-radius: var(--radius-md); background: var(--bg-sidebar); color: #fff; box-shadow: var(--shadow-overlay); font-size: 13px; }
.toast > span { flex: 1; overflow-wrap: anywhere; }
.toast.ok :first-child { color: #6ce9a6; }
.toast.err { background: var(--danger); }
.toast button { border: 0; background: transparent; color: inherit; opacity: 0.7; cursor: pointer; padding: 2px; display: grid; }
.toast-enter-active, .toast-leave-active { transition: all 0.2s; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
.body { white-space: pre-line; }
</style>
