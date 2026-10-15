<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { num, opt, str, useBusy, useLoader } from '@/composables'
import { bytes, dateTime, when } from '@/format'
import { ask } from '@/ui'
import DataTable from '@/components/DataTable.vue'
import JsonField from '@/components/JsonField.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiSelect from '@/components/UiSelect.vue'
import UiTabs from '@/components/UiTabs.vue'

const props = defineProps<{ id: string; tab?: string }>()
const router = useRouter()
const tab = ref(props.tab ?? 'overview')
watch(tab, (t) => router.replace(`/platform/tenants/${props.id}/${t}`))
const t = ref<any>(null)
const modules = ref<any[]>([])
const { busy, run } = useBusy()
const base = useLoader(async () => {
  const [x, m] = await Promise.all([api.get(`/platform/tenants/${props.id}`), api.get(`/platform/tenants/${props.id}/modules`)])
  t.value = x; modules.value = m.items
  Object.assign(ov, { name: x.name, status: x.status === 'offboarding' ? 'active' : x.status, rate: str(x.rate_limit_per_min) })
})

// ---- overview -----------------------------------------------------------------------------------
const ov = reactive({ name: '', status: 'active', rate: '' })
async function saveOverview() {
  await run('ov', async () => {
    t.value = await api.patch(`/platform/tenants/${props.id}`, { name: ov.name.trim(), status: t.value.status === 'offboarding' ? undefined : ov.status,
      ...(num(ov.rate) === null ? { use_default_rate_limit: true } : { rate_limit_per_min: num(ov.rate) }) })
    await base.reload()
  }, ov.status === 'suspended' ? 'Suspended. Nobody in this workspace can sign in until you reactivate it.' : 'Saved.')
}

// ---- modules ------------------------------------------------------------------------------------
const enabled = computed(() => new Set(modules.value.filter((m) => m.enabled).map((m) => m.code)))
async function toggleModule(code: string) {
  const next = new Set(enabled.value)
  if (next.has(code)) {
    const drop = new Set([code]); let grew = true
    while (grew) { grew = false; for (const m of modules.value) if (!drop.has(m.code) && next.has(m.code) && m.requires.some((r: string) => drop.has(r))) { drop.add(m.code); grew = true } }
    if (drop.size > 1 && !await ask({ title: `Also switch off ${[...drop].filter((c) => c !== code).map((c) => modules.value.find((m) => m.code === c)?.label).join(', ')}?`, body: 'They need this module. Data is kept; switching back on brings it back.', confirmLabel: 'Switch off' })) return
    drop.forEach((c) => next.delete(c))
  } else {
    const add = [code]
    for (let i = 0; i < add.length; i++) for (const r of modules.value.find((m) => m.code === add[i])?.requires ?? []) if (!add.includes(r)) add.push(r)
    add.forEach((c) => next.add(c))
  }
  await run('mod', async () => {
    modules.value = (await api.put(`/platform/tenants/${props.id}/modules`, { modules: Object.fromEntries(modules.value.map((m) => [m.code, next.has(m.code)])) })).items
  }, 'Modules updated. People in the workspace see the change on their next page load.')
}
const HINTS: Record<string, string> = {
  files: '{ "max_file_mb": 20, "quota_mb": 1024 }',
  data_tables: '{ "max_tables": 50, "max_fields_per_table": 100, "max_rows_per_table": 100000 }',
  imports: '{ "max_file_mb": 10, "max_rows": 50000 }',
  delivery_tracking: '{ "order_status_on_picked_up": "shipped", "order_status_on_delivered": "delivered" }',
}
const cfg = reactive({ open: false, code: '', label: '', text: '' })
const cfgField = ref<InstanceType<typeof JsonField> | null>(null)
async function openConfig(m: any) {
  const c = await api.get(`/platform/tenants/${props.id}/modules/${m.code}/config`)
  Object.assign(cfg, { open: true, code: m.code, label: m.label, text: JSON.stringify(c, null, 2) })
}
async function saveConfig() {
  let body: unknown
  try { body = JSON.parse(cfg.text || '{}') } catch { return }
  const ok = await run('cfg', () => api.put(`/platform/tenants/${props.id}/modules/${cfg.code}/config`, body), 'Module options saved.')
  if (ok) cfg.open = false
}

// ---- branding (the workspace's settings, edited by the platform) ---------------------------------
const br = reactive({ loaded: false, display_name: '', primary_color: '#4f46e5', currency: 'USD', timezone: 'UTC', locale: 'en' })
async function loadBranding() {
  const s = await api.get(`/platform/tenants/${props.id}/settings`)
  Object.assign(br, { loaded: true, display_name: s.display_name ?? '', primary_color: s.primary_color ?? '#4f46e5', currency: s.currency, timezone: s.timezone, locale: s.locale })
}
async function saveBranding() {
  await run('br', () => api.put(`/platform/tenants/${props.id}/settings`, { display_name: opt(br.display_name), primary_color: br.primary_color, currency: br.currency.toUpperCase(), timezone: br.timezone, locale: br.locale }), 'Saved.')
}

// ---- exports and offboarding --------------------------------------------------------------------
const exports = ref<any[]>([])
let timer: ReturnType<typeof setTimeout> | undefined
async function loadExports() {
  exports.value = (await api.get(`/platform/tenants/${props.id}/exports`)).items
  clearTimeout(timer)
  if (exports.value.some((e) => ['queued', 'running'].includes(e.status))) timer = setTimeout(loadExports, 2000)
}
onBeforeUnmount(() => clearTimeout(timer))
async function newExport() { await run('exp', async () => { await api.post(`/platform/tenants/${props.id}/exports`); await loadExports() }, 'Export started.') }
async function download(e: any) { await run('dl:' + e.id, () => api.download(`/platform/exports/${e.id}/download`, `${t.value.slug}-${e.id.slice(0, 8)}.zip`)) }
watch(tab, (k) => { if (k === 'branding' && !br.loaded) loadBranding(); if (k === 'data') loadExports() }, { immediate: true })

const off = reactive({ open: false, confirm: '' })
async function offboard() {
  const ok = await run('off', async () => { t.value = await api.post(`/platform/tenants/${props.id}/offboard`, { confirm: off.confirm }); await loadExports() },
    'Workspace locked and everyone signed out. A final export is being made.')
  if (ok) { off.open = false; tab.value = 'data' }
}
async function cancelOff() { await run('off', async () => { t.value = await api.post(`/platform/tenants/${props.id}/cancel-offboarding`); await base.reload() }, 'Offboarding cancelled. The workspace is active again.') }
const purge = reactive({ open: false, confirm: '', force: false })
const finishedExport = computed(() => exports.value.some((e) => e.status === 'done'))
async function doPurge() {
  const ok = await run('purge', () => api.del(`/platform/tenants/${props.id}`, { confirm: purge.confirm, force: purge.force || undefined }), 'Deleting everything. This runs in the background.')
  if (ok) { purge.open = false; router.push('/platform') }
}
</script>

<template>
  <div class="page">
    <LoadState :loading="base.loading.value" :error="base.error.value" :ready="!!t" @retry="base.reload">
      <template v-if="t">
        <PageHeader :title="t.name" back="/platform">
          <template #sub><div class="chips sub"><StatusBadge :status="t.status" /><span class="caption mono">{{ t.slug }}</span><span class="caption">{{ t.user_count }} {{ t.user_count === 1 ? "person" : "people" }} · since {{ dateTime(t.created_at) }}</span></div></template>
        </PageHeader>
        <p v-if="t.status === 'offboarding'" class="note danger">This workspace is being offboarded: nobody can sign in and its shop is offline. Download the final export, then delete it — or cancel to bring it back.</p>
        <UiTabs v-model="tab" :tabs="[{ key: 'overview', label: 'Overview' }, { key: 'modules', label: 'Modules' }, { key: 'branding', label: 'Business settings' }, { key: 'data', label: 'Data & offboarding' }]" />

        <UiCard v-if="tab === 'overview'" title="Workspace">
          <form class="card-body" @submit.prevent="saveOverview">
            <UiInput v-model="ov.name" label="Name" />
            <div class="grid2">
              <UiSelect v-model="ov.status" label="Status" :disabled="t.status === 'offboarding'" :options="[{ value: 'active', label: 'Active' }, { value: 'suspended', label: 'Suspended (nobody can sign in)' }]" />
              <UiInput v-model="ov.rate" label="Requests per minute" inputmode="numeric" placeholder="Platform default" />
            </div>
            <div class="kv"><span>ID</span><span class="mono">{{ t.id }}</span></div>
            <div class="kv"><span>Sign-in workspace name</span><span class="mono">{{ t.slug }}</span></div>
            <div><UiButton type="submit" variant="primary" :loading="busy === 'ov'">Save</UiButton></div>
          </form>
        </UiCard>

        <UiCard v-else-if="tab === 'modules'" title="Modules" caption="Switch parts of the system on or off for this business. Switching off hides them but keeps the data.">
          <div class="mods">
            <div v-for="m in modules" :key="m.code" class="mod" :class="{ on: m.enabled }">
              <UiCheck :model-value="m.enabled" toggle :label="m.label" :hint="m.description + (m.requires.length ? ` · needs ${m.requires.join(', ')}` : '')" :disabled="busy === 'mod'" @update:model-value="toggleModule(m.code)" />
              <UiButton variant="ghost" icon="settings" :aria-label="`Options for ${m.label}`" @click="openConfig(m)">Options</UiButton>
            </div>
          </div>
        </UiCard>

        <UiCard v-else-if="tab === 'branding'" title="Business settings" caption="The same settings the workspace admin sees under Settings → Business.">
          <form v-if="br.loaded" class="card-body" @submit.prevent="saveBranding">
            <UiInput v-model="br.display_name" label="Business name" />
            <div class="grid3">
              <UiInput v-model="br.primary_color" label="Brand colour" mono />
              <UiInput v-model="br.currency" label="Currency" mono />
              <UiInput v-model="br.locale" label="Language" mono />
            </div>
            <UiInput v-model="br.timezone" label="Time zone" mono />
            <div><UiButton type="submit" variant="primary" :loading="busy === 'br'">Save</UiButton></div>
          </form>
        </UiCard>

        <template v-else>
          <UiCard title="Exports" caption="Full copies of this workspace's data and files. Kept 7 days.">
            <template #actions><UiButton icon="download" :loading="busy === 'exp'" @click="newExport">New export</UiButton></template>
            <DataTable :columns="[{ key: 'when', label: 'Requested', primary: true }, { key: 'status', label: 'Status' }, { key: 'size', label: 'Size', align: 'right' }, { key: 'acts', label: '' }]" :rows="exports" row-key="id" empty="No exports yet.">
              <template #cell-when="{ row }"><strong>{{ when(row.created_at) }}</strong><span class="caption">by {{ row.requested_by_type }} · expires {{ dateTime(row.expires_at) }}</span></template>
              <template #cell-status="{ row }"><StatusBadge :status="row.status" /><span v-if="row.error" class="caption">{{ row.error }}</span></template>
              <template #cell-size="{ row }">{{ bytes(row.size_bytes) }}</template>
              <template #cell-acts="{ row }"><div class="row-actions"><UiButton v-if="row.status === 'done'" variant="ghost" icon="download" :loading="busy === 'dl:' + row.id" @click="download(row)">Download</UiButton></div></template>
            </DataTable>
          </UiCard>
          <UiCard title="Offboarding" tone="danger" caption="For a business that is leaving. Step 1 locks the workspace and makes a final export. Step 2 deletes every record and file for good.">
            <div class="card-body">
              <div class="steps">
                <div>
                  <p><strong>1. Lock and export</strong></p>
                  <p class="caption">Signs everyone out, blocks sign-in and the shop, and starts a final export.</p>
                  <UiButton v-if="t.status !== 'offboarding'" variant="danger" @click="Object.assign(off, { open: true, confirm: '' })">Start offboarding</UiButton>
                  <UiButton v-else :loading="busy === 'off'" @click="cancelOff">Cancel offboarding</UiButton>
                </div>
                <div>
                  <p><strong>2. Delete everything</strong></p>
                  <p class="caption">Removes all rows in every table and every stored file. The activity log keeps one entry that it happened. Backups still contain the data until they age out.</p>
                  <UiButton variant="danger" icon="trash" :disabled="t.status !== 'offboarding'" @click="Object.assign(purge, { open: true, confirm: '', force: false })">Delete workspace</UiButton>
                </div>
              </div>
            </div>
          </UiCard>
        </template>
      </template>
    </LoadState>

    <UiModal :open="cfg.open" :title="`${cfg.label} options`" wide submit-label="Save" :busy="busy === 'cfg'" :submit-disabled="!!cfgField?.error" @close="cfg.open = false" @submit="saveConfig">
      <JsonField ref="cfgField" v-model="cfg.text" label="Options (JSON)" :rows="10" :hint="HINTS[cfg.code] ? `Limits you can set: ${HINTS[cfg.code]}` : 'Advanced. The workspace changes most of these itself from its Settings page.'" />
      <p class="note warn">This replaces all options for the module. Keep keys you do not mean to change.</p>
    </UiModal>
    <UiModal :open="off.open" title="Start offboarding" submit-label="Lock workspace" danger :busy="busy === 'off'" :submit-disabled="off.confirm !== t?.slug" @close="off.open = false" @submit="offboard">
      <p class="muted">Everyone in {{ t?.name }} is signed out and cannot sign in again. Customers and the shop API stop working. You can cancel until you delete it.</p>
      <UiInput v-model="off.confirm" :label="`Type ${t?.slug} to confirm`" mono autocomplete="off" />
    </UiModal>
    <UiModal :open="purge.open" title="Delete this workspace for good" submit-label="Delete everything" danger :busy="busy === 'purge'" :submit-disabled="purge.confirm !== t?.slug || (!finishedExport && !purge.force)" @close="purge.open = false" @submit="doPurge">
      <p class="muted">Every order, product, customer, table row and file of {{ t?.name }} is deleted. This cannot be undone.</p>
      <p v-if="!finishedExport" class="note warn">There is no finished export yet. Wait for it, or tick below to delete without one.</p>
      <UiInput v-model="purge.confirm" :label="`Type ${t?.slug} to confirm`" mono autocomplete="off" />
      <UiCheck v-if="!finishedExport" v-model="purge.force" label="Delete without a final export" />
    </UiModal>
  </div>
</template>

<style scoped>
.sub { margin-top: 6px; align-items: center; }
.mods { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 10px; padding: 0 24px 24px; }
.mod { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; padding: 12px; border: 1px solid var(--border-default); border-radius: var(--radius-md); }
.mod.on { border-color: color-mix(in srgb, var(--brand-primary) 40%, var(--border-default)); }
.steps { display: grid; gap: 20px; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); }
.steps > div { display: grid; gap: 8px; justify-items: start; align-content: start; }
</style>
