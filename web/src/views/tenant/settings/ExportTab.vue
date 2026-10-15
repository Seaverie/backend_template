<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { api } from '@/api/client'
import { useBusy, useLoader } from '@/composables'
import { bytes, dateTime, when } from '@/format'
import DataTable from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'

const items = ref<any[]>([])
const { loading, error, reload } = useLoader(async () => { items.value = (await api.get('/workspace/exports')).items; schedule() })
const { busy, run } = useBusy()
async function create() { await run('new', async () => { await api.post('/workspace/exports'); await reload() }, 'Export started. It usually takes under a minute.') }
async function get(e: any) { await run('dl:' + e.id, () => api.download(`/workspace/exports/${e.id}/download`, `export-${e.id.slice(0, 8)}.zip`)) }
let timer: ReturnType<typeof setTimeout> | undefined
function schedule() { clearTimeout(timer); if (items.value.some((e) => ['queued', 'running'].includes(e.status))) timer = setTimeout(reload, 2000) }
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <UiCard title="Export all data" caption="A zip with every record of this workspace as JSON lines, plus your uploaded files. Passwords and keys are left out. Links last 7 days.">
    <template #actions><UiButton icon="download" variant="primary" :loading="busy === 'new'" @click="create">New export</UiButton></template>
    <LoadState :loading="loading && !items.length" :error="error" :ready="true" @retry="reload">
      <DataTable :columns="[{ key: 'when', label: 'Requested', primary: true }, { key: 'status', label: 'Status' }, { key: 'size', label: 'Size', align: 'right' }, { key: 'acts', label: '' }]" :rows="items" row-key="id" empty="No exports yet.">
        <template #cell-when="{ row }"><strong>{{ when(row.created_at) }}</strong><span class="caption">expires {{ dateTime(row.expires_at) }}</span></template>
        <template #cell-status="{ row }"><StatusBadge :status="row.status" /><span v-if="row.error" class="caption">{{ row.error }}</span></template>
        <template #cell-size="{ row }">{{ bytes(row.size_bytes) }}</template>
        <template #cell-acts="{ row }"><div class="row-actions"><UiButton v-if="row.status === 'done'" variant="ghost" icon="download" :loading="busy === 'dl:' + row.id" @click="get(row)">Download</UiButton></div></template>
      </DataTable>
    </LoadState>
  </UiCard>
</template>
