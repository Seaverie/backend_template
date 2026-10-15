<script setup lang="ts">
import { ref } from 'vue'
import { api } from '@/api/client'
import { useLoader } from '@/composables'
import { bytes, dateTime } from '@/format'
import DataTable from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatCard from '@/components/StatCard.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'

const s = ref<any>(null)
const { loading, error, reload } = useLoader(async () => { s.value = await api.get('/platform/backups') })
const age = (h: number | null) => (h === null || h === undefined ? '—' : h < 1 ? 'under an hour' : h < 48 ? `${Math.round(h)} hours` : `${Math.round(h / 24)} days`)
</script>

<template>
  <div class="page">
    <PageHeader title="Backups" caption="Made by the backup service next to the API: a database dump and a copy of stored files.">
      <UiButton icon="refresh" @click="reload">Refresh</UiButton>
    </PageHeader>
    <LoadState :loading="loading" :error="error" :ready="!!s" @retry="reload">
      <template v-if="s">
        <p v-if="!s.configured" class="note warn">No backup folder at {{ s.dir }}. Run the “backup” service from docker-compose.yml and mount its volume into the API.</p>
        <template v-else>
          <div class="stats">
            <StatCard label="Health" :value="s.healthy ? 'OK' : 'Late'" :note="s.healthy ? 'A recent database backup exists.' : 'No database backup in the last two intervals.'" />
            <StatCard label="Last database backup" :value="age(s.age_hours) + (s.age_hours !== null ? ' ago' : '')" :note="dateTime(s.latest_database_backup_at)" />
            <StatCard label="Every" :value="`${s.interval_hours} hours`" />
            <StatCard label="Files kept" :value="String(s.items.length)" />
          </div>
          <UiCard title="Backup files" :caption="s.dir">
            <template #actions><UiBadge :tone="s.healthy ? 'success' : 'danger'">{{ s.healthy ? 'Healthy' : 'Late' }}</UiBadge></template>
            <DataTable :columns="[{ key: 'name', label: 'File', primary: true }, { key: 'size', label: 'Size', align: 'right' }]" :rows="s.items" row-key="name" empty="No backups yet.">
              <template #cell-name="{ row }"><strong class="mono">{{ row.name }}</strong><span class="caption">{{ dateTime(row.created_at) }}</span></template>
              <template #cell-size="{ row }">{{ bytes(row.size_bytes) }}</template>
            </DataTable>
          </UiCard>
        </template>
        <UiCard title="Restoring">
          <div class="card-body">
            <p class="muted">Restores replace all current data, so they are run from the server, not from here:</p>
            <pre class="mono cmd">docker compose stop api
docker compose run --rm backup bash /opt/backup/restore.sh 20261015-020000
docker compose start api</pre>
            <p class="caption">Copy the backups volume to another machine or cloud storage regularly — a backup on the same disk does not survive that disk.</p>
          </div>
        </UiCard>
      </template>
    </LoadState>
  </div>
</template>

<style scoped>.cmd { background: var(--bg-sidebar); color: #e2e8f0; padding: 12px 14px; border-radius: var(--radius-md); margin: 0; overflow-x: auto; }</style>
