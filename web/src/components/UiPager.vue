<script setup lang="ts">
// "1–50 of 230" with previous/next. The parent owns offset and reloads when it changes.
import { computed } from 'vue'
import UiButton from './UiButton.vue'
const props = defineProps<{ total: number | null | undefined; limit: number; count: number }>()
const offset = defineModel<number>({ required: true })
const end = computed(() => offset.value + props.count)
const hasNext = computed(() => (props.total ?? null) !== null ? end.value < (props.total as number) : props.count >= props.limit)
</script>

<template>
  <div v-if="offset > 0 || hasNext" class="pager">
    <span class="caption">{{ count ? offset + 1 : 0 }}–{{ end }}<template v-if="total !== null && total !== undefined"> of {{ total }}</template></span>
    <div class="grow" />
    <UiButton :disabled="offset === 0" @click="offset = Math.max(0, offset - limit)">Previous</UiButton>
    <UiButton :disabled="!hasNext" @click="offset = offset + limit">Next</UiButton>
  </div>
</template>

<style scoped>
.pager { display: flex; align-items: center; gap: 8px; padding: 12px 24px; border-top: 1px solid var(--border-default); }
.grow { flex: 1; }
@media (max-width: 767px) { .pager { padding: 12px 16px; } }
</style>
