<script setup lang="ts">
export interface Tab { key: string; label: string }
defineProps<{ tabs: Tab[] }>()
const model = defineModel<string>({ required: true })
</script>

<template>
  <div class="tabs" role="tablist">
    <button v-for="t in tabs" :key="t.key" type="button" role="tab" class="tab" :class="{ on: model === t.key }"
            :aria-selected="model === t.key" @click="model = t.key">{{ t.label }}</button>
  </div>
</template>
