<script setup lang="ts">
import { computed } from 'vue'
import { money } from '@/format'
const props = defineProps<{ points: { label: string; value: number }[]; currency: string }>()
const max = computed(() => Math.max(1, ...props.points.map((p) => p.value)))
</script>

<template>
  <figure class="chart">
    <div class="bars" role="img" :aria-label="`Revenue per day, ${points.length} days, highest ${money(max, currency)}`">
      <div v-for="(p, i) in points" :key="p.label" class="bar" :class="{ last: i === points.length - 1 }"
           :style="{ height: `${Math.max(4, (p.value / max) * 100)}%` }" :title="`${p.label}: ${money(p.value, currency)}`" />
    </div>
    <figcaption class="axis caption"><span>{{ points[0]?.label }}</span><span>{{ points.at(-1)?.label }} (today)</span></figcaption>
  </figure>
</template>

<style scoped>
.chart { margin: 0; padding: 8px 24px 20px; }
.bars { height: 196px; display: flex; align-items: flex-end; gap: clamp(2px, 0.6vw, 6px); }
.bar { flex: 1 1 0; min-width: 0; background: var(--brand-soft); border-radius: var(--radius-sm) var(--radius-sm) 2px 2px; transition: background 0.15s; }
.bar:hover { background: #c7d2fe; }
.bar.last { background: var(--brand-primary); }
.axis { display: flex; justify-content: space-between; margin-top: 8px; }
@media (max-width: 767px) { .chart { padding: 4px 16px 16px; } .bars { height: 140px; } }
</style>
