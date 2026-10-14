<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { Tenant, TenantDatabase } from '@/api/types'
import DataTable, { type Column } from '@/components/DataTable.vue'
import StatCard from '@/components/StatCard.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import { number, tenantTone, title } from '@/format'

const tenants = ref<Tenant[]>([])
const selected = ref<Tenant | null>(null)
const db = ref<TenantDatabase | null>(null)
const confirm = ref('')
const drop = ref(false)
const msg = ref<{ tone: 'ok' | 'err'; text: string } | null>(null)
const busy = ref('')

async function pick(t: Tenant) {
  selected.value = t; confirm.value = ''; drop.value = false; msg.value = null
  db.value = t.isolation_mode === 'dedicated' ? await api.tenantDb(t.id) : null
}
async function sync() {
  if (!selected.value) return
  busy.value = 'sync'
  try { db.value = { ...(await api.syncTenantDb(selected.value.id)), platform_schema_version: db.value?.platform_schema_version } } finally { busy.value = '' }
}
async function offboard() {
  if (!selected.value) return
  busy.value = 'off'; msg.value = null
  try {
    const task = await api.offboard(selected.value.id, confirm.value, drop.value)
    msg.value = { tone: 'ok', text: `Offboarding started (task ${task.id}). Track it at GET /platform/tasks/{id}.` }
    tenants.value = (await api.tenants()).items
  } catch (e) { msg.value = { tone: 'err', text: e instanceof Error ? e.message : 'Request failed.' } } finally { busy.value = '' }
}
onMounted(async () => { tenants.value = (await api.tenants()).items; const g = tenants.value.find((t) => t.isolation_mode === 'dedicated'); if (g) await pick(g) })

const counts = computed(() => ({
  total: tenants.value.length, active: tenants.value.filter((t) => t.status === 'active').length,
  dedicated: tenants.value.filter((t) => t.isolation_mode === 'dedicated').length,
}))
const inSync = computed(() => !db.value || db.value.synced_schema_version === db.value.platform_schema_version)
const columns: Column[] = [
  { key: 'name', label: 'Tenant', primary: true }, { key: 'iso', label: 'Isolation' }, { key: 'status', label: 'Status' },
  { key: 'rl', label: 'Rate limit', align: 'right', hideOnMobile: true }, { key: 'users', label: 'Users', align: 'right', hideOnMobile: true },
]
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Tenants</h1><p class="muted">GET /platform/tenants · platform admin, MFA verified</p></div>
      <div class="actions"><UiButton>Export schema snapshot</UiButton><UiButton variant="primary" icon="plus">New tenant</UiButton></div>
    </div>
    <div class="stats">
      <StatCard label="Tenants" :value="String(counts.total)" :note="`${counts.active} active`" />
      <StatCard label="Dedicated databases" :value="String(counts.dedicated)" note="PostgreSQL" />
      <StatCard label="Dead letters" value="2" note="GET /platform/dead-letters" />
      <StatCard label="Background tasks" value="1 running" note="Imports, offboarding, deliveries" />
    </div>

    <UiCard title="All tenants" caption="Shared tenants are isolated by row-level security; dedicated tenants have their own database">
      <DataTable :columns="columns" :rows="tenants" row-key="id" :selected="selected?.id" clickable @select="pick">
        <template #cell-name="{ row }"><strong>{{ row.name }}</strong><div class="caption">{{ row.slug }}</div></template>
        <template #cell-iso="{ row }"><UiBadge :tone="row.isolation_mode === 'dedicated' ? 'brand' : 'neutral'">{{ title(row.isolation_mode) }}</UiBadge></template>
        <template #cell-status="{ row }"><UiBadge :tone="tenantTone[row.status]">{{ title(row.status) }}</UiBadge></template>
        <template #cell-rl="{ row }">{{ number(row.rate_limit_per_min) }} / min</template>
        <template #cell-users="{ row }">{{ row.user_count }}</template>
      </DataTable>
    </UiCard>

    <div v-if="selected" class="split">
      <UiCard :title="`Dedicated database · ${selected.slug}`" caption="GET /platform/tenants/{id}/database · credentials stored encrypted, never shown">
        <template #actions><UiBadge v-if="db" :tone="inSync ? 'success' : 'warning'">{{ inSync ? 'In sync' : 'Sync due' }}</UiBadge></template>
        <div v-if="db" class="body">
          <div class="kv"><span>Engine</span><span>{{ title(db.dialect) }}</span></div>
          <div class="kv"><span>Host</span><span>{{ db.host }}:{{ db.port }}</span></div>
          <div class="kv"><span>TLS</span><span>{{ db.ssl_mode }}</span></div>
          <div class="kv"><span>Schema</span><span>Ledger version {{ db.synced_schema_version }} of {{ db.platform_schema_version }}</span></div>
          <div class="kv"><span>Credentials</span><span>Version {{ db.secret_version }}</span></div>
          <div class="kv"><span>Row-level security</span><UiBadge tone="success">Forced on every table</UiBadge></div>
        </div>
        <p v-else class="body caption">This tenant is on the shared database (row-level security). Configure a dedicated database with PUT /platform/tenants/{id}/database.</p>
        <div v-if="db" class="foot">
          <UiButton icon="refresh" :loading="busy === 'sync'" @click="sync">Sync schema</UiButton>
          <UiButton icon="key">Rotate credentials</UiButton>
          <UiButton variant="ghost">Disable routing</UiButton>
        </div>
      </UiCard>

      <UiCard title="Offboard tenant" tone="danger">
        <form class="danger" @submit.prevent="offboard">
          <p class="muted">Archives the audit log (encrypted), deletes every row the tenant owns, clears its cached data and records the erasure. This cannot be undone.</p>
          <UiInput v-model="confirm" label="Type the tenant slug to confirm" :placeholder="selected.slug" autocomplete="off" />
          <label v-if="selected.isolation_mode === 'dedicated'" class="check"><input v-model="drop" type="checkbox" /> Also drop TenantCore objects from the dedicated database</label>
          <UiButton type="submit" variant="danger" block :loading="busy === 'off'" :disabled="confirm !== selected.slug || selected.status === 'offboarding'">
            {{ selected.status === 'offboarding' ? 'Offboarding in progress' : `Offboard ${selected.slug}` }}
          </UiButton>
          <p v-if="msg" :class="msg.tone" role="status">{{ msg.text }}</p>
          <p class="caption">POST /platform/tenants/{id}/offboard · runs as a background task</p>
        </form>
      </UiCard>
    </div>
  </div>
</template>

<style scoped>
.body { padding: 0 24px 16px; }
.foot { display: flex; gap: 8px; flex-wrap: wrap; padding: 16px 24px; border-top: 1px solid var(--border-default); }
.danger { padding: 0 24px 20px; display: grid; gap: 14px; }
.check { display: flex; gap: 10px; align-items: flex-start; font-size: 13px; }
.check input { margin-top: 2px; width: 16px; height: 16px; flex: none; }
.ok { color: var(--success); font-size: 13px; }
.err { color: var(--danger); font-size: 13px; }
@media (max-width: 767px) { .body { padding: 0 16px 14px; } .foot { padding: 14px 16px; } .danger { padding: 0 16px 16px; } }
</style>
