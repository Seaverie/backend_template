<script setup lang="ts">
import { reactive } from 'vue'
import { api } from '@/api/client'
import { useBusy, useLoader } from '@/composables'
import { toast } from '@/ui'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiSelect from '@/components/UiSelect.vue'

const s = reactive({ loaded: false, driver: 'local', local_dir: '', secret_set: false, secret: '', endpoint: '', region: 'auto', bucket: '', access_key_id: '', prefix: '', path_style: true })
const { loading, error, reload } = useLoader(async () => {
  const r = await api.get('/platform/storage')
  Object.assign(s, { loaded: true, driver: r.driver, local_dir: r.local_dir, secret_set: r.s3_secret_set, secret: '', ...r.s3 })
})
const { busy, run } = useBusy()
async function save() {
  await run('save', async () => {
    await api.put('/platform/storage', { driver: s.driver, s3: { endpoint: s.endpoint.trim(), region: s.region.trim() || 'auto', bucket: s.bucket.trim(), access_key_id: s.access_key_id.trim(), prefix: s.prefix.trim(), path_style: s.path_style },
      s3_secret_access_key: s.secret ? s.secret : undefined })
    await reload()
  }, 'Storage saved. New uploads go to the chosen store; existing files stay where they are.')
}
async function test() {
  await run('test', async () => {
    const r = await api.post('/platform/storage/test')
    if (r.ok) toast(`The ${r.driver} store accepted a test file.`)
    else toast(`Test failed: ${r.error ?? 'unknown error'}`, 'err')
  })
}
</script>

<template>
  <div class="page narrow">
    <PageHeader title="File storage" caption="Where uploads are kept." />
    <LoadState :loading="loading" :error="error" :ready="s.loaded" @retry="reload">
      <UiCard title="Store for new uploads">
        <template #actions><UiBadge tone="brand">{{ s.driver === 's3' ? 'S3-compatible' : 'This server\'s disk' }}</UiBadge></template>
        <form class="card-body" @submit.prevent="save">
          <UiSelect v-model="s.driver" label="Store" :options="[{ value: 'local', label: 'This server\'s disk' }, { value: 's3', label: 'S3-compatible (Cloudflare R2, AWS S3, MinIO…)' }]" />
          <p v-if="s.driver === 'local'" class="caption">Files are kept in <span class="mono">{{ s.local_dir }}</span> (the “storage” volume). Include it in backups.</p>
          <template v-else>
            <UiInput v-model="s.endpoint" label="Endpoint" type="url" mono placeholder="https://<account>.r2.cloudflarestorage.com" />
            <div class="grid3"><UiInput v-model="s.bucket" label="Bucket" mono /><UiInput v-model="s.region" label="Region" mono /><UiInput v-model="s.prefix" label="Folder prefix" mono placeholder="optional/" /></div>
            <div class="grid2">
              <UiInput v-model="s.access_key_id" label="Access key ID" mono autocomplete="off" />
              <UiInput v-model="s.secret" label="Secret access key" type="password" mono autocomplete="off" :placeholder="s.secret_set ? 'Saved – leave empty to keep' : ''" />
            </div>
            <UiCheck v-model="s.path_style" label="Path-style addresses" hint="Needed for MinIO and most self-hosted servers." />
          </template>
          <div class="acts"><UiButton type="submit" variant="primary" :loading="busy === 'save'">Save</UiButton><UiButton :loading="busy === 'test'" @click="test">Test the saved store</UiButton></div>
        </form>
      </UiCard>
    </LoadState>
  </div>
</template>

<style scoped>.narrow { max-width: 860px; } .acts { display: flex; gap: 8px; flex-wrap: wrap; }</style>
