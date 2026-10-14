<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import type { ApiKey, DataTable as Table } from '@/api/types'
import DataTable, { type Column } from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiSelect from '@/components/UiSelect.vue'
import { useBusy, useLoader } from '@/composables'
import { dateTime, title, type Tone } from '@/format'
import { ask, copy } from '@/ui'

const keys = ref<ApiKey[]>([])
const tables = ref<Table[]>([])
const showRevoked = ref(false)
const { busy, run } = useBusy()
const { loading, error, reload } = useLoader(async () => {
  const [k, t] = await Promise.all([api.apiKeys(), api.tables().catch(() => ({ items: [] as Table[] }))])
  keys.value = k.items; tables.value = t.items
})

function state(k: ApiKey): { label: string; tone: Tone } {
  if (k.revoked_at) return { label: 'Revoked', tone: 'neutral' }
  if (k.expires_at && new Date(k.expires_at) <= new Date()) return { label: 'Expired', tone: 'neutral' }
  if (k.expires_at && keys.value.some((x) => x.rotated_from_id === k.id)) return { label: 'Rotating out', tone: 'warning' }
  return { label: 'Active', tone: 'success' }
}
const shown = computed(() => keys.value.filter((k) => showRevoked.value || state(k).label !== 'Revoked' && state(k).label !== 'Expired'))
const columns: Column[] = [
  { key: 'name', label: 'Key', primary: true }, { key: 'scopes', label: 'Scopes' }, { key: 'status', label: 'Status' },
  { key: 'created', label: 'Created', hideOnMobile: true }, { key: 'expires', label: 'Expires', hideOnMobile: true }, { key: 'act', label: '', align: 'right' },
]

// Scope format: <resource>:<action> (see app/services/api_keys.py).
const RESOURCES = ['orders', 'products', 'inventory', 'purchasing', 'rma', 'payments', 'maps', 'imports', 'deliveries', 'users', 'roles', 'api_keys', 'audit_logs', 'settings', 'analytics', 'webhooks', 'notifications']
const resourceOptions = computed(() => [...RESOURCES.map((r) => ({ value: r, label: title(r) })), ...tables.value.map((t) => ({ value: `data.${t.name}`, label: `Data · ${title(t.name)}` })), { value: 'data.*', label: 'All data tables (admin only)' }])
const actionOptions = ['read', 'create', 'update', 'delete', 'manage', '*'].map((a) => ({ value: a, label: a === '*' ? 'Everything (admin only)' : title(a) }))

const create = reactive({ open: false, name: '', resource: 'orders', action: 'read', scopes: [] as string[], expires: '90', secret: '' })
function openCreate() { Object.assign(create, { open: true, name: '', resource: 'orders', action: 'read', scopes: [], expires: '90', secret: '' }) }
function addScope() { const s = `${create.resource}:${create.action}`; if (!create.scopes.includes(s)) create.scopes.push(s) }
async function submitCreate() {
  let k: ApiKey | undefined
  if (await run('create', async () => { k = await api.createApiKey(create.name.trim(), create.scopes, create.expires ? Number(create.expires) : undefined) }) && k) {
    create.secret = k.api_key ?? ''; await reload()
  }
}

const rot = reactive({ open: false, key: null as ApiKey | null, grace: '3600', secret: '' })
async function submitRotate() {
  if (!rot.key) return
  const id = rot.key.id
  let k: ApiKey | undefined
  if (await run('rotate', async () => { k = await api.rotateApiKey(id, Number(rot.grace)) }) && k) { rot.secret = k.api_key ?? ''; await reload() }
}
async function revoke(k: ApiKey) {
  if (!(await ask({ title: `Revoke “${k.name}”?`, body: 'Requests using this key start failing immediately. This cannot be undone.', confirmLabel: 'Revoke key', danger: true }))) return
  if (await run(`rev-${k.id}`, () => api.revokeApiKey(k.id), 'Key revoked.')) await reload()
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>API keys</h1><p class="muted">Keys let other systems call the API as this workspace. A key never has more access than the person who created it.</p></div>
      <div class="actions"><UiButton variant="primary" icon="plus" @click="openCreate">New API key</UiButton></div>
    </div>
    <LoadState :loading="loading" :error="error" :ready="!!keys.length" @retry="reload">
      <UiCard title="Keys" :caption="`${keys.filter((k) => state(k).label === 'Active').length} active · send as the X-API-Key header`">
        <template #actions><UiCheck v-model="showRevoked" label="Show revoked and expired" /></template>
        <EmptyState v-if="!shown.length" icon="key" title="No API keys" body="Create a key for each integration so you can revoke them independently.">
          <UiButton variant="primary" icon="plus" @click="openCreate">New API key</UiButton>
        </EmptyState>
        <DataTable v-else :columns="columns" :rows="shown" row-key="id">
          <template #cell-name="{ row }"><strong>{{ row.name }}</strong><div class="caption mono">{{ row.key_id }}</div></template>
          <template #cell-scopes="{ row }"><span class="chips"><span v-for="s in row.scopes" :key="s" class="chip mono">{{ s }}</span></span></template>
          <template #cell-status="{ row }"><UiBadge :tone="state(row).tone">{{ state(row).label }}</UiBadge></template>
          <template #cell-created="{ row }">{{ dateTime(row.created_at) }}</template>
          <template #cell-expires="{ row }">{{ row.expires_at ? dateTime(row.expires_at) : 'Never' }}</template>
          <template #cell-act="{ row }">
            <div v-if="state(row).label === 'Active'" class="row-actions">
              <UiButton variant="ghost" icon="refresh" @click="Object.assign(rot, { open: true, key: row, grace: '3600', secret: '' })">Rotate</UiButton>
              <UiButton variant="ghost" icon="trash" :loading="busy === `rev-${row.id}`" @click="revoke(row)">Revoke</UiButton>
            </div>
          </template>
        </DataTable>
      </UiCard>
    </LoadState>

    <UiModal :open="create.open" :title="create.secret ? 'Copy your new key' : 'New API key'" :submit-label="create.secret ? undefined : 'Create key'"
             :busy="busy === 'create'" :submit-disabled="!create.name.trim() || !create.scopes.length" @close="create.open = false" @submit="submitCreate">
      <template v-if="!create.secret">
        <UiInput v-model="create.name" label="Name" required :maxlength="100" placeholder="e.g. ERP sync" />
        <div class="scope-row">
          <UiSelect v-model="create.resource" label="Resource" :options="resourceOptions" />
          <UiSelect v-model="create.action" label="Action" :options="actionOptions" />
          <UiButton icon="plus" @click="addScope">Add</UiButton>
        </div>
        <div v-if="create.scopes.length" class="chips">
          <button v-for="(s, i) in create.scopes" :key="s" type="button" class="chip mono removable" :aria-label="`Remove ${s}`" @click="create.scopes.splice(i, 1)">{{ s }} ✕</button>
        </div>
        <p v-else class="caption">Add at least one scope, e.g. orders:read.</p>
        <UiSelect v-model="create.expires" label="Expires" :options="[{ value: '30', label: 'In 30 days' }, { value: '90', label: 'In 90 days' }, { value: '365', label: 'In 1 year' }, { value: '', label: 'Never' }]" />
      </template>
      <template v-else>
        <p class="note warn">This is the only time the key is shown. Store it in your secrets manager now.</p>
        <div class="secret-out"><code>{{ create.secret }}</code><UiButton icon="copy" @click="copy(create.secret)">Copy</UiButton></div>
      </template>
    </UiModal>

    <UiModal :open="rot.open" :title="rot.secret ? 'Copy the replacement key' : `Rotate “${rot.key?.name}”`" :submit-label="rot.secret ? undefined : 'Rotate key'"
             :busy="busy === 'rotate'" @close="rot.open = false" @submit="submitRotate">
      <template v-if="!rot.secret">
        <p class="muted">Issues a new key with the same scopes. The old key keeps working for the grace period so you can deploy the new one.</p>
        <UiSelect v-model="rot.grace" label="Old key stops working" :options="[{ value: '0', label: 'Immediately' }, { value: '900', label: 'In 15 minutes' }, { value: '3600', label: 'In 1 hour' }, { value: '86400', label: 'In 24 hours' }]" />
      </template>
      <template v-else>
        <p class="note warn">This is the only time the new key is shown.</p>
        <div class="secret-out"><code>{{ rot.secret }}</code><UiButton icon="copy" @click="copy(rot.secret)">Copy</UiButton></div>
      </template>
    </UiModal>
  </div>
</template>

<style scoped>
.scope-row { display: grid; grid-template-columns: 1.4fr 1fr auto; gap: 10px; align-items: end; }
.removable { border: 0; cursor: pointer; }
.removable:hover { background: var(--danger-bg); color: var(--danger); }
@media (max-width: 639px) { .scope-row { grid-template-columns: 1fr 1fr; } .scope-row > :last-child { grid-column: 1 / -1; } }
</style>
