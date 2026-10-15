<script setup lang="ts">
import { ref } from 'vue'
import { api } from '@/api/client'
import { can } from '@/api/me'
import { useLoader } from '@/composables'
import AuditList from '@/components/AuditList.vue'
import PageHeader from '@/components/PageHeader.vue'

const names = ref<Record<string, string>>({})
useLoader(async () => { if (can('users')) names.value = Object.fromEntries((await api.get('/users')).items.map((u: any) => [u.id, u.full_name || u.email])) })
</script>

<template>
  <div class="page">
    <PageHeader title="Activity log" caption="Every change in this workspace: who did it and when. Entries cannot be edited or deleted." />
    <AuditList path="/audit-logs" :names="names" />
  </div>
</template>
