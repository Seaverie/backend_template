<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api } from '@/api/client'
import { me } from '@/api/me'
import { opt, useBusy, useLoader } from '@/composables'
import { when } from '@/format'
import { ask } from '@/ui'
import DataTable from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'

const items = ref<any[]>([])
const { loading, error, reload } = useLoader(async () => { items.value = (await api.get('/platform/admins')).items })
const { busy, run } = useBusy()
const f = reactive({ open: false, email: '', name: '', password: '' })
async function create() {
  const ok = await run('add', async () => { await api.post('/platform/admins', { email: f.email.trim(), full_name: opt(f.name), password: f.password }); await reload() }, 'Platform admin added. They set up two-factor on first sign-in.')
  if (ok) f.open = false
}
async function setActive(a: any) {
  if (a.is_active && !await ask({ title: `Deactivate ${a.email}?`, body: 'They are signed out and cannot sign in.', confirmLabel: 'Deactivate', danger: true })) return
  await run('a:' + a.id, async () => { await api.patch(`/platform/admins/${a.id}`, { is_active: !a.is_active }); await reload() }, a.is_active ? 'Deactivated.' : 'Activated.')
}
async function resetMfa(a: any) {
  if (!await ask({ title: `Reset two-factor for ${a.email}?`, body: 'Use this when they lost their phone. They set it up again at next sign-in.', confirmLabel: 'Reset' })) return
  await run('m:' + a.id, async () => { await api.patch(`/platform/admins/${a.id}`, { reset_mfa: true }); await reload() }, 'Two-factor reset.')
}
</script>

<template>
  <div class="page">
    <PageHeader title="Platform admins" caption="People who run the platform and can open every workspace's settings.">
      <UiButton icon="plus" variant="primary" @click="Object.assign(f, { open: true, email: '', name: '', password: '' })">Add admin</UiButton>
    </PageHeader>
    <UiCard>
      <LoadState :loading="loading" :error="error" :ready="items.length > 0" @retry="reload">
        <DataTable :columns="[{ key: 'email', label: 'Admin', primary: true }, { key: 'state', label: 'State' }, { key: 'last', label: 'Last sign-in', hideOnMobile: true }, { key: 'acts', label: '' }]" :rows="items" row-key="id">
          <template #cell-email="{ row }"><strong>{{ row.full_name || row.email }}</strong><span class="caption">{{ row.email }}<template v-if="row.id === me.platform?.id"> · you</template></span></template>
          <template #cell-state="{ row }"><div class="chips"><UiBadge :tone="row.is_active ? 'success' : 'neutral'">{{ row.is_active ? 'Active' : 'Inactive' }}</UiBadge><UiBadge :tone="row.mfa_enabled ? 'info' : 'warning'">{{ row.mfa_enabled ? '2FA on' : 'No 2FA' }}</UiBadge></div></template>
          <template #cell-last="{ row }">{{ when(row.last_login_at) }}</template>
          <template #cell-acts="{ row }">
            <div v-if="row.id !== me.platform?.id" class="row-actions">
              <UiButton v-if="row.mfa_enabled" variant="ghost" :loading="busy === 'm:' + row.id" @click="resetMfa(row)">Reset 2FA</UiButton>
              <UiButton variant="ghost" :loading="busy === 'a:' + row.id" @click="setActive(row)">{{ row.is_active ? 'Deactivate' : 'Activate' }}</UiButton>
            </div>
          </template>
        </DataTable>
      </LoadState>
    </UiCard>
    <UiModal :open="f.open" title="Add platform admin" submit-label="Add" :busy="busy === 'add'" :submit-disabled="!f.email.includes('@') || f.password.length < 12" @close="f.open = false" @submit="create">
      <div class="grid2"><UiInput v-model="f.email" label="Email" type="email" /><UiInput v-model="f.name" label="Name" /></div>
      <UiInput v-model="f.password" label="First password" type="password" autocomplete="new-password" hint="At least 12 characters. Share it safely." />
    </UiModal>
  </div>
</template>
