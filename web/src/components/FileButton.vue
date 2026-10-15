<script setup lang="ts">
// A button that opens the file picker and emits the chosen file(s).
import { ref } from 'vue'
import UiButton from './UiButton.vue'
defineProps<{ accept?: string; multiple?: boolean; icon?: string; loading?: boolean; variant?: 'primary' | 'secondary' | 'ghost' }>()
const emit = defineEmits<{ pick: [files: File[]] }>()
const input = ref<HTMLInputElement | null>(null)
function changed(e: Event) {
  const el = e.target as HTMLInputElement
  const files = Array.from(el.files ?? [])
  el.value = ''
  if (files.length) emit('pick', files)
}
</script>

<template>
  <UiButton :icon="icon ?? 'upload'" :loading="loading" :variant="variant" @click="input?.click()"><slot>Upload</slot></UiButton>
  <input ref="input" type="file" class="sr-only" :accept="accept" :multiple="multiple" tabindex="-1" aria-hidden="true" @change="changed" />
</template>
