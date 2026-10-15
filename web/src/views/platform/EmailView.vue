<script setup lang="ts">
import EmailPanel from '@/components/EmailPanel.vue'
import PageHeader from '@/components/PageHeader.vue'
</script>

<template>
  <div class="page">
    <PageHeader title="Email" caption="The mail server and default wording for every workspace. A workspace can use its own server and wording instead." />
    <EmailPanel base="/platform/email" />
  </div>
</template>
