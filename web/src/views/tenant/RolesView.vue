<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import { can, hasModule } from '@/api/me'
import { opt, useBusy, useLoader } from '@/composables'
import { title } from '@/format'
import { ask } from '@/ui'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'

interface Res { resource: string; label: string; group: string; actions: string[]; enabled: boolean; fields?: { key: string; label: string }[] }
interface Grant { action: string; row_scope: string; readable_fields: string[] | null; writable_fields: string[] | null }
const ACTIONS = ['read', 'create', 'update', 'delete', 'manage']
const LABELS: Record<string, string> = { rma: 'Returns', data_schema: 'Table design', audit_logs: 'Activity log', api_keys: 'API keys', notifications: 'Push notifications' }

const roles = ref<any[]>([])
const catalog = ref<Res[]>([])
const sel = ref<any>(null)
const form = reactive({ name: '', description: '', grants: {} as Record<string, Grant[]> })
const { loading, error, reload } = useLoader(async () => {
  const [r, c, t] = await Promise.all([api.get('/roles'), api.get('/permissions/catalog'), hasModule('data_tables') ? api.get('/data/tables').catch(() => ({ items: [] })) : Promise.resolve({ items: [] })])
  roles.value = r.items
  catalog.value = [
    ...c.items.map((i: any) => ({ resource: i.resource, label: LABELS[i.resource] ?? title(i.resource), group: i.module_label ?? 'Workspace', actions: i.actions, enabled: i.module_enabled })),
    ...t.items.map((x: any) => ({ resource: `data.${x.key}`, label: x.name, group: 'Custom tables', actions: ['read', 'create', 'update', 'delete', 'manage'], enabled: true,
      fields: x.fields.map((f: any) => ({ key: f.key, label: f.label })) })),
  ]
  if (sel.value) pick(roles.value.find((x) => x.id === sel.value.id) ?? null)
})
const groups = computed(() => {
  const m = new Map<string, Res[]>()
  for (const r of catalog.value) m.set(r.group, [...(m.get(r.group) ?? []), r])
  return [...m.entries()]
})
const manage = computed(() => can('roles', 'manage'))

function pick(r: any | null) {
  sel.value = r
  form.name = r?.name ?? ''; form.description = r?.description ?? ''
  form.grants = {}
  for (const p of r?.permissions ?? []) (form.grants[p.resource] ??= []).push({ action: p.action, row_scope: p.row_scope, readable_fields: p.readable_fields, writable_fields: p.writable_fields })
}
function startNew() { pick({ id: '', name: '', description: '', permissions: [], is_system: false }) }
const has = (res: string, a: string) => !!form.grants[res]?.some((g) => g.action === a)
function toggle(res: string, a: string) {
  const list = (form.grants[res] ??= [])
  const i = list.findIndex((g) => g.action === a)
  if (i >= 0) list.splice(i, 1)
  else list.push({ action: a, row_scope: list[0]?.row_scope ?? 'all', readable_fields: list[0]?.readable_fields ?? null, writable_fields: list[0]?.writable_fields ?? null })
}
const scope = (res: string) => form.grants[res]?.[0]?.row_scope ?? 'all'
function setScope(res: string, s: string) { for (const g of form.grants[res] ?? []) g.row_scope = s }
const fieldsOf = (res: string, kind: 'readable_fields' | 'writable_fields') => form.grants[res]?.[0]?.[kind] ?? null
function toggleField(res: string, kind: 'readable_fields' | 'writable_fields', key: string, all: string[]) {
  const cur = fieldsOf(res, kind) ?? [...all]
  const next = cur.includes(key) ? cur.filter((k) => k !== key) : [...cur, key]
  for (const g of form.grants[res] ?? []) g[kind] = next.length === all.length ? null : next
}
const openFields = ref('')
const { busy, run } = useBusy()
async function save() {
  const permissions = Object.entries(form.grants).flatMap(([resource, gs]) => gs.map((g) => ({ resource, ...g })))
  await run('save', async () => {
    const body = { name: form.name.trim(), description: opt(form.description), permissions }
    const r = sel.value.id ? await api.patch(`/roles/${sel.value.id}`, body) : await api.post('/roles', body)
    sel.value = r; await reload()
  }, 'Role saved. People with this role get the change on their next request.')
}
async function remove() {
  if (!await ask({ title: `Delete the role “${sel.value.name}”?`, body: sel.value.user_count ? `${sel.value.user_count} people lose the access it gives.` : undefined, confirmLabel: 'Delete', danger: true })) return
  if (await run('del', () => api.del(`/roles/${sel.value.id}`), 'Role deleted.')) { sel.value = null; reload() }
}
</script>

<template>
  <div class="page">
    <PageHeader title="Roles" caption="What each kind of team member can see and change. Workspace admins can always do everything.">
      <UiButton v-if="manage" icon="plus" variant="primary" @click="startNew">New role</UiButton>
    </PageHeader>
    <LoadState :loading="loading" :error="error" :ready="roles.length > 0 || !!catalog.length" @retry="reload">
      <div class="layout">
        <UiCard title="Roles">
          <div class="list">
            <button v-for="r in roles" :key="r.id" type="button" class="role" :class="{ on: sel?.id === r.id }" @click="pick(r)">
              <span><strong>{{ r.name }}</strong><span class="caption">{{ r.user_count }} {{ r.user_count === 1 ? 'person' : 'people' }} · {{ r.permissions.length }} permissions</span></span>
              <UiBadge v-if="r.is_system" tone="neutral">Built-in</UiBadge>
            </button>
            <p v-if="!roles.length" class="muted pad">No roles yet.</p>
          </div>
        </UiCard>
        <UiCard v-if="sel" :title="sel.id ? sel.name : 'New role'">
          <template #actions>
            <UiButton v-if="manage && sel.id && !sel.is_system" variant="ghost" icon="trash" @click="remove">Delete</UiButton>
            <UiButton v-if="manage" variant="primary" icon="check" :loading="busy === 'save'" :disabled="!form.name.trim()" @click="save">Save role</UiButton>
          </template>
          <div class="card-body">
            <div class="grid2"><UiInput v-model="form.name" label="Name" :disabled="!manage" /><UiInput v-model="form.description" label="Description" :disabled="!manage" /></div>
          </div>
          <div class="matrix-wrap">
            <table class="matrix">
              <thead><tr><th>Area</th><th v-for="a in ACTIONS" :key="a">{{ title(a) }}</th><th>Rows</th></tr></thead>
              <tbody>
                <template v-for="[g, items] in groups" :key="g">
                  <tr class="grp"><td :colspan="ACTIONS.length + 2">{{ g }}</td></tr>
                  <template v-for="r in items" :key="r.resource">
                    <tr :class="{ off: !r.enabled }">
                      <td><span>{{ r.label }}</span><span v-if="!r.enabled" class="caption"> (module off)</span>
                        <button v-if="r.fields && form.grants[r.resource]?.length" type="button" class="link" @click="openFields = openFields === r.resource ? '' : r.resource">Fields…</button></td>
                      <td v-for="a in ACTIONS" :key="a" class="c">
                        <input v-if="r.actions.includes(a)" type="checkbox" :checked="has(r.resource, a)" :disabled="!manage" :aria-label="`${r.label}: ${a}`" @change="toggle(r.resource, a)" />
                      </td>
                      <td class="c">
                        <select v-if="form.grants[r.resource]?.length && r.actions.includes('create')" :value="scope(r.resource)" :disabled="!manage" :aria-label="`${r.label}: which rows`" @change="setScope(r.resource, ($event.target as HTMLSelectElement).value)">
                          <option value="all">All</option><option value="own">Own only</option>
                        </select>
                      </td>
                    </tr>
                    <tr v-if="openFields === r.resource && r.fields" class="fields">
                      <td :colspan="ACTIONS.length + 2">
                        <p class="caption">Untick fields this role must not see or change. All ticked = every field, including ones added later.</p>
                        <div class="fgrid">
                          <div v-for="f in r.fields" :key="f.key" class="frow">
                            <span>{{ f.label }}</span>
                            <label><input type="checkbox" :disabled="!manage" :checked="!fieldsOf(r.resource, 'readable_fields') || fieldsOf(r.resource, 'readable_fields')!.includes(f.key)" @change="toggleField(r.resource, 'readable_fields', f.key, r.fields.map((x) => x.key))" /> see</label>
                            <label><input type="checkbox" :disabled="!manage" :checked="!fieldsOf(r.resource, 'writable_fields') || fieldsOf(r.resource, 'writable_fields')!.includes(f.key)" @change="toggleField(r.resource, 'writable_fields', f.key, r.fields.map((x) => x.key))" /> change</label>
                          </div>
                        </div>
                      </td>
                    </tr>
                  </template>
                </template>
              </tbody>
            </table>
          </div>
          <p class="caption pad">“Manage” includes reading and settings for that area. “Own only” limits a role to the records its people created.</p>
        </UiCard>
        <UiCard v-else><EmptyState icon="shield" title="Pick a role" body="Its permissions show here as a grid you can tick." /></UiCard>
      </div>
    </LoadState>
  </div>
</template>

<style scoped>
.layout { display: grid; gap: 24px; grid-template-columns: 300px minmax(0, 1fr); align-items: start; }
@media (max-width: 1023px) { .layout { grid-template-columns: minmax(0, 1fr); } }
.list { display: grid; padding: 0 12px 12px; }
.role { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 10px 12px; border: 0; background: none; border-radius: var(--radius-md); cursor: pointer; text-align: left; }
.role > span { display: flex; flex-direction: column; }
.role:hover { background: var(--bg-subtle); }
.role.on { background: var(--brand-soft); }
.pad { padding: 12px 24px; }
.matrix-wrap { overflow-x: auto; }
.matrix { width: 100%; border-collapse: collapse; font-size: 13px; }
.matrix th { background: var(--bg-subtle); color: var(--text-secondary); font-weight: 500; padding: 8px 10px; text-align: center; white-space: nowrap; }
.matrix th:first-child { text-align: left; padding-left: 24px; }
.matrix td { padding: 8px 10px; border-top: 1px solid var(--border-default); }
.matrix td:first-child { padding-left: 24px; }
.matrix .c { text-align: center; }
.matrix input[type=checkbox] { width: 16px; height: 16px; accent-color: var(--brand-primary); }
.matrix select { padding: 4px 6px; border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--bg-surface); font-size: 12px; }
.grp td { background: var(--bg-canvas); font-size: 11px; font-weight: 600; letter-spacing: .05em; text-transform: uppercase; color: var(--text-secondary); }
tr.off td { opacity: .5; }
.link { border: 0; background: none; color: var(--brand-primary); cursor: pointer; font-size: 12px; margin-left: 8px; }
.fields td { background: var(--bg-canvas); }
.fgrid { display: grid; gap: 4px; margin-top: 8px; }
.frow { display: flex; gap: 16px; align-items: center; }
.frow > span { flex: 0 0 180px; }
.frow label { display: flex; gap: 4px; align-items: center; }
</style>
