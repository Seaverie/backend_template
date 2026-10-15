<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { can, hasModule } from '@/api/me'
import { opt, useBusy, useLoader } from '@/composables'
import EmptyState from '@/components/EmptyState.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'

const router = useRouter()
const tables = ref<any[]>([])
const { loading, error, reload } = useLoader(async () => { tables.value = (await api.get('/data/tables')).items })
const { busy, run } = useBusy()
const form = reactive({ open: false, name: '', key: '', description: '' })
const keyFrom = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').replace(/^(\d)/, 't_$1').slice(0, 63)
const keyTouched = ref(false)
function onName(v: string) { form.name = v; if (!keyTouched.value) form.key = keyFrom(v) }
async function create() {
  let key = ''
  const ok = await run('create', async () => {
    const t = await api.post('/data/tables', { key: form.key, name: form.name.trim(), description: opt(form.description), fields: [{ key: 'name', label: 'Name', type: 'text', required: true }], display_field: 'name' })
    key = t.key
  }, 'Table created with a Name field. Add more fields next.')
  if (ok) router.push({ path: `/data/${key}`, query: { tab: 'fields' } })
}
</script>

<template>
  <div class="page">
    <PageHeader title="Tables" caption="Your own lists — leads, bookings, assets, anything — with the fields you choose.">
      <RouterLink v-if="hasModule('imports') && can('imports')" to="/imports"><UiButton icon="import">Imports</UiButton></RouterLink>
      <UiButton v-if="can('data_schema', 'manage')" icon="plus" variant="primary" @click="Object.assign(form, { open: true, name: '', key: '', description: '' }); keyTouched = false">New table</UiButton>
    </PageHeader>
    <LoadState :loading="loading" :error="error" :ready="tables.length > 0" @retry="reload">
      <UiCard v-if="!tables.length"><EmptyState icon="database" title="No tables yet" :body="can('data_schema', 'manage') ? 'Create one to start keeping records.' : 'A workspace admin can create tables for you.'" /></UiCard>
      <div class="grid">
        <RouterLink v-for="t in tables" :key="t.key" :to="`/data/${t.key}`" class="tile">
          <strong>{{ t.name }}</strong>
          <span class="caption">{{ t.row_count }} row{{ t.row_count === 1 ? '' : 's' }} · {{ t.fields.length }} field{{ t.fields.length === 1 ? '' : 's' }}</span>
          <p v-if="t.description" class="muted">{{ t.description }}</p>
          <span class="caption mono">{{ t.key }}</span>
        </RouterLink>
      </div>
    </LoadState>
    <UiModal :open="form.open" title="New table" submit-label="Create" :busy="busy === 'create'" :submit-disabled="!form.name.trim() || !form.key" @close="form.open = false" @submit="create">
      <UiInput :model-value="form.name" label="Name" placeholder="Leads" required @update:model-value="onName" />
      <UiInput v-model="form.key" label="Key" mono hint="Used in the API and webhooks (data.<key>.created). Cannot be changed later." @input="keyTouched = true" />
      <UiInput v-model="form.description" label="Description" multiline :rows="2" />
    </UiModal>
  </div>
</template>

<style scoped>
.grid { display: grid; gap: 16px; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); }
.tile { display: flex; flex-direction: column; gap: 6px; padding: 18px; background: var(--bg-surface); border: 1px solid var(--border-default); border-radius: var(--radius-lg); color: var(--text-primary); }
.tile:hover { text-decoration: none; border-color: var(--brand-primary); }
</style>
