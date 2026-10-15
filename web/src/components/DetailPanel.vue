<script setup lang="ts">
// Detail column beside a list on wide screens; a slide-over sheet on tablets and phones.
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
const props = defineProps<{ open: boolean; label: string }>()
const emit = defineEmits<{ close: [] }>()
const narrow = ref(false)
let mq: MediaQueryList | undefined
const sync = () => { narrow.value = !!mq?.matches }
onMounted(() => { mq = window.matchMedia('(max-width: 1199px)'); sync(); mq.addEventListener('change', sync) })
onBeforeUnmount(() => mq?.removeEventListener('change', sync))
const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') emit('close') }
watch(() => props.open && narrow.value, (sheet) => {
  document.body.style.overflow = sheet ? 'hidden' : ''
  if (sheet) window.addEventListener('keydown', onKey); else window.removeEventListener('keydown', onKey)
})
onBeforeUnmount(() => { document.body.style.overflow = ''; window.removeEventListener('keydown', onKey) })
</script>

<template>
  <aside v-if="!narrow" class="panel" :aria-label="label"><slot /></aside>
  <Teleport v-else to="body">
    <Transition name="sheet">
      <div v-if="open" class="overlay" @click.self="$emit('close')">
        <aside class="panel sheet" role="dialog" aria-modal="true" :aria-label="label">
          <button class="close" type="button" aria-label="Close" @click="$emit('close')"><AppIcon name="close" /></button>
          <slot />
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.panel { background: var(--bg-surface); border: 1px solid var(--border-default); border-radius: var(--radius-lg); overflow: hidden; min-width: 0; }
.overlay { position: fixed; inset: 0; z-index: 50; background: rgba(15, 23, 42, 0.45); display: flex; justify-content: flex-end; }
.sheet { position: relative; width: min(440px, 100%); height: 100%; overflow-y: auto; border-radius: 0; border: 0; box-shadow: var(--shadow-overlay); padding-bottom: env(safe-area-inset-bottom); }
.close { position: absolute; top: 14px; right: 14px; z-index: 1; width: 40px; height: 40px; display: grid; place-items: center; border: 0; border-radius: var(--radius-md); background: var(--bg-subtle); cursor: pointer; }
.sheet-enter-active, .sheet-leave-active { transition: opacity 0.2s; }
.sheet-enter-active .sheet, .sheet-leave-active .sheet { transition: transform 0.2s ease; }
.sheet-enter-from, .sheet-leave-to { opacity: 0; }
.sheet-enter-from .sheet, .sheet-leave-to .sheet { transform: translateX(100%); }
</style>
