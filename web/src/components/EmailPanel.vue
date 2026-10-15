<script setup lang="ts">
// Mail server, templates and the outbox log. Used for the platform (base /platform/email) and for a
// workspace's own server and wording (base /email).
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import { opt, num, useBusy, useLoader } from '@/composables'
import { when } from '@/format'
import { ask } from '@/ui'
import DataTable from './DataTable.vue'
import LoadState from './LoadState.vue'
import StatusBadge from './StatusBadge.vue'
import UiBadge from './UiBadge.vue'
import UiButton from './UiButton.vue'
import UiCard from './UiCard.vue'
import UiInput from './UiInput.vue'
import UiModal from './UiModal.vue'
import UiSelect from './UiSelect.vue'
import UiTabs from './UiTabs.vue'

const props = defineProps<{ base: string; workspace?: boolean }>()
const tab = ref('server')
const { busy, run } = useBusy()

// ---- server -------------------------------------------------------------------------------------
const smtp = reactive({ configured: false, password_set: false, host: '', port: '587', username: '', password: '', security: 'starttls', from_address: '', from_name: '' })
const testTo = ref('')
const server = useLoader(async () => {
  const s = await api.get(`${props.base}/settings`)
  Object.assign(smtp, { configured: s.configured, password_set: s.password_set, password: '' })
  if (s.settings) Object.assign(smtp, { host: s.settings.host, port: String(s.settings.port), username: s.settings.username ?? '', security: s.settings.security, from_address: s.settings.from_address, from_name: s.settings.from_name ?? '' })
})
async function saveServer() {
  await run('smtp', async () => {
    await api.put(`${props.base}/settings`, {
      settings: { host: smtp.host.trim(), port: num(smtp.port), username: opt(smtp.username), security: smtp.security, from_address: smtp.from_address.trim(), from_name: opt(smtp.from_name) },
      password: smtp.password ? smtp.password : undefined,
    })
    await server.reload()
  }, 'Mail server saved.')
}
async function resetServer() {
  if (!await ask({ title: 'Stop using your own mail server?', body: 'Email goes through the platform\'s server again.', confirmLabel: 'Use the platform server' })) return
  await run('smtp', async () => { await api.del(`${props.base}/settings`); await server.reload() }, 'Using the platform mail server.')
}
async function sendTest() { await run('test', () => api.post(`${props.base}/test`, { to: testTo.value.trim() }), `Test email sent to ${testTo.value.trim()}. Check the log below if it does not arrive.`); log.reload() }

// ---- templates ----------------------------------------------------------------------------------
const templates = ref<any[]>([])
const tpl = useLoader(async () => { templates.value = (await api.get(`${props.base}/templates`)).items }, false)
const ed = reactive({ open: false, t: null as any, subject: '', html: '', text: '', view: 'html', preview: '' })
function editTpl(t: any) { Object.assign(ed, { open: true, t, subject: t.subject, html: t.html, text: t.text, view: 'html', preview: '' }) }
async function preview() {
  if (!props.workspace) { ed.preview = ed.html; ed.view = 'preview'; return }
  await run('preview', async () => { const r = await api.post(`${props.base}/templates/${ed.t.key}/preview`, { subject: ed.subject, html: ed.html, text: ed.text }); ed.preview = r.html; ed.view = 'preview' })
}
async function saveTpl() {
  const ok = await run('tpl', async () => { await api.put(`${props.base}/templates/${ed.t.key}`, { subject: ed.subject, html: ed.html, text: ed.text }); await tpl.reload() }, 'Template saved.')
  if (ok) ed.open = false
}
async function resetTpl() {
  if (!await ask({ title: 'Go back to the default wording?', confirmLabel: 'Reset' })) return
  const ok = await run('tpl', async () => { await api.del(`${props.base}/templates/${ed.t.key}`); await tpl.reload() }, 'Template reset.')
  if (ok) ed.open = false
}

// ---- log ----------------------------------------------------------------------------------------
const items = ref<any[]>([])
const log = useLoader(async () => { items.value = (await api.get(`${props.base}/log`, { limit: 100 })).items }, false)
const tabs = computed(() => [{ key: 'server', label: 'Mail server' }, { key: 'templates', label: 'Templates' }, { key: 'log', label: 'Sent mail' }])
function show(k: string) { tab.value = k; if (k === 'templates' && !templates.value.length) tpl.reload(); if (k === 'log') log.reload() }
const sourceTone = (s: string) => (s === 'default' ? 'neutral' : 'brand')
</script>

<template>
  <div class="panel">
    <UiTabs :model-value="tab" :tabs="tabs" @update:model-value="show" />
    <UiCard v-if="tab === 'server'" :title="workspace ? 'Your own mail server (optional)' : 'Outgoing mail server (SMTP)'"
            :caption="workspace ? 'Send this workspace\'s email from your own address and provider. Without it, email goes through the platform server.' : 'Used for invitations, password resets and every workspace without its own server.'">
      <template #actions><UiBadge :tone="smtp.configured ? 'success' : 'warning'">{{ smtp.configured ? 'Set up' : workspace ? 'Using platform server' : 'Not set up' }}</UiBadge></template>
      <LoadState :loading="server.loading.value" :error="server.error.value" :ready="true" @retry="server.reload">
        <form class="card-body" @submit.prevent="saveServer">
          <div class="grid3">
            <UiInput v-model="smtp.host" label="Host" placeholder="smtp.example.com" />
            <UiInput v-model="smtp.port" label="Port" inputmode="numeric" />
            <UiSelect v-model="smtp.security" label="Security" :options="[{ value: 'starttls', label: 'STARTTLS (587)' }, { value: 'ssl', label: 'SSL/TLS (465)' }, ...(workspace ? [] : [{ value: 'none', label: 'None (local testing only)' }])]" />
          </div>
          <div class="grid2">
            <UiInput v-model="smtp.username" label="Username" autocomplete="off" />
            <UiInput v-model="smtp.password" label="Password" type="password" autocomplete="new-password" :placeholder="smtp.password_set ? 'Saved – leave empty to keep' : ''" />
          </div>
          <div class="grid2">
            <UiInput v-model="smtp.from_address" label="From address" type="email" placeholder="hello@yourbusiness.com" />
            <UiInput v-model="smtp.from_name" label="From name" />
          </div>
          <div class="acts">
            <UiButton type="submit" variant="primary" :loading="busy === 'smtp'">Save</UiButton>
            <UiButton v-if="workspace && smtp.configured" variant="ghost" @click="resetServer">Use the platform server</UiButton>
          </div>
        </form>
      </LoadState>
      <div class="card-body test">
        <p class="section-label">Send a test</p>
        <div class="row"><UiInput v-model="testTo" label="To" type="email" placeholder="you@example.com" /><UiButton :loading="busy === 'test'" :disabled="!testTo.includes('@')" @click="sendTest">Send test</UiButton></div>
      </div>
    </UiCard>

    <UiCard v-else-if="tab === 'templates'" title="Templates" caption="The wording of each email. Use {{name}} placeholders from the list under each template.">
      <LoadState :loading="tpl.loading.value" :error="tpl.error.value" :ready="templates.length > 0" @retry="tpl.reload">
        <DataTable :columns="[{ key: 'label', label: 'Email', primary: true }, { key: 'source', label: '' }]" :rows="templates" row-key="key" clickable @select="editTpl">
          <template #cell-label="{ row }"><strong>{{ row.label }}</strong><span class="caption">{{ row.subject }}</span></template>
          <template #cell-source="{ row }"><UiBadge :tone="sourceTone(row.source)">{{ row.source === 'default' ? 'Default' : row.source === 'platform' ? 'Platform wording' : 'Customised' }}</UiBadge></template>
        </DataTable>
      </LoadState>
    </UiCard>

    <UiCard v-else title="Sent mail" caption="The last 100 emails and whether they went out.">
      <template #actions><UiButton variant="ghost" icon="refresh" @click="log.reload">Refresh</UiButton></template>
      <LoadState :loading="log.loading.value" :error="log.error.value" :ready="items.length > 0" @retry="log.reload">
        <DataTable :columns="[{ key: 'subject', label: 'Email', primary: true }, { key: 'status', label: 'Status' }]" :rows="items" row-key="id" empty="Nothing sent yet.">
          <template #cell-subject="{ row }"><strong>{{ row.subject }}</strong><span class="caption">to {{ row.to_address }} · {{ when(row.created_at) }}</span><span v-if="row.last_error" class="caption err">{{ row.last_error }}</span></template>
          <template #cell-status="{ row }"><StatusBadge :status="row.status" /></template>
        </DataTable>
      </LoadState>
    </UiCard>

    <UiModal :open="ed.open" :title="ed.t?.label ?? ''" wide submit-label="Save" :busy="busy === 'tpl'" @close="ed.open = false" @submit="saveTpl">
      <UiInput v-model="ed.subject" label="Subject" />
      <UiTabs v-model="ed.view" :tabs="[{ key: 'html', label: 'HTML' }, { key: 'text', label: 'Plain text' }, { key: 'preview', label: 'Preview' }]" @update:model-value="(v: string) => v === 'preview' && preview()" />
      <UiInput v-if="ed.view === 'html'" v-model="ed.html" label="HTML body" multiline :rows="12" mono />
      <UiInput v-else-if="ed.view === 'text'" v-model="ed.text" label="Plain-text body" multiline :rows="12" mono />
      <iframe v-else class="preview" sandbox="" :srcdoc="ed.preview" title="Email preview" />
      <p class="caption">Placeholders: <span v-for="v in ed.t?.variables ?? []" :key="v" class="mono">{{ '{' + '{' + v + '}' + '}' }} </span></p>
      <template v-if="ed.t && ed.t.source !== 'default'" #footer-start><UiButton variant="ghost" icon="undo" @click="resetTpl">Reset to default</UiButton></template>
    </UiModal>
  </div>
</template>

<style scoped>
.panel { display: grid; gap: 16px; }
.acts { display: flex; gap: 8px; flex-wrap: wrap; }
.test { border-top: 1px solid var(--border-default); padding-top: 16px; }
.row { display: flex; gap: 8px; align-items: flex-end; flex-wrap: wrap; }
.row > :first-child { flex: 1 1 240px; }
.err { color: var(--danger); }
.preview { width: 100%; height: 360px; border: 1px solid var(--border-default); border-radius: var(--radius-md); background: #fff; }
</style>
