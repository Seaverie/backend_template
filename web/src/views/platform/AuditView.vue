<script setup lang="ts">
import { ref } from 'vue'
import { api } from '@/api/client'
import { useLoader } from '@/composables'
import AuditList from '@/components/AuditList.vue'
import PageHeader from '@/components/PageHeader.vue'

const names = ref<Record<string, string>>({})
useLoader(async () => { names.value = Object.fromEntries((await api.get('/platform/admins')).items.map((a: any) => [a.id, a.full_name || a.email])) })
</script>

<template>
  <div class="page">
    <PageHeader title="Activity log" caption="Platform-level actions: sign-ins, workspaces created, modules changed, offboarding." />
    <AuditList path="/platform/audit-logs" :names="names" />
  </div>
</template>
