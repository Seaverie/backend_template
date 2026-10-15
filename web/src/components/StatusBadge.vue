<script setup lang="ts">
import { title, tone } from '@/format'
import UiBadge from './UiBadge.vue'
defineProps<{ status: string | null | undefined; label?: string | null }>()
</script>

<template>
  <UiBadge :tone="tone(status)">{{ label || title(status ?? '—') }}</UiBadge>
</template>
