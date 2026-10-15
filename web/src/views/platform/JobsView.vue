<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { api } from '@/api/client'
import { useBusy, useLoader } from '@/composables'
import { dateTime, when } from '@/format'
import DataTable from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiModal from '@/components/UiModal.vue'

const f = reactive({ status: '', kind: '' })
const data = ref<any>({ counts: {}, items: [] })
const { loading, error, reload } = useLoader(async () => { data.value = await api.get('/platform/jobs', { status: f.status, kind: f.kind, limit: 100 }) })
watch(f, () => reload())
const { busy, run } = useBusy()
async function retry(j: any) { await run('r:' + j.id, async () => { await api.post(`/platform/jobs/${j.id}/retry`); await reload() }, 'Queued again.') }
const sel = ref<any>(null)
const kinds = ['webhook.deliver', 'push.send', 'tenant.export', 'tenant.purge']
</script>

<template>
  <div class="page">
    <PageHeader title="Background jobs" caption="Work done outside requests: webhooks, push, exports, deletes. Each runs at least once and retries with back-off.">
      <UiButton icon="refresh" @click="reload">Refresh</UiButton>
    </PageHeader>
    <div class="stats">
      <StatCard label="Waiting" :value="String(data.counts.queued ?? 0)" />
      <StatCard label="Running" :value="String(data.counts.running ?? 0)" />
      <StatCard label="Done (kept 7 days)" :value="String(data.counts.done ?? 0)" />
      <StatCard label="Gave up" :value="String(data.counts.dead ?? 0)" :note="data.counts.dead ? 'Check the error, fix the cause, then retry.' : undefined" />
    </div>
    <UiCard>
      <div class="toolbar" style="padding-top: 16px">
        <select v-model="f.status" class="search-input" aria-label="Status"><option value="">Any status</option><option v-for="s in ['queued', 'running', 'done', 'dead']" :key="s" :value="s">{{ s }}</option></select>
        <select v-model="f.kind" class="search-input" aria-label="Kind"><option value="">Any kind</option><option v-for="k in kinds" :key="k" :value="k">{{ k }}</option></select>
      </div>
      <LoadState :loading="loading" :error="error" :ready="data.items.length > 0" @retry="reload">
        <DataTable :columns="[{ key: 'kind', label: 'Job', primary: true }, { key: 'status', label: 'Status' }, { key: 'attempts', label: 'Tries', align: 'right' }, { key: 'acts', label: '' }]"
                   :rows="data.items" row-key="id" clickable empty="No jobs." @select="(j) => (sel = j)">
          <template #cell-kind="{ row }"><strong class="mono">{{ row.kind }}</strong><span class="caption">{{ when(row.created_at) }}<template v-if="row.tenant_id"> · workspace {{ row.tenant_id.slice(0, 8) }}</template></span><span v-if="row.last_error" class="caption err">{{ row.last_error }}</span></template>
          <template #cell-status="{ row }"><StatusBadge :status="row.status" /></template>
          <template #cell-attempts="{ row }">{{ row.attempts }} / {{ row.max_attempts }}</template>
          <template #cell-acts="{ row }"><div class="row-actions"><UiButton v-if="row.status === 'dead'" variant="ghost" icon="refresh" :loading="busy === 'r:' + row.id" @click.stop="retry(row)">Retry</UiButton></div></template>
        </DataTable>
      </LoadState>
    </UiCard>
    <UiModal :open="!!sel" :title="sel?.kind ?? ''" @close="sel = null">
      <template v-if="sel">
        <div class="kv"><span>ID</span><span class="mono">{{ sel.id }}</span></div>
        <div class="kv"><span>Workspace</span><span class="mono">{{ sel.tenant_id ?? 'Platform' }}</span></div>
        <div class="kv"><span>Status</span><StatusBadge :status="sel.status" /></div>
        <div class="kv"><span>Tries</span><span>{{ sel.attempts }} of {{ sel.max_attempts }}</span></div>
        <div class="kv"><span>Next run</span><span>{{ dateTime(sel.run_at) }}</span></div>
        <div class="kv"><span>Finished</span><span>{{ dateTime(sel.finished_at) }}</span></div>
        <p v-if="sel.last_error" class="note danger mono">{{ sel.last_error }}</p>
      </template>
    </UiModal>
  </div>
</template>

<style scoped>.err { color: var(--danger); overflow-wrap: anywhere; }</style>
