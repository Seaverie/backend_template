<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppOverlays from './components/AppOverlays.vue'
import AppShell from './components/AppShell.vue'
const route = useRoute()
</script>

<template>
  <!-- Nothing renders until the first navigation resolves, so the shell never mounts on the sign-in page. -->
  <template v-if="route.matched.length">
    <RouterView v-if="route.meta.public" />
    <AppShell v-else><RouterView :key="route.path" /></AppShell>
  </template>
  <AppOverlays />
</template>
