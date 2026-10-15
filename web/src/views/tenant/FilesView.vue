<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { api } from '@/api/client'
import { can } from '@/api/me'
import { useBusy, useList, useLoader } from '@/composables'
import { bytes, dateTime, title, when } from '@/format'
import { ask, copy } from '@/ui'
import EmptyState from '@/components/EmptyState.vue'
import FileButton from '@/components/FileButton.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiPager from '@/components/UiPager.vue'

const usage = ref<any>(null)
const usageLoad = useLoader(async () => { usage.value = await api.get('/files/usage') })
const list = useList((q) => api.get('/files', q), { q: '', purpose: '' }, 48)
const { busy, run } = useBusy()
const usedPct = computed(() => (usage.value ? Math.min(100, Math.round((usage.value.used_bytes / Math.max(1, usage.value.quota_bytes)) * 100)) : 0))

// Private thumbnails need the sign-in token, so they are fetched as blobs.
const thumbs = reactive<Record<string, string>>({})
watch(() => list.items.value, async (items) => {
  for (const f of items as any[]) {
    if (thumbs[f.id] || !f.has_thumbnail) continue
    if (f.thumbnail_url) thumbs[f.id] = f.thumbnail_url
    else api.blobUrl(`/files/${f.id}/thumbnail`).then((u) => { thumbs[f.id] = u }).catch(() => {})
  }
})
onBeforeUnmount(() => Object.values(thumbs).forEach((u) => u.startsWith('blob:') && URL.revokeObjectURL(u)))

async function upload(files: File[]) {
  await run('upload', async () => {
    for (const file of files) { const fd = new FormData(); fd.append('file', file); await api.upload('/files', fd) }
    await Promise.all([list.reload(), usageLoad.reload()])
  }, files.length > 1 ? `${files.length} files uploaded.` : 'File uploaded.')
}

const sel = reactive({ open: false, f: null as any, name: '', pub: false, link: '' })
function open(f: any) { Object.assign(sel, { open: true, f, name: f.filename, pub: f.is_public, link: '' }) }
async function save() {
  const ok = await run('save', async () => { sel.f = await api.patch(`/files/${sel.f.id}`, { filename: sel.name.trim(), is_public: sel.pub }); await list.reload() }, 'Saved.')
  if (ok) sel.open = false
}
async function link() {
  await run('link', async () => { const r = await api.post(`/files/${sel.f.id}/link?ttl_seconds=86400`); sel.link = location.origin + r.url })
}
async function remove() {
  if (!await ask({ title: `Delete ${sel.f.filename}?`, body: 'Anything that uses this file (a logo, a product image) loses it.', confirmLabel: 'Delete', danger: true })) return
  const ok = await run('del', async () => { await api.del(`/files/${sel.f.id}`); await Promise.all([list.reload(), usageLoad.reload()]) }, 'File deleted.')
  if (ok) sel.open = false
}
const origin = location.origin
const ext = (n: string) => (n.includes('.') ? n.split('.').pop()!.toUpperCase().slice(0, 4) : 'FILE')
</script>

<template>
  <div class="page">
    <PageHeader title="Files" caption="Images and documents uploaded to this workspace.">
      <FileButton v-if="can('files', 'create')" multiple variant="primary" :loading="busy === 'upload'" @pick="upload">Upload</FileButton>
    </PageHeader>
    <UiCard v-if="usage" :title="`${bytes(usage.used_bytes)} of ${bytes(usage.quota_bytes)} used`" :caption="`${usage.file_count} files · up to ${bytes(usage.max_file_bytes)} each`">
      <div class="card-body"><div class="meter" role="meter" :aria-valuenow="usedPct" aria-valuemin="0" aria-valuemax="100" aria-label="Storage used"><span :class="{ warn: usedPct > 85 }" :style="{ width: usedPct + '%' }" /></div></div>
    </UiCard>
    <UiCard>
      <div class="toolbar" style="padding-top: 16px">
        <input v-model="list.filters.q" class="search-input" type="search" placeholder="Search file names…" aria-label="Search files" />
        <select v-model="list.filters.purpose" class="search-input narrow" aria-label="Kind">
          <option value="">All kinds</option><option v-for="p in ['attachment', 'product_image', 'logo', 'data', 'delivery_proof']" :key="p" :value="p">{{ title(p) }}</option>
        </select>
      </div>
      <LoadState :loading="list.loading.value" :error="list.error.value" :ready="list.items.value.length > 0" @retry="list.reload">
        <EmptyState v-if="!list.items.value.length" icon="image" title="No files" body="Uploads from the dashboard, the shop and the API appear here." />
        <div class="grid">
          <button v-for="f in list.items.value" :key="f.id" type="button" class="file" @click="open(f)">
            <span class="pic"><img v-if="thumbs[f.id]" :src="thumbs[f.id]" alt="" loading="lazy" /><span v-else class="ext">{{ ext(f.filename) }}</span></span>
            <span class="name">{{ f.filename }}</span>
            <span class="caption">{{ bytes(f.size_bytes) }} · {{ when(f.created_at) }}</span>
            <UiBadge v-if="f.is_public" tone="info" class="pub">Public</UiBadge>
          </button>
        </div>
        <UiPager v-model="list.offset.value" :total="list.total.value" :limit="list.limit" :count="list.items.value.length" />
      </LoadState>
    </UiCard>

    <UiModal :open="sel.open" :title="sel.f?.filename ?? ''" :submit-label="can('files', 'update') ? 'Save' : undefined" :busy="busy === 'save'" @close="sel.open = false" @submit="save">
      <template v-if="sel.f">
        <img v-if="thumbs[sel.f.id]" :src="thumbs[sel.f.id]" alt="" class="preview" />
        <div class="kv"><span>Type</span><span>{{ sel.f.content_type }}</span></div>
        <div class="kv"><span>Size</span><span>{{ bytes(sel.f.size_bytes) }}<template v-if="sel.f.width"> · {{ sel.f.width }}×{{ sel.f.height }}</template></span></div>
        <div class="kv"><span>Kind</span><span>{{ title(sel.f.purpose) }}</span></div>
        <div class="kv"><span>Uploaded</span><span>{{ dateTime(sel.f.created_at) }}</span></div>
        <div class="kv"><span>SHA-256</span><span class="mono hash">{{ sel.f.sha256 }}</span></div>
        <UiInput v-model="sel.name" label="File name" :disabled="!can('files', 'update')" />
        <UiCheck v-model="sel.pub" label="Public" hint="Anyone with the link can open it (for logos and product images)." :disabled="!can('files', 'update')" />
        <div v-if="sel.f.is_public && sel.f.url" class="secret-out"><code>{{ origin + sel.f.url }}</code><UiButton variant="ghost" icon="copy" aria-label="Copy" @click="copy(origin + sel.f.url)" /></div>
        <div class="acts">
          <UiButton icon="download" @click="api.download(`/files/${sel.f.id}/content`, sel.f.filename)">Download</UiButton>
          <UiButton v-if="!sel.f.is_public" icon="link" :loading="busy === 'link'" @click="link">Share link (24 h)</UiButton>
        </div>
        <div v-if="sel.link" class="secret-out"><code>{{ sel.link }}</code><UiButton variant="ghost" icon="copy" aria-label="Copy" @click="copy(sel.link)" /></div>
      </template>
      <template v-if="sel.f && can('files', 'delete')" #footer-start><UiButton variant="ghost" icon="trash" :loading="busy === 'del'" @click="remove">Delete</UiButton></template>
    </UiModal>
  </div>
</template>

<style scoped>
.narrow { flex: 0 1 180px !important; }
.meter { height: 8px; background: var(--bg-subtle); border-radius: 4px; overflow: hidden; }
.meter span { display: block; height: 100%; background: var(--brand-primary); }
.meter span.warn { background: var(--warning); }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 14px; padding: 0 24px 24px; }
.file { position: relative; display: flex; flex-direction: column; gap: 4px; padding: 8px; border: 1px solid var(--border-default); border-radius: var(--radius-md); background: var(--bg-surface); cursor: pointer; text-align: left; min-width: 0; }
.file:hover { border-color: var(--brand-primary); }
.pic { aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--bg-subtle); display: grid; place-items: center; overflow: hidden; }
.pic img { width: 100%; height: 100%; object-fit: cover; }
.ext { font-weight: 600; color: var(--text-secondary); font-size: 13px; }
.name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pub { position: absolute; top: 12px; left: 12px; }
.preview { max-width: 100%; max-height: 220px; border-radius: var(--radius-md); border: 1px solid var(--border-default); justify-self: start; }
.hash { overflow-wrap: anywhere; max-width: 60%; }
.acts { display: flex; gap: 8px; flex-wrap: wrap; }
@media (max-width: 767px) { .grid { padding: 0 16px 16px; grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
