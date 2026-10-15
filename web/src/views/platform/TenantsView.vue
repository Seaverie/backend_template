<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { num, opt, useBusy, useList, useLoader } from '@/composables'
import { when } from '@/format'
import DataTable from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiPager from '@/components/UiPager.vue'

const router = useRouter()
const list = useList((q) => api.get('/platform/tenants', q), { q: '', status: '' })
const catalog = ref<any[]>([])
useLoader(async () => { catalog.value = (await api.get('/platform/modules')).items })
const { busy, run } = useBusy()
const DEFAULTS = ['catalog', 'orders', 'files', 'email']
const f = reactive({ open: false, name: '', slug: '', email: '', owner: '', password: '', rate: '', modules: [...DEFAULTS] as string[] })
const slugFrom = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 63)
const slugTouched = ref(false)
function onName(v: string) { f.name = v; if (!slugTouched.value) f.slug = slugFrom(v) }
function toggle(code: string) {
  const on = f.modules.includes(code)
  if (on) {
    // switching off also switches off modules that need this one
    const drop = new Set([code]); let grew = true
    while (grew) { grew = false; for (const m of catalog.value) if (!drop.has(m.code) && m.requires.some((r: string) => drop.has(r))) { drop.add(m.code); grew = true } }
    f.modules = f.modules.filter((c) => !drop.has(c))
  } else {
    const add = new Set([code]); let grew = true
    while (grew) { grew = false; for (const c of [...add]) for (const r of catalog.value.find((m) => m.code === c)?.requires ?? []) if (!add.has(r)) { add.add(r); grew = true } }
    f.modules = [...new Set([...f.modules, ...add])]
  }
}
async function create() {
  let id = ''
  const ok = await run('create', async () => {
    const t = await api.post('/platform/tenants', { slug: f.slug, name: f.name.trim(), owner_email: f.email.trim(), owner_full_name: opt(f.owner), owner_password: f.password, rate_limit_per_min: num(f.rate), modules: f.modules })
    id = t.tenant.id
  }, 'Workspace created. Send the owner their workspace name, email and password.')
  if (ok) router.push(`/platform/tenants/${id}`)
}
function genPassword() {
  const a = new Uint32Array(4); crypto.getRandomValues(a)
  const words = ['river', 'mango', 'cloud', 'tiger', 'lotus', 'amber', 'coral', 'maple', 'storm', 'pearl', 'cedar', 'delta', 'ember', 'frost', 'grove', 'harbor']
  f.password = `${words[a[0] % 16]}-${words[a[1] % 16]}-${words[a[2] % 16]}-${(a[3] % 900) + 100}`
}
</script>

<template>
  <div class="page">
    <PageHeader title="Workspaces" caption="Each business using this platform, with its own data, team and modules.">
      <UiButton icon="plus" variant="primary" @click="Object.assign(f, { open: true, name: '', slug: '', email: '', owner: '', password: '', rate: '', modules: [...DEFAULTS] }); slugTouched = false">New workspace</UiButton>
    </PageHeader>
    <UiCard>
      <div class="toolbar" style="padding-top: 16px">
        <input v-model="list.filters.q" class="search-input" type="search" placeholder="Search name or short name…" aria-label="Search workspaces" />
        <select v-model="list.filters.status" class="search-input narrow" aria-label="Status"><option value="">Any status</option><option value="active">Active</option><option value="suspended">Suspended</option><option value="offboarding">Offboarding</option></select>
      </div>
      <LoadState :loading="list.loading.value" :error="list.error.value" :ready="list.items.value.length > 0" @retry="list.reload">
        <DataTable :columns="[{ key: 'name', label: 'Workspace', primary: true }, { key: 'status', label: 'Status' }, { key: 'created', label: 'Created', hideOnMobile: true }]"
                   :rows="list.items.value" row-key="id" clickable empty="No workspaces yet." @select="(t) => router.push(`/platform/tenants/${t.id}`)">
          <template #cell-name="{ row }"><strong>{{ row.name }}</strong><span class="caption mono">{{ row.slug }}</span></template>
          <template #cell-status="{ row }"><StatusBadge :status="row.status" /></template>
          <template #cell-created="{ row }">{{ when(row.created_at) }}</template>
        </DataTable>
        <UiPager v-model="list.offset.value" :total="list.total.value" :limit="list.limit" :count="list.items.value.length" />
      </LoadState>
    </UiCard>

    <UiModal :open="f.open" title="New workspace" wide submit-label="Create workspace" :busy="busy === 'create'" :submit-disabled="!f.name.trim() || f.slug.length < 2 || !f.email.includes('@') || f.password.length < 12" @close="f.open = false" @submit="create">
      <div class="grid2">
        <UiInput :model-value="f.name" label="Business name" required @update:model-value="onName" />
        <UiInput v-model="f.slug" label="Workspace short name" mono hint="Typed at sign-in and used in shop links. Cannot change later." @input="slugTouched = true" />
      </div>
      <div class="grid2"><UiInput v-model="f.email" label="Owner email" type="email" required /><UiInput v-model="f.owner" label="Owner name" /></div>
      <div class="pwrow"><UiInput v-model="f.password" label="Owner's first password" autocomplete="off" mono hint="At least 12 characters. They can change it after signing in." /><UiButton @click="genPassword">Suggest</UiButton></div>
      <UiInput v-model="f.rate" label="Requests per minute" inputmode="numeric" placeholder="Platform default" />
      <div>
        <p class="label">Modules</p>
        <div class="mods">
          <label v-for="m in catalog" :key="m.code" class="mod" :class="{ on: f.modules.includes(m.code) }">
            <input type="checkbox" :checked="f.modules.includes(m.code)" @change="toggle(m.code)" />
            <span><strong>{{ m.label }}</strong><span class="caption">{{ m.description }}</span></span>
          </label>
        </div>
      </div>
    </UiModal>
  </div>
</template>

<style scoped>
.narrow { flex: 0 1 180px !important; }
.pwrow { display: flex; gap: 8px; align-items: flex-start; }
.pwrow > :first-child { flex: 1; }
.pwrow > :last-child { margin-top: 22px; }
.mods { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 8px; margin-top: 6px; }
.mod { display: flex; gap: 10px; align-items: flex-start; padding: 10px; border: 1px solid var(--border-default); border-radius: var(--radius-md); cursor: pointer; }
.mod.on { border-color: var(--brand-primary); background: var(--brand-soft); }
.mod > span { display: flex; flex-direction: column; }
.mod input { margin-top: 3px; accent-color: var(--brand-primary); }
</style>
