<script setup lang="ts">
// Dialog with an optional form: Enter submits, Esc or the backdrop cancels. A bottom sheet on phones.
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import UiButton from './UiButton.vue'

const props = withDefaults(defineProps<{
  open: boolean; title: string; caption?: string; submitLabel?: string; busy?: boolean; danger?: boolean
  submitDisabled?: boolean; wide?: boolean; cancelLabel?: string; onTop?: boolean
}>(), { cancelLabel: 'Cancel' })
const emit = defineEmits<{ close: []; submit: [] }>()
const box = ref<HTMLElement | null>(null)

const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !props.busy) emit('close') }
watch(() => props.open, async (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
  if (open) {
    window.addEventListener('keydown', onKey)
    await nextTick()
    box.value?.querySelector<HTMLElement>('input:not([type=checkbox]):not([disabled]), select, textarea')?.focus()
  } else window.removeEventListener('keydown', onKey)
}, { immediate: true })
onBeforeUnmount(() => { document.body.style.overflow = ''; window.removeEventListener('keydown', onKey) })
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="overlay" :class="{ top: onTop }" @mousedown.self="!busy && emit('close')">
        <div ref="box" class="modal" :class="{ wide }" role="dialog" aria-modal="true" :aria-label="title">
          <form class="form" novalidate @submit.prevent="!busy && !submitDisabled && emit('submit')">
            <header class="head">
              <div class="titles"><h2>{{ title }}</h2><p v-if="caption" class="caption">{{ caption }}</p></div>
              <button class="x" type="button" aria-label="Close" :disabled="busy" @click="emit('close')"><AppIcon name="close" /></button>
            </header>
            <div class="body"><slot /></div>
            <footer class="foot">
              <slot name="footer-start" />
              <div class="grow" />
              <UiButton :disabled="busy" @click="emit('close')">{{ submitLabel ? cancelLabel : 'Close' }}</UiButton>
              <UiButton v-if="submitLabel" type="submit" :variant="danger ? 'danger' : 'primary'" :loading="busy" :disabled="submitDisabled">{{ submitLabel }}</UiButton>
            </footer>
          </form>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.overlay { position: fixed; inset: 0; z-index: 80; background: rgba(15, 23, 42, 0.5); display: grid; place-items: center; padding: 24px; overflow-y: auto; }
.overlay.top { z-index: 90; }
.modal { width: min(520px, 100%); background: var(--bg-surface); border-radius: var(--radius-lg); box-shadow: var(--shadow-overlay); display: flex; flex-direction: column; max-height: calc(100dvh - 48px); }
.modal.wide { width: min(820px, 100%); }
.form { display: flex; flex-direction: column; min-height: 0; flex: 1; }
.head { display: flex; align-items: flex-start; gap: 12px; padding: 20px 24px 12px; }
.titles { flex: 1; min-width: 0; }
.titles p { margin-top: 4px; }
.x { width: 36px; height: 36px; display: grid; place-items: center; border: 0; border-radius: var(--radius-md); background: transparent; cursor: pointer; color: var(--text-secondary); flex: none; margin: -6px -8px 0 0; }
.x:hover { background: var(--bg-subtle); }
.body { padding: 4px 24px 20px; display: grid; gap: 14px; overflow-y: auto; min-height: 0; }
.foot { display: flex; gap: 8px; align-items: center; padding: 14px 24px; border-top: 1px solid var(--border-default); flex-wrap: wrap; }
.grow { flex: 1; }
.modal-enter-active, .modal-leave-active { transition: opacity 0.15s; }
.modal-enter-from, .modal-leave-to { opacity: 0; }
@media (max-width: 639px) {
  .overlay { padding: 0; place-items: end stretch; }
  .modal, .modal.wide { width: 100%; max-height: 92dvh; border-radius: var(--radius-lg) var(--radius-lg) 0 0; padding-bottom: env(safe-area-inset-bottom); }
  .head { padding: 16px 16px 8px; }
  .body { padding: 4px 16px 16px; }
  .foot { padding: 12px 16px; }
  .foot > :deep(.btn) { flex: 1; }
  .grow { display: none; }
}
</style>
