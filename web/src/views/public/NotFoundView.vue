<script setup lang="ts">
import { session } from '@/api/session'
import AuthCard from '@/components/AuthCard.vue'
import UiButton from '@/components/UiButton.vue'
</script>

<template>
  <AuthCard title="Page not found" caption="The address may be mistyped, or the page has moved.">
    <RouterLink :to="session.audience === 'platform' ? '/platform' : '/'"><UiButton variant="primary" block>Go to the dashboard</UiButton></RouterLink>
  </AuthCard>
</template>
