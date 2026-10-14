<script setup lang="ts">
defineProps<{ title?: string; caption?: string; tone?: 'danger' }>()
</script>

<template>
  <section class="card" :class="tone">
    <header v-if="title || $slots.actions" class="card-header">
      <div class="titles">
        <h3 v-if="title" :class="{ 'danger-text': tone === 'danger' }">{{ title }}</h3>
        <p v-if="caption" class="caption">{{ caption }}</p>
      </div>
      <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
    </header>
    <slot />
  </section>
</template>

<style scoped>
.card { background: var(--bg-surface); border: 1px solid var(--border-default); border-radius: var(--radius-lg); overflow: hidden; min-width: 0; }
.card.danger { border-color: var(--danger); }
.card-header { display: flex; align-items: center; gap: 12px; padding: 16px 24px; flex-wrap: wrap; }
.titles { flex: 1 1 200px; min-width: 0; }
.titles p { margin-top: 2px; overflow-wrap: anywhere; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
.danger-text { color: var(--danger); }
@media (max-width: 767px) { .card-header { padding: 14px 16px; } }
</style>
