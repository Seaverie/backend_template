<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '@/api/client'
import type { Action, Role, User } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import DataTable, { type Column } from '@/components/DataTable.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import { when } from '@/format'

const users = ref<User[]>([])
const roles = ref<Role[]>([])
onMounted(async () => { const [u, r] = await Promise.all([api.users(), api.roles()]); users.value = u.items; roles.value = r.items })

const role = computed(() => roles.value[0])
const actions: Action[] = ['read', 'create', 'update', 'delete', 'manage']
// Admin resources accept only read and manage (see app/models/rbac.py).
const adminOnly = new Set(['users', 'roles', 'api_keys', 'audit_logs', 'webhooks', 'notifications'])
const resources = computed(() => [...new Set(role.value?.permissions.map((p) => p.resource) ?? []), 'users'])
const has = (res: string, a: Action) => role.value?.permissions.some((p) => p.resource === res && p.action === a) ?? false
const na = (res: string, a: Action) => adminOnly.has(res) && a !== 'read' && a !== 'manage'
const rules = computed(() => (role.value?.permissions ?? []).flatMap((p) => [
  ...(p.row_scope === 'own' && p.action === 'read' ? [{ k: `${p.resource} · row scope`, v: 'Own rows only', tone: 'info' as const }] : []),
  ...(p.readable_fields ? [{ k: `${p.resource} · readable fields`, v: p.readable_fields.join(', '), tone: 'warning' as const }] : []),
  ...(p.writable_fields ? [{ k: `${p.resource} · writable fields`, v: p.writable_fields.join(', '), tone: 'warning' as const }] : []),
]))
const initials = (u: User) => (u.full_name ?? u.email).split(' ').map((s) => s[0]).join('').slice(0, 2).toUpperCase()
const columns: Column[] = [
  { key: 'user', label: 'User', primary: true }, { key: 'roles', label: 'Roles' }, { key: 'status', label: 'Status' }, { key: 'last', label: 'Last login', hideOnMobile: true },
]
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Users &amp; roles</h1><p class="muted">GET /users · GET /roles · you can only grant permissions you already hold</p></div>
      <div class="actions"><UiButton icon="plus">New role</UiButton><UiButton variant="primary" icon="plus">Add user</UiButton></div>
    </div>
    <div class="split">
      <UiCard title="Users" :caption="`${users.length} users · POST /users · PUT /users/{id}/roles`">
        <DataTable :columns="columns" :rows="users" row-key="id">
          <template #cell-user="{ row }">
            <div class="who"><span class="avatar">{{ initials(row) }}</span><div><strong>{{ row.full_name }}</strong><div class="caption">{{ row.email }}</div></div></div>
          </template>
          <template #cell-roles="{ row }">{{ row.is_tenant_admin ? 'Tenant admin' : (row.roles ?? []).join(', ') }}</template>
          <template #cell-status="{ row }">
            <UiBadge :tone="!row.is_active ? 'neutral' : row.locked ? 'warning' : 'success'">{{ !row.is_active ? 'Disabled' : row.locked ? 'Locked' : 'Active' }}</UiBadge>
          </template>
          <template #cell-last="{ row }">{{ when(row.last_login_at) }}</template>
        </DataTable>
      </UiCard>

      <UiCard v-if="role" :title="`Role: ${role.name}`" :caption="role.description ?? undefined">
        <template #actions><UiBadge tone="brand">{{ role.user_count }} users</UiBadge></template>
        <div class="matrix-wrap">
          <table class="matrix">
            <thead><tr><th scope="col">Resource</th><th v-for="a in actions" :key="a" scope="col" :title="a"><span class="full">{{ a[0].toUpperCase() + a.slice(1) }}</span><span class="short" aria-hidden="true">{{ a[0].toUpperCase() }}</span></th></tr></thead>
            <tbody>
              <tr v-for="r in resources" :key="r">
                <th scope="row">{{ r }}</th>
                <td v-for="a in actions" :key="a">
                  <span v-if="na(r, a)" class="caption">n/a</span>
                  <span v-else class="box" :class="{ on: has(r, a) }" role="img" :aria-label="has(r, a) ? 'allowed' : 'not allowed'"><AppIcon v-if="has(r, a)" name="check" :size="12" /></span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="rules">
          <p class="label">Row &amp; field security</p>
          <div v-for="x in rules" :key="x.k" class="kv"><span>{{ x.k }}</span><UiBadge :tone="x.tone">{{ x.v }}</UiBadge></div>
        </div>
        <div class="foot"><UiButton>Cancel</UiButton><UiButton variant="primary">Save role</UiButton></div>
      </UiCard>
    </div>
  </div>
</template>

<style scoped>
.who { display: flex; align-items: center; gap: 10px; min-width: 0; }
.avatar { width: 32px; height: 32px; border-radius: 50%; background: var(--brand-soft); color: var(--brand-primary); display: grid; place-items: center; font-size: 12px; font-weight: 600; flex: none; }
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
.foot { display: flex; gap: 8px; padding: 16px 24px; border-top: 1px solid var(--border-default); }
.foot > * { flex: 1; }
@media (max-width: 767px) {
  .matrix th[scope='row'], .matrix thead th:first-child { padding-left: 16px; }
  .rules, .foot { padding: 14px 16px; }
  .matrix th, .matrix td { padding: 10px 4px; }
  .rules .kv { flex-direction: column; align-items: flex-start; gap: 4px; }
}
@media (max-width: 479px) { .full { display: none; } .short { display: inline; } }
</style>
