<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import type { Action, DataTable as Table, Me, Permission, Role, User } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
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
import { isEmail, title, when } from '@/format'
import { copy } from '@/ui'

const users = ref<User[]>([])
const roles = ref<Role[]>([])
const tables = ref<Table[]>([])
const me = ref<Me | null>(null)
const roleId = ref('')
const { busy, run } = useBusy()

const { loading, error, reload } = useLoader(async () => {
  const [u, r, m, t] = await Promise.all([api.users(), api.roles(), api.me().catch(() => null), api.tables().catch(() => ({ items: [] as Table[] }))])
  users.value = u.items; roles.value = r.items; me.value = m; tables.value = t.items
  if (!roles.value.some((x) => x.id === roleId.value)) roleId.value = roles.value[0]?.id ?? ''
})

const role = computed(() => roles.value.find((r) => r.id === roleId.value))
const roleName = (id: string) => roles.value.find((r) => r.id === id)?.name ?? 'Unknown role'
const locked = (u: User) => !!u.locked_until && new Date(u.locked_until) > new Date()
const initials = (u: User) => (u.full_name || u.email).split(/[\s@.]/).filter(Boolean).map((s) => s[0]).join('').slice(0, 2).toUpperCase()
const columns: Column[] = [
  { key: 'user', label: 'User', primary: true }, { key: 'roles', label: 'Roles' }, { key: 'status', label: 'Status' },
  { key: 'last', label: 'Last login', hideOnMobile: true }, { key: 'act', label: '', align: 'right' },
]

// Permission model (app/models/rbac.py): admin resources take read/manage only.
const ACTIONS: Action[] = ['read', 'create', 'update', 'delete', 'manage']
const ADMIN = ['users', 'roles', 'api_keys', 'audit_logs', 'settings', 'analytics', 'webhooks', 'notifications']
const DOMAIN = ['products', 'inventory', 'purchasing', 'orders', 'rma', 'payments', 'maps', 'imports', 'deliveries']
const allowed = (res: string, a: Action) => (ADMIN.includes(res) ? a === 'read' || a === 'manage' : res.startsWith('data.') ? a !== 'manage' : true)
const resources = computed(() => [...new Set(role.value?.permissions.map((p) => p.resource) ?? [])].sort())
const has = (res: string, a: Action) => role.value?.permissions.some((p) => p.resource === res && p.action === a) ?? false
const rules = computed(() => (role.value?.permissions ?? []).flatMap((p) => [
  ...(p.row_scope === 'own' ? [{ k: `${p.resource} · ${p.action}`, v: 'Own rows only', tone: 'info' as const }] : []),
  ...(p.readable_fields ? [{ k: `${p.resource} · can read`, v: p.readable_fields.join(', '), tone: 'warning' as const }] : []),
  ...(p.writable_fields ? [{ k: `${p.resource} · can write`, v: p.writable_fields.join(', '), tone: 'warning' as const }] : []),
]))
const members = (r: Role) => users.value.filter((u) => u.role_ids?.includes(r.id)).length

// -- add user --
const strong = () => { const a = new Uint8Array(12); crypto.getRandomValues(a); return 'Tc-' + Array.from(a, (b) => 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'[b % 56]).join('') + '-9' }
const add = reactive({ open: false, email: '', full_name: '', password: '', admin: false, roles: [] as string[] })
function openAdd() { Object.assign(add, { open: true, email: '', full_name: '', password: strong(), admin: false, roles: [] }) }
const addValid = computed(() => isEmail(add.email) && add.password.length >= 12)
async function submitAdd() {
  let created: User | undefined
  const ok = await run('add', async () => {
    created = await api.createUser({ email: add.email.trim(), password: add.password, full_name: add.full_name.trim() || null, is_tenant_admin: add.admin })
    if (add.roles.length && !add.admin) await api.setUserRoles(created.id, add.roles)
  }, `User ${add.email} created. Share the password with them securely.`)
  if (ok && created) { add.open = false; await reload() }
}

// -- edit user --
const edit = reactive({ open: false, id: '', active: true, admin: false, roles: [] as string[] })
const editing = computed(() => users.value.find((u) => u.id === edit.id))
function openEdit(u: User) { Object.assign(edit, { open: true, id: u.id, active: u.is_active, admin: u.is_tenant_admin, roles: [...(u.role_ids ?? [])] }) }
async function submitEdit() {
  const u = editing.value; if (!u) return
  const patch: { is_active?: boolean; is_tenant_admin?: boolean } = {}
  if (edit.active !== u.is_active) patch.is_active = edit.active
  if (edit.admin !== u.is_tenant_admin) patch.is_tenant_admin = edit.admin
  const rolesChanged = [...edit.roles].sort().join() !== [...(u.role_ids ?? [])].sort().join()
  const ok = await run('edit', async () => {
    if (Object.keys(patch).length) await api.updateUser(u.id, patch)
    if (rolesChanged) await api.setUserRoles(u.id, edit.roles)
  }, 'User updated.')
  if (ok) { await reload(); edit.open = false }
}
function toggle(list: string[], id: string) { const i = list.indexOf(id); if (i >= 0) list.splice(i, 1); else list.push(id) }

// -- new role --
interface Grant { resource: string; actions: Action[]; ownOnly: boolean; readFields: string[] | null; writeFields: string[] | null }
const newRole = reactive({ open: false, name: '', description: '', grants: [] as Grant[], pick: '' })
const resourceOptions = computed(() => [
  ...DOMAIN.map((r) => ({ value: r, label: title(r) })), ...ADMIN.map((r) => ({ value: r, label: `${title(r)} (admin)` })),
  ...tables.value.map((t) => ({ value: `data.${t.name}`, label: `Data · ${title(t.name)}` })),
].filter((o) => !newRole.grants.some((g) => g.resource === o.value)))
function openRole() { Object.assign(newRole, { open: true, name: '', description: '', grants: [], pick: '' }) }
function addGrant() { if (newRole.pick) { newRole.grants.push({ resource: newRole.pick, actions: ['read'], ownOnly: false, readFields: null, writeFields: null }); newRole.pick = '' } }
const fieldsOf = (res: string) => tables.value.find((t) => `data.${t.name}` === res)?.fields.map((f) => f.name) ?? []
const roleValid = computed(() => /^[A-Za-z0-9 _.-]{1,100}$/.test(newRole.name) && newRole.grants.some((g) => g.actions.length))
async function submitRole() {
  const permissions: (Pick<Permission, 'resource' | 'action'> & Partial<Permission>)[] = newRole.grants.flatMap((g) => g.actions.map((action) => {
    const isData = g.resource.startsWith('data.')
    return {
      resource: g.resource, action,
      ...(isData && g.ownOnly ? { row_scope: 'own' as const } : {}),
      ...(isData && action === 'read' && g.readFields ? { readable_fields: g.readFields } : {}),
      ...(isData && (action === 'create' || action === 'update') && g.writeFields ? { writable_fields: g.writeFields } : {}),
    }
  }))
  let created: Role | undefined
  if (await run('role', async () => { created = await api.createRole({ name: newRole.name.trim(), description: newRole.description.trim() || null, permissions }) }, `Role “${newRole.name}” created.`) && created) {
    newRole.open = false; await reload(); roleId.value = created.id
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Users &amp; roles</h1><p class="muted">Invite people, decide what they can do, and lock them out when needed. You can only grant permissions you hold yourself.</p></div>
      <div class="actions"><UiButton icon="plus" @click="openRole">New role</UiButton><UiButton variant="primary" icon="plus" @click="openAdd">Add user</UiButton></div>
    </div>
    <LoadState :loading="loading" :error="error" :ready="!!users.length" @retry="reload">
      <div class="split">
        <UiCard title="Users" :caption="`${users.length} users · ${users.filter((u) => u.is_active).length} active`">
          <DataTable :columns="columns" :rows="users" row-key="id" clickable @select="openEdit">
            <template #cell-user="{ row }">
              <div class="who"><span class="avatar">{{ initials(row) }}</span><div><strong>{{ row.full_name || row.email }}</strong><span v-if="row.id === me?.user_id" class="you">You</span><div class="caption">{{ row.email }}</div></div></div>
            </template>
            <template #cell-roles="{ row }">
              <UiBadge v-if="row.is_tenant_admin" tone="brand">Tenant admin</UiBadge>
              <span v-else-if="row.role_ids?.length" class="chips"><span v-for="id in row.role_ids" :key="id" class="chip">{{ roleName(id) }}</span></span>
              <span v-else class="caption">No roles</span>
            </template>
            <template #cell-status="{ row }">
              <UiBadge :tone="!row.is_active ? 'neutral' : locked(row) ? 'warning' : 'success'">{{ !row.is_active ? 'Disabled' : locked(row) ? 'Locked' : 'Active' }}</UiBadge>
            </template>
            <template #cell-last="{ row }">{{ row.last_login_at ? when(row.last_login_at) : 'Never' }}</template>
            <template #cell-act><UiButton variant="ghost" icon="edit">Edit</UiButton></template>
          </DataTable>
        </UiCard>

        <UiCard title="Roles" :caption="`${roles.length} roles · permissions are fixed once a role is created`">
          <EmptyState v-if="!roles.length" icon="shield" title="No roles yet" body="Create a role to give non-admin users access to specific areas.">
            <UiButton variant="primary" icon="plus" @click="openRole">New role</UiButton>
          </EmptyState>
          <template v-else>
            <div class="toolbar"><UiSelect v-model="roleId" label="Role" :options="roles.map((r) => ({ value: r.id, label: `${r.name} · ${members(r)} users` }))" /></div>
            <template v-if="role">
              <p v-if="role.description" class="desc muted">{{ role.description }}</p>
              <div class="matrix-wrap">
                <table class="matrix">
                  <thead><tr><th scope="col">Resource</th><th v-for="a in ACTIONS" :key="a" scope="col" :title="a"><span class="full">{{ title(a) }}</span><span class="short" aria-hidden="true">{{ a[0].toUpperCase() }}</span></th></tr></thead>
                  <tbody>
                    <tr v-for="r in resources" :key="r">
                      <th scope="row">{{ r }}</th>
                      <td v-for="a in ACTIONS" :key="a">
                        <span v-if="!allowed(r, a)" class="caption">n/a</span>
                        <span v-else class="box" :class="{ on: has(r, a) }" role="img" :aria-label="has(r, a) ? 'allowed' : 'not allowed'"><AppIcon v-if="has(r, a)" name="check" :size="12" /></span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="rules.length" class="rules">
                <p class="label">Row &amp; field security</p>
                <div v-for="x in rules" :key="x.k" class="kv"><span>{{ x.k }}</span><UiBadge :tone="x.tone">{{ x.v }}</UiBadge></div>
              </div>
            </template>
          </template>
        </UiCard>
      </div>
    </LoadState>

    <UiModal :open="add.open" title="Add user" submit-label="Create user" :busy="busy === 'add'" :submit-disabled="!addValid" @close="add.open = false" @submit="submitAdd">
      <UiInput v-model="add.email" label="Email" type="email" required autocomplete="off" :error="add.email && !isEmail(add.email) ? 'Enter a valid email address.' : ''" />
      <UiInput v-model="add.full_name" label="Full name" autocomplete="off" />
      <div class="pw">
        <UiInput v-model="add.password" label="Initial password" required mono autocomplete="new-password" hint="At least 12 characters. Ask them to change it after signing in."
                 :error="add.password && add.password.length < 12 ? 'At least 12 characters.' : ''" />
        <div class="row-actions"><UiButton variant="ghost" icon="refresh" @click="add.password = strong()">Generate</UiButton><UiButton variant="ghost" icon="copy" @click="copy(add.password)">Copy</UiButton></div>
      </div>
      <UiCheck v-model="add.admin" label="Tenant admin" hint="Full access to everything in this workspace." />
      <template v-if="!add.admin && roles.length">
        <p class="section-label">Roles</p>
        <UiCheck v-for="r in roles" :key="r.id" :model-value="add.roles.includes(r.id)" :label="r.name" :hint="r.description ?? undefined" @update:model-value="toggle(add.roles, r.id)" />
      </template>
    </UiModal>

    <UiModal :open="edit.open" :title="editing?.full_name || editing?.email || 'User'" :caption="editing?.email" submit-label="Save changes" :busy="busy === 'edit'" @close="edit.open = false" @submit="submitEdit">
      <p v-if="editing && locked(editing)" class="note warn">Locked after repeated failed sign-ins until {{ when(editing.locked_until) }}. The lock clears by itself.</p>
      <UiCheck v-model="edit.active" toggle label="Active" hint="Disabled users cannot sign in and their sessions stop working." :disabled="edit.id === me?.user_id" />
      <UiCheck v-model="edit.admin" toggle label="Tenant admin" hint="Full access to everything in this workspace." :disabled="edit.id === me?.user_id" />
      <p v-if="edit.id === me?.user_id" class="caption">You cannot disable or demote yourself.</p>
      <template v-if="!edit.admin">
        <p class="section-label">Roles</p>
        <p v-if="!roles.length" class="caption">No roles exist yet.</p>
        <UiCheck v-for="r in roles" :key="r.id" :model-value="edit.roles.includes(r.id)" :label="r.name" :hint="r.description ?? undefined" @update:model-value="toggle(edit.roles, r.id)" />
      </template>
    </UiModal>

    <UiModal :open="newRole.open" title="New role" caption="Pick resources, then what the role may do with each." submit-label="Create role" wide
             :busy="busy === 'role'" :submit-disabled="!roleValid" @close="newRole.open = false" @submit="submitRole">
      <div class="grid2">
        <UiInput v-model="newRole.name" label="Name" required :maxlength="100" placeholder="e.g. Support" :error="newRole.name && !/^[A-Za-z0-9 _.-]{1,100}$/.test(newRole.name) ? 'Letters, digits, spaces, . _ -' : ''" />
        <UiInput v-model="newRole.description" label="Description" :maxlength="500" />
      </div>
      <div class="add-grant">
        <UiSelect v-model="newRole.pick" label="Add a resource" :options="resourceOptions" placeholder="Choose…" />
        <UiButton icon="plus" :disabled="!newRole.pick" @click="addGrant">Add</UiButton>
      </div>
      <p v-if="!newRole.grants.length" class="caption">No permissions yet.</p>
      <div v-for="(g, i) in newRole.grants" :key="g.resource" class="grant">
        <div class="grant-head"><strong>{{ g.resource }}</strong><UiButton variant="ghost" icon="trash" :aria-label="`Remove ${g.resource}`" @click="newRole.grants.splice(i, 1)" /></div>
        <div class="acts">
          <UiCheck v-for="a in ACTIONS.filter((x) => allowed(g.resource, x))" :key="a" :model-value="g.actions.includes(a)" :label="title(a)" @update:model-value="toggle(g.actions, a)" />
        </div>
        <template v-if="g.resource.startsWith('data.')">
          <UiCheck v-model="g.ownOnly" label="Only rows they created" />
          <UiCheck :model-value="g.readFields !== null" label="Limit readable fields" @update:model-value="(v: boolean) => (g.readFields = v ? [...fieldsOf(g.resource)] : null)" />
          <div v-if="g.readFields" class="acts sub"><UiCheck v-for="f in fieldsOf(g.resource)" :key="f" :model-value="g.readFields.includes(f)" :label="f" @update:model-value="toggle(g.readFields, f)" /></div>
          <UiCheck :model-value="g.writeFields !== null" label="Limit writable fields" @update:model-value="(v: boolean) => (g.writeFields = v ? [...fieldsOf(g.resource)] : null)" />
          <div v-if="g.writeFields" class="acts sub"><UiCheck v-for="f in fieldsOf(g.resource)" :key="f" :model-value="g.writeFields.includes(f)" :label="f" @update:model-value="toggle(g.writeFields, f)" /></div>
        </template>
      </div>
    </UiModal>
  </div>
</template>

<style scoped>
.who { display: flex; align-items: center; gap: 10px; min-width: 0; }
.avatar { width: 32px; height: 32px; border-radius: 50%; background: var(--brand-soft); color: var(--brand-primary); display: grid; place-items: center; font-size: 12px; font-weight: 600; flex: none; }
.you { margin-left: 6px; font-size: 11px; padding: 1px 6px; border-radius: 999px; background: var(--bg-subtle); color: var(--text-secondary); }
.desc { padding: 0 24px 12px; }
.matrix-wrap { overflow-x: auto; }
.matrix { width: 100%; border-collapse: collapse; font-size: 13px; }
.matrix th, .matrix td { padding: 10px 8px; text-align: center; border-top: 1px solid var(--border-default); }
.matrix thead th { background: var(--bg-subtle); color: var(--text-secondary); font-size: 12px; font-weight: 500; border-top: 0; }
.matrix th[scope='row'], .matrix thead th:first-child { text-align: left; padding-left: 24px; font-weight: 400; white-space: nowrap; }
.short { display: none; }
.box { width: 18px; height: 18px; border-radius: var(--radius-sm); border: 1px solid var(--border-strong); display: inline-grid; place-items: center; vertical-align: middle; }
.box.on { background: var(--brand-primary); border-color: var(--brand-primary); color: #fff; }
.rules { padding: 16px 24px; border-top: 1px solid var(--border-default); display: grid; gap: 6px; }
.rules .kv { align-items: center; }
.pw { display: grid; gap: 6px; }
.add-grant { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; align-items: end; }
.grant { border: 1px solid var(--border-default); border-radius: var(--radius-md); padding: 12px 14px; display: grid; gap: 10px; }
.grant-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin: -4px -6px -4px 0; }
.acts { display: flex; flex-wrap: wrap; gap: 8px 18px; }
.acts.sub { padding-left: 26px; }
@media (max-width: 767px) {
  .matrix th[scope='row'], .matrix thead th:first-child { padding-left: 16px; }
  .rules { padding: 14px 16px; } .desc { padding: 0 16px 12px; }
  .matrix th, .matrix td { padding: 10px 4px; }
  .rules .kv { flex-direction: column; align-items: flex-start; gap: 4px; }
}
@media (max-width: 479px) { .full { display: none; } .short { display: inline; } }
</style>
