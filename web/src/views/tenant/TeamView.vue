<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api } from '@/api/client'
import { can, isAdmin, me } from '@/api/me'
import { opt, useBusy, useLoader } from '@/composables'
import { dateTime, when } from '@/format'
import DataTable from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiTabs from '@/components/UiTabs.vue'

const users = ref<any[]>([])
const roles = ref<any[]>([])
const { loading, error, reload } = useLoader(async () => {
  const [u, r] = await Promise.all([api.get('/users'), can('roles') ? api.get('/roles') : Promise.resolve({ items: [] })])
  users.value = u.items; roles.value = r.items
})
const roleName = (id: string) => roles.value.find((r) => r.id === id)?.name ?? 'Role'
const manage = () => can('users', 'manage')
const { busy, run } = useBusy()

const add = reactive({ open: false, how: 'invite', email: '', name: '', password: '', admin: false, roles: [] as string[] })
function toggleRole(list: string[], id: string) { const i = list.indexOf(id); if (i >= 0) list.splice(i, 1); else list.push(id) }
async function saveAdd() {
  const body = { email: add.email.trim(), full_name: opt(add.name), is_tenant_admin: add.admin, role_ids: add.roles }
  const ok = await run('add', () => (add.how === 'invite' ? api.post('/users/invite', body) : api.post('/users', { ...body, password: add.password })),
    add.how === 'invite' ? `Invitation sent to ${add.email.trim()}.` : 'Team member added.')
  if (ok) { add.open = false; reload() }
}

const ed = reactive({ open: false, u: null as any, name: '', active: true, admin: false, roles: [] as string[], password: '', tab: 'profile' })
function openUser(u: any) { Object.assign(ed, { open: true, u, name: u.full_name ?? '', active: u.is_active, admin: u.is_tenant_admin, roles: [...u.role_ids], password: '', tab: 'profile' }) }
const self = () => ed.u?.id === me.tenant?.user_id
async function saveUser() {
  const ok = await run('save', async () => {
    if (ed.tab === 'password') { await api.post(`/users/${ed.u.id}/reset-password`, { password: ed.password }); return }
    await api.patch(`/users/${ed.u.id}`, { full_name: ed.name, is_active: self() ? undefined : ed.active, is_tenant_admin: self() ? undefined : ed.admin })
    if (can('roles')) await api.put(`/users/${ed.u.id}/roles`, { role_ids: ed.roles })
  }, ed.tab === 'password' ? 'Password set. Their other sessions were signed out.' : 'Saved.')
  if (ok) { ed.open = false; reload() }
}
async function unlock() { await run('unlock', () => api.patch(`/users/${ed.u.id}`, { unlock: true }), 'Unlocked.'); ed.open = false; reload() }
async function resend() { await run('resend', () => api.post(`/users/${ed.u.id}/resend-invite`), 'Invitation sent again.') }
const locked = (u: any) => u.locked_until && new Date(u.locked_until) > new Date()
</script>

<template>
  <div class="page">
    <PageHeader title="Team" caption="People who can sign in to this workspace.">
      <UiButton v-if="manage()" icon="plus" variant="primary" @click="Object.assign(add, { open: true, how: 'invite', email: '', name: '', password: '', admin: false, roles: [] })">Add person</UiButton>
    </PageHeader>
    <UiCard>
      <LoadState :loading="loading" :error="error" :ready="users.length > 0" @retry="reload">
        <DataTable :columns="[{ key: 'email', label: 'Person', primary: true }, { key: 'roles', label: 'Access' }, { key: 'last', label: 'Last sign-in', hideOnMobile: true }]"
                   :rows="users" row-key="id" :clickable="manage()" @select="openUser">
          <template #cell-email="{ row }"><strong>{{ row.full_name || row.email }}</strong><span class="caption">{{ row.email }}</span></template>
          <template #cell-roles="{ row }">
            <div class="chips">
              <UiBadge v-if="row.is_tenant_admin" tone="brand">Admin</UiBadge>
              <span v-for="r in row.role_ids" :key="r" class="chip">{{ roleName(r) }}</span>
              <UiBadge v-if="row.invitation_pending" tone="warning">Invited</UiBadge>
              <UiBadge v-if="!row.is_active">Disabled</UiBadge>
              <UiBadge v-if="locked(row)" tone="danger">Locked</UiBadge>
              <span v-if="!row.is_tenant_admin && !row.role_ids.length && row.is_active" class="caption">No access yet</span>
            </div>
          </template>
          <template #cell-last="{ row }">{{ when(row.last_login_at) }}</template>
        </DataTable>
      </LoadState>
    </UiCard>

    <UiModal :open="add.open" title="Add a person" submit-label="Add" :busy="busy === 'add'" :submit-disabled="!add.email.trim() || (add.how === 'password' && add.password.length < 12)" @close="add.open = false" @submit="saveAdd">
      <UiTabs v-model="add.how" :tabs="[{ key: 'invite', label: 'Send an invitation' }, { key: 'password', label: 'Set a password now' }]" />
      <div class="grid2"><UiInput v-model="add.email" label="Email" type="email" required /><UiInput v-model="add.name" label="Name" /></div>
      <UiInput v-if="add.how === 'password'" v-model="add.password" label="Password" type="password" autocomplete="new-password" hint="At least 12 characters. Share it with them safely; they can change it later." />
      <p v-else class="caption">They get an email with a link to choose their own password. Email must be set up by the platform admin.</p>
      <UiCheck v-if="isAdmin()" v-model="add.admin" label="Workspace admin" hint="Can do everything, including managing the team and settings." />
      <div v-if="!add.admin && roles.length">
        <p class="label">Roles</p>
        <div class="chips roles"><button v-for="r in roles" :key="r.id" type="button" class="chip pick" :class="{ on: add.roles.includes(r.id) }" @click="toggleRole(add.roles, r.id)">{{ r.name }}</button></div>
      </div>
    </UiModal>

    <UiModal :open="ed.open" :title="ed.u?.full_name || ed.u?.email || ''" :caption="ed.u?.email" submit-label="Save" :busy="busy === 'save'" :submit-disabled="ed.tab === 'password' && ed.password.length < 12" @close="ed.open = false" @submit="saveUser">
      <template v-if="ed.u">
        <UiTabs v-model="ed.tab" :tabs="[{ key: 'profile', label: 'Access' }, ...(isAdmin() && !self() ? [{ key: 'password', label: 'Set password' }] : [])]" />
        <template v-if="ed.tab === 'profile'">
          <UiInput v-model="ed.name" label="Name" />
          <UiCheck v-model="ed.active" label="Can sign in" :disabled="self()" :hint="self() ? 'You cannot disable yourself.' : undefined" />
          <UiCheck v-if="isAdmin()" v-model="ed.admin" label="Workspace admin" :disabled="self()" />
          <div v-if="!ed.admin && can('roles')">
            <p class="label">Roles</p>
            <div class="chips roles"><button v-for="r in roles" :key="r.id" type="button" class="chip pick" :class="{ on: ed.roles.includes(r.id) }" @click="toggleRole(ed.roles, r.id)">{{ r.name }}</button></div>
          </div>
          <p class="caption">Joined {{ dateTime(ed.u.created_at) }} · last sign-in {{ dateTime(ed.u.last_login_at) }}</p>
          <div class="acts">
            <UiButton v-if="locked(ed.u)" icon="lock" :loading="busy === 'unlock'" @click="unlock">Unlock (too many wrong passwords)</UiButton>
            <UiButton v-if="ed.u.invitation_pending" icon="mail" :loading="busy === 'resend'" @click="resend">Resend invitation</UiButton>
          </div>
        </template>
        <template v-else>
          <UiInput v-model="ed.password" label="New password" type="password" autocomplete="new-password" hint="At least 12 characters. Signs them out everywhere." />
        </template>
      </template>
    </UiModal>
  </div>
</template>

<style scoped>
.roles { margin-top: 6px; }
.pick { border: 1px solid var(--border-strong); background: var(--bg-surface); cursor: pointer; padding: 4px 12px; }
.pick.on { background: var(--brand-soft); border-color: var(--brand-primary); color: var(--brand-primary); }
.acts { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
