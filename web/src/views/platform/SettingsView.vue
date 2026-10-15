<script setup lang="ts">
import { reactive } from 'vue'
import { api } from '@/api/client'
import { useBusy, useLoader } from '@/composables'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'

const s = reactive({ loaded: false, platform_name: '', public_url: '' })
const { loading, error, reload } = useLoader(async () => { const r = await api.get('/platform/settings/general'); Object.assign(s, { loaded: true, ...r }) })
const { busy, run } = useBusy()
async function save() { await run('save', async () => { Object.assign(s, await api.put('/platform/settings/general', { platform_name: s.platform_name.trim(), public_url: s.public_url.trim() })) }, 'Saved.') }
</script>

<template>
  <div class="page narrow">
    <PageHeader title="General" caption="Platform-wide settings." />
    <LoadState :loading="loading" :error="error" :ready="s.loaded" @retry="reload">
      <UiCard title="Platform">
        <form class="card-body" @submit.prevent="save">
          <UiInput v-model="s.platform_name" label="Platform name" hint="Shown in platform emails." />
          <UiInput v-model="s.public_url" label="Public address of this dashboard" type="url" placeholder="https://admin.example.com" hint="Used for links in emails (invitations, password resets). Use your Cloudflare Tunnel address in production." />
          <div><UiButton type="submit" variant="primary" :loading="busy === 'save'">Save</UiButton></div>
        </form>
      </UiCard>
    </LoadState>
  </div>
</template>

<style scoped>.narrow { max-width: 760px; }</style>
