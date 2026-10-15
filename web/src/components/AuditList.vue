<script setup lang="ts">
// The activity log (workspace or platform): who did what, newest first.
import { ref } from 'vue'
import { api } from '@/api/client'
import { useList } from '@/composables'
import { dateTime, title } from '@/format'
import DataTable from './DataTable.vue'
import LoadState from './LoadState.vue'
import UiCard from './UiCard.vue'
import UiModal from './UiModal.vue'
import UiPager from './UiPager.vue'

const props = defineProps<{ path: string; names?: Record<string, string> }>()
const list = useList((q) => api.get(props.path, q), {})
const sel = ref<any>(null)
const who = (r: any) => (r.actor_id && props.names?.[r.actor_id]) || title(r.actor_type ?? 'system')
</script>

<template>
  <UiCard>
    <LoadState :loading="list.loading.value" :error="list.error.value" :ready="list.items.value.length > 0" @retry="list.reload">
      <DataTable :columns="[{ key: 'action', label: 'What', primary: true }, { key: 'who', label: 'Who' }, { key: 'when', label: 'When', hideOnMobile: true }]"
                 :rows="list.items.value" row-key="id" clickable empty="Nothing recorded yet." @select="(r) => (sel = r)">
        <template #cell-action="{ row }"><strong class="mono">{{ row.action }}</strong><span class="caption">{{ row.resource }}<template v-if="row.resource_id"> · {{ String(row.resource_id).slice(0, 12) }}</template></span></template>
        <template #cell-who="{ row }">{{ who(row) }}</template>
        <template #cell-when="{ row }">{{ dateTime(row.created_at) }}</template>
      </DataTable>
      <UiPager v-model="list.offset.value" :total="list.total.value" :limit="list.limit" :count="list.items.value.length" />
    </LoadState>
  </UiCard>
  <UiModal :open="!!sel" :title="sel?.action ?? ''" @close="sel = null">
    <template v-if="sel">
      <div class="kv"><span>When</span><span>{{ dateTime(sel.created_at) }}</span></div>
      <div class="kv"><span>Who</span><span>{{ who(sel) }}</span></div>
      <div class="kv"><span>Actor ID</span><span class="mono">{{ sel.actor_id ?? '—' }}</span></div>
      <div class="kv"><span>Record</span><span class="mono">{{ sel.resource }} {{ sel.resource_id ?? '' }}</span></div>
      <div class="kv"><span>Request</span><span class="mono">{{ sel.request_id ?? '—' }}</span></div>
      <pre class="detail mono">{{ JSON.stringify(sel.detail, null, 2) }}</pre>
    </template>
  </UiModal>
</template>

<style scoped>.detail { background: var(--bg-subtle); padding: 12px; border-radius: var(--radius-md); overflow: auto; max-height: 300px; margin: 0; white-space: pre-wrap; }</style>
