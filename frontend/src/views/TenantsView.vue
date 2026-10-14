<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { api } from '@/api/client'
import type { Task, Tenant, TenantDatabase } from '@/api/types'
import DataTable, { type Column } from '@/components/DataTable.vue'
import DetailPanel from '@/components/DetailPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import StatCard from '@/components/StatCard.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiSelect from '@/components/UiSelect.vue'
import { useBusy, useLoader } from '@/composables'
import { isEmail, number, taskTone, tenantTone, title } from '@/format'
import { ask, copy, download, toast } from '@/ui'

const tenants = ref<Tenant[]>([])
const selected = ref<Tenant | null>(null)
const db = ref<TenantDatabase | null>(null)
const dbLoading = ref(false)
const panel = ref(false)
const deadLetters = ref<number | null>(null)
const rate = ref('')
const { busy, run } = useBusy()

const { loading, error, reload } = useLoader(async () => {
  api.deadLetters().then((r) => { deadLetters.value = r.items.length }).catch(() => { deadLetters.value = null })
  tenants.value = (await api.tenants()).items.reverse() // newest first
  const keep = tenants.value.find((t) => t.id === selected.value?.id)
  if (keep) await pick(keep, false)
  else if (tenants.value[0] && window.matchMedia('(min-width: 1200px)').matches) await pick(tenants.value[0], false)
  else selected.value = null
})
async function pick(t: Tenant, openPanel = true) {
  const changed = selected.value?.id !== t.id
  selected.value = t; panel.value = openPanel || panel.value
  if (changed) { rate.value = t.rate_limit_per_min ? String(t.rate_limit_per_min) : ''; off.confirm = ''; off.drop = false }
  db.value = null
  if (t.isolation_mode === 'dedicated') {
    dbLoading.value = true
    try { db.value = await api.tenantDb(t.id) } catch { db.value = null } finally { dbLoading.value = false }
  }
}

const q = ref('')
const shown = computed(() => { const s = q.value.trim().toLowerCase(); return s ? tenants.value.filter((t) => `${t.name} ${t.slug} ${t.id}`.toLowerCase().includes(s)) : tenants.value })
const limit = ref(50)
const counts = computed(() => ({
  total: tenants.value.length, active: tenants.value.filter((t) => t.status === 'active').length,
  inactive: tenants.value.filter((t) => t.status !== 'active').length, dedicated: tenants.value.filter((t) => t.isolation_mode === 'dedicated').length,
}))
const columns: Column[] = [
  { key: 'name', label: 'Tenant', primary: true }, { key: 'iso', label: 'Isolation' }, { key: 'status', label: 'Status' },
  { key: 'rl', label: 'Rate limit', align: 'right', hideOnMobile: true },
]

// -- create tenant --
const strong = () => 'Tc-' + Array.from(crypto.getRandomValues(new Uint8Array(12)), (b) => 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'[b % 56]).join('') + '-7'
const create = reactive({ open: false, name: '', slug: '', slugTouched: false, email: '', password: '', rate: '', done: null as null | { slug: string; email: string; password: string } })
function openCreate() { Object.assign(create, { open: true, name: '', slug: '', slugTouched: false, email: '', password: strong(), rate: '', done: null }) }
const slugify = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 63)
const setName = (v: string) => { create.name = v; if (!create.slugTouched) create.slug = slugify(v) }
const slugOk = (s: string) => /^[a-z0-9][a-z0-9-]{1,62}$/.test(s)
const createValid = computed(() => create.name.trim() && slugOk(create.slug) && isEmail(create.email) && create.password.length >= 12 && (!create.rate || /^\d+$/.test(create.rate)))
async function submitCreate() {
  const body = { slug: create.slug, name: create.name.trim(), owner_email: create.email.trim(), owner_password: create.password, rate_limit_per_min: create.rate ? Number(create.rate) : null }
  if (await run('create', () => api.createTenant(body), `Tenant ${body.slug} created.`)) {
    create.done = { slug: body.slug, email: body.owner_email, password: body.owner_password }; await reload()
  }
}

// -- status & rate limit --
async function setStatus(t: Tenant, status: 'active' | 'suspended') {
  if (status === 'suspended' && !(await ask({ title: `Suspend ${t.slug}?`, body: 'Its users and API keys are refused until you reactivate it. No data is deleted.', confirmLabel: 'Suspend tenant', danger: true }))) return
  if (await run('status', () => api.updateTenant(t.id, { status }), status === 'active' ? 'Tenant reactivated.' : 'Tenant suspended.')) await reload()
}
async function saveRate() {
  if (!selected.value) return
  const id = selected.value.id, n = Number(rate.value)
  if (await run('rate', () => api.updateTenant(id, { rate_limit_per_min: n }), `Rate limit set to ${number(n)}/min.`)) await reload()
}

// -- dedicated database --
const dbForm = reactive({ open: false, dialect: 'postgresql', host: '', port: '5432', database: '', username: '', password: '', ssl_mode: 'verify-full' })
function openDb() {
  const d = db.value
  Object.assign(dbForm, { open: true, dialect: d?.dialect ?? 'postgresql', host: d?.host ?? '', port: String(d?.port ?? 5432), database: d?.database ?? '', username: d?.username ?? '', password: '', ssl_mode: d?.ssl_mode ?? 'verify-full' })
}
const defaultPort: Record<string, string> = { postgresql: '5432', mysql: '3306', oracle: '1521' }
const dbValid = computed(() => /^[A-Za-z0-9.\-:]+$/.test(dbForm.host) && /^\d+$/.test(dbForm.port) && Number(dbForm.port) <= 65535
  && /^[A-Za-z0-9_\-.]{1,128}$/.test(dbForm.database) && /^[A-Za-z0-9_\-.@]{1,128}$/.test(dbForm.username) && dbForm.password.length > 0)
async function submitDb() {
  if (!selected.value) return
  const id = selected.value.id
  const body = { dialect: dbForm.dialect, host: dbForm.host.trim(), port: Number(dbForm.port), database: dbForm.database.trim(), username: dbForm.username.trim(), password: dbForm.password, ssl_mode: dbForm.ssl_mode }
  if (await run('db', () => api.configureTenantDb(id, body), db.value ? 'Connection updated; credentials rotated.' : 'Dedicated database configured. Sync the schema next.')) { dbForm.open = false; await reload() }
}
async function syncDb() {
  if (!selected.value) return
  const id = selected.value.id
  if (await run('sync', () => api.syncTenantDb(id), 'Schema synced to the dedicated database.')) await reload()
}
async function disableDb() {
  if (!selected.value) return
  const t = selected.value
  if (!(await ask({ title: 'Disable dedicated database routing?', body: `Requests for ${t.slug} stop using its dedicated database. Data stays where it is.`, confirmLabel: 'Disable routing', danger: true }))) return
  if (await run('disable', () => api.disableTenantDb(t.id), 'Routing disabled.')) await reload()
}

// -- offboarding --
const off = reactive({ confirm: '', drop: false, task: null as Task | null })
let timer: ReturnType<typeof setTimeout> | undefined
async function pollTask() {
  if (!off.task) return
  try { off.task = await api.platformTask(off.task.id) } catch { /* keep last state */ }
  if (off.task && ['queued', 'running'].includes(off.task.status)) timer = setTimeout(pollTask, 2000)
  else if (off.task?.status === 'succeeded') { toast('Tenant erased.'); selected.value = null; await reload() }
}
onBeforeUnmount(() => clearTimeout(timer))
async function offboard() {
  if (!selected.value) return
  const t = selected.value
  if (!(await ask({ title: `Erase ${t.slug} permanently?`, body: 'Every row this tenant owns is deleted after its audit log is archived. There is no undo.', confirmLabel: 'Erase tenant', danger: true }))) return
  let task: Task | undefined
  if (await run('off', async () => { task = await api.offboard(t.id, off.confirm, off.drop) }, 'Offboarding started.') && task) {
    off.task = task; off.confirm = ''; await reload(); pollTask()
  }
}

async function exportSnapshot() {
  await run('snap', async () => {
    const snap = await api.exportSnapshot()
    download(`tenantcore-schema-${new Date().toISOString().slice(0, 10)}.json`, JSON.stringify(snap, null, 2), 'application/json')
  }, 'Schema snapshot downloaded.')
}
const inSync = computed(() => !db.value || db.value.synced_schema_version === (db.value.platform_schema_version ?? db.value.synced_schema_version))
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Tenants</h1><p class="muted">Every workspace on the platform. Shared tenants are isolated by row-level security; dedicated tenants have their own database.</p></div>
      <div class="actions">
        <UiButton icon="download" :loading="busy === 'snap'" @click="exportSnapshot">Export schema snapshot</UiButton>
        <UiButton variant="primary" icon="plus" @click="openCreate">New tenant</UiButton>
      </div>
    </div>
    <LoadState :loading="loading" :error="error" :ready="!!tenants.length" @retry="reload">
      <div class="stats">
        <StatCard label="Tenants" :value="String(counts.total)" :note="`${counts.active} active`" />
        <StatCard label="Dedicated databases" :value="String(counts.dedicated)" note="Tenants with their own database" />
        <StatCard label="Dead letters" :value="deadLetters === null ? '—' : String(deadLetters)" note="Failed jobs waiting for a retry" />
        <StatCard label="Suspended or offboarding" :value="String(counts.inactive)" note="Tenants that cannot sign in" />
      </div>

      <div class="split">
        <UiCard title="All tenants" :caption="`${tenants.length} tenants · newest first`">
          <template #actions><UiButton variant="ghost" icon="refresh" :loading="loading" @click="reload">Refresh</UiButton></template>
          <EmptyState v-if="!tenants.length" icon="tenants" title="No tenants yet" body="Create the first workspace and its owner account.">
            <UiButton variant="primary" icon="plus" @click="openCreate">New tenant</UiButton>
          </EmptyState>
          <div v-if="tenants.length" class="toolbar"><input v-model="q" class="search-input" type="search" placeholder="Search name, slug or id" aria-label="Search tenants" @input="limit = 50" /></div>
          <DataTable v-if="tenants.length" :columns="columns" :rows="shown.slice(0, limit)" row-key="id" :selected="selected?.id" clickable empty="No tenants match your search." @select="pick">
            <template #cell-name="{ row }"><strong>{{ row.name }}</strong><div class="caption">{{ row.slug }}</div></template>
            <template #cell-iso="{ row }"><UiBadge :tone="row.isolation_mode === 'dedicated' ? 'brand' : 'neutral'">{{ title(row.isolation_mode) }}</UiBadge></template>
            <template #cell-status="{ row }"><UiBadge :tone="tenantTone[row.status]">{{ title(row.status) }}</UiBadge></template>
            <template #cell-rl="{ row }">{{ row.rate_limit_per_min ? `${number(row.rate_limit_per_min)} / min` : 'Default' }}</template>
          </DataTable>
          <div v-if="shown.length > limit" class="more"><UiButton variant="ghost" @click="limit += 50">Show more ({{ shown.length - limit }} left)</UiButton></div>
        </UiCard>

        <DetailPanel :open="panel && !!selected" label="Tenant detail" @close="panel = false">
          <template v-if="selected">
            <header class="d-head">
              <div class="d-title"><h2>{{ selected.name }}</h2><UiBadge :tone="tenantTone[selected.status]">{{ title(selected.status) }}</UiBadge></div>
              <p class="caption mono">{{ selected.slug }} · {{ selected.id }}</p>
            </header>

            <section class="d-sec">
              <p class="label">Access</p>
              <div class="btns">
                <UiButton v-if="selected.status === 'active'" icon="lock" :loading="busy === 'status'" @click="setStatus(selected, 'suspended')">Suspend</UiButton>
                <UiButton v-else-if="selected.status === 'suspended'" variant="primary" :loading="busy === 'status'" @click="setStatus(selected, 'active')">Reactivate</UiButton>
                <p v-else class="caption">Offboarding in progress: the tenant can no longer sign in.</p>
              </div>
              <form class="rate" @submit.prevent="saveRate">
                <UiInput v-model="rate" label="Rate limit (requests/min)" inputmode="numeric" placeholder="Platform default" />
                <UiButton type="submit" :loading="busy === 'rate'" :disabled="!/^\d+$/.test(rate) || Number(rate) < 1 || Number(rate) === selected.rate_limit_per_min">Save</UiButton>
              </form>
            </section>

            <section class="d-sec">
              <div class="sec-head"><p class="label">Database</p><UiBadge v-if="db" :tone="db.status !== 'active' ? 'neutral' : inSync ? 'success' : 'warning'">{{ db.status !== 'active' ? title(db.status) : inSync ? 'In sync' : 'Sync due' }}</UiBadge></div>
              <p v-if="dbLoading" class="caption">Loading…</p>
              <template v-else-if="db">
                <div class="kv"><span>Engine</span><span>{{ title(db.dialect) }}</span></div>
                <div class="kv"><span>Host</span><span class="mono">{{ db.host }}:{{ db.port }}/{{ db.database }}</span></div>
                <div class="kv"><span>TLS</span><span>{{ db.ssl_mode }}</span></div>
                <div class="kv"><span>Schema version</span><span>{{ db.synced_schema_version ?? 'never synced' }}</span></div>
                <div class="kv"><span>Credentials</span><span>version {{ db.secret_version }}</span></div>
                <div class="btns">
                  <UiButton icon="refresh" :loading="busy === 'sync'" @click="syncDb">Sync schema</UiButton>
                  <UiButton icon="key" @click="openDb">Rotate credentials</UiButton>
                  <UiButton variant="ghost" :loading="busy === 'disable'" :disabled="db.status !== 'active'" @click="disableDb">Disable routing</UiButton>
                </div>
              </template>
              <template v-else>
                <p class="caption">Shared database, isolated with row-level security.</p>
                <div class="btns"><UiButton icon="database" :disabled="selected.status !== 'active'" @click="openDb">Move to a dedicated database</UiButton></div>
              </template>
            </section>

            <section class="d-sec danger-zone">
              <p class="label danger-text">Offboard tenant</p>
              <p class="caption">Archives the audit log (encrypted), deletes every row the tenant owns, clears its cached data and records the erasure. This cannot be undone.</p>
              <template v-if="selected.status !== 'offboarding'">
                <UiInput v-model="off.confirm" :label="`Type ${selected.slug} to confirm`" autocomplete="off" mono />
                <UiCheck v-if="selected.isolation_mode === 'dedicated'" v-model="off.drop" label="Also drop TenantCore objects from the dedicated database" />
                <UiButton variant="danger" block :loading="busy === 'off'" :disabled="off.confirm !== selected.slug" @click="offboard">Offboard {{ selected.slug }}</UiButton>
              </template>
              <div v-if="off.task" class="kv"><span>Task {{ off.task.id.slice(0, 8) }}</span><UiBadge :tone="taskTone[off.task.status]">{{ title(off.task.status) }}</UiBadge></div>
              <p v-if="off.task?.status === 'queued'" class="caption">Waiting for a background worker to pick it up.</p>
            </section>
          </template>
        </DetailPanel>
      </div>
    </LoadState>

    <UiModal :open="create.open" :title="create.done ? 'Tenant created' : 'New tenant'" :submit-label="create.done ? undefined : 'Create tenant'" :busy="busy === 'create'"
             :submit-disabled="!createValid" @close="create.open = false" @submit="submitCreate">
      <template v-if="!create.done">
        <UiInput :model-value="create.name" label="Name" required :maxlength="200" placeholder="Acme Store" @update:model-value="setName" />
        <UiInput :model-value="create.slug" label="Slug" required mono hint="Used to sign in. Lowercase letters, digits and dashes." :error="create.slug && !slugOk(create.slug) ? 'At least 2 characters: a-z, 0-9 and -' : ''"
                 @update:model-value="(v: string) => { create.slug = v; create.slugTouched = true }" />
        <p class="section-label">Owner account</p>
        <UiInput v-model="create.email" label="Owner email" type="email" required :error="create.email && !isEmail(create.email) ? 'Enter a valid email address.' : ''" />
        <div class="pw">
          <UiInput v-model="create.password" label="Owner password" required mono autocomplete="new-password" :error="create.password && create.password.length < 12 ? 'At least 12 characters.' : ''" />
          <div class="row-actions"><UiButton variant="ghost" icon="refresh" @click="create.password = strong()">Generate</UiButton></div>
        </div>
        <UiInput v-model="create.rate" label="Rate limit (requests/min)" inputmode="numeric" placeholder="Platform default" />
      </template>
      <template v-else>
        <p class="muted">Share these sign-in details with the owner over a secure channel. The password is not shown again.</p>
        <div class="kv"><span>Workspace</span><span class="mono">{{ create.done.slug }}</span></div>
        <div class="kv"><span>Email</span><span>{{ create.done.email }}</span></div>
        <div class="secret-out"><code>{{ create.done.password }}</code><UiButton icon="copy" @click="copy(create.done.password)">Copy</UiButton></div>
      </template>
    </UiModal>

    <UiModal :open="dbForm.open" :title="db ? 'Rotate database credentials' : 'Dedicated database'" caption="Credentials are encrypted at rest and never returned by the API."
             :submit-label="db ? 'Save and rotate' : 'Connect database'" :busy="busy === 'db'" :submit-disabled="!dbValid" @close="dbForm.open = false" @submit="submitDb">
      <div class="grid2">
        <UiSelect :model-value="dbForm.dialect" label="Engine" :options="[{ value: 'postgresql', label: 'PostgreSQL' }, { value: 'mysql', label: 'MySQL' }, { value: 'oracle', label: 'Oracle' }]"
                  @update:model-value="(v: string) => { dbForm.dialect = v; dbForm.port = defaultPort[v] }" />
        <UiSelect v-model="dbForm.ssl_mode" label="TLS" :options="[{ value: 'verify-full', label: 'Verify certificate (recommended)' }, { value: 'require', label: 'Require TLS' }, { value: 'disable', label: 'Disabled (dev only)' }]" />
      </div>
      <div class="grid2">
        <UiInput v-model="dbForm.host" label="Host" required mono />
        <UiInput v-model="dbForm.port" label="Port" inputmode="numeric" required />
      </div>
      <div class="grid2">
        <UiInput v-model="dbForm.database" label="Database" required mono />
        <UiInput v-model="dbForm.username" label="Username" required mono autocomplete="off" />
      </div>
      <UiInput v-model="dbForm.password" label="Password" type="password" required autocomplete="new-password" />
      <p v-if="dbForm.dialect !== 'postgresql'" class="note warn">MySQL and Oracle have no row-level security: isolation there relies on the database being dedicated to this tenant.</p>
    </UiModal>
  </div>
</template>

<style scoped>
.d-head { padding: 20px 24px; padding-right: 64px; }
.d-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 6px; }
.d-head .mono { overflow-wrap: anywhere; }
.d-sec { padding: 16px 24px; border-top: 1px solid var(--border-default); display: flex; flex-direction: column; gap: 10px; }
.sec-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.btns { display: flex; gap: 8px; flex-wrap: wrap; }
.btns > * { flex: 1 1 140px; }
.rate { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; align-items: end; }
.more { display: flex; justify-content: center; padding: 8px; border-top: 1px solid var(--border-default); }
.danger-zone { background: var(--danger-bg); }
.danger-text { color: var(--danger); }
.pw { display: grid; gap: 6px; }
@media (max-width: 767px) { .d-head, .d-sec { padding-left: 16px; padding-right: 16px; } .d-head { padding-right: 64px; } }
</style>
