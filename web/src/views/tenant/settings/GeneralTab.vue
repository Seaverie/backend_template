<script setup lang="ts">
import { reactive } from 'vue'
import { api } from '@/api/client'
import { applyBrand, can, hasModule, me } from '@/api/me'
import { opt, useBusy, useLoader } from '@/composables'
import FileButton from '@/components/FileButton.vue'
import LoadState from '@/components/LoadState.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiSelect from '@/components/UiSelect.vue'

const f = reactive({ display_name: '', primary_color: '#4f46e5', currency: 'USD', timezone: 'UTC', locale: 'en', logo_url: null as string | null })
const editable = can('settings', 'manage')
const { loading, error, reload } = useLoader(async () => {
  const s = await api.get('/settings')
  Object.assign(f, { display_name: s.display_name ?? '', primary_color: s.primary_color ?? '#4f46e5', currency: s.currency, timezone: s.timezone, locale: s.locale, logo_url: s.logo_url })
})
const zones = (Intl as any).supportedValuesOf?.('timeZone') as string[] | undefined
const currencies = ['THB', 'USD', 'EUR', 'GBP', 'JPY', 'SGD', 'MYR', 'IDR', 'VND', 'PHP', 'CNY', 'HKD', 'TWD', 'KRW', 'INR', 'AUD', 'NZD', 'CAD', 'CHF', 'SEK', 'NOK', 'DKK', 'AED']
const { busy, run } = useBusy()
async function apply(s: any) { if (me.tenant) { me.tenant.settings = s; applyBrand() } Object.assign(f, { logo_url: s.logo_url }) }
async function save() {
  await run('save', async () => apply(await api.put('/settings', { display_name: opt(f.display_name), primary_color: f.primary_color, currency: f.currency.toUpperCase(), timezone: f.timezone, locale: f.locale })), 'Saved.')
}
async function uploadLogo(files: File[]) {
  await run('logo', async () => {
    const fd = new FormData(); fd.append('file', files[0])
    const file = await api.upload('/files?purpose=logo&public=true', fd)
    await apply(await api.put('/settings', { logo_file_id: file.id }))
  }, 'Logo updated.')
}
async function removeLogo() { await run('logo', async () => apply(await api.put('/settings', { remove_logo: true })), 'Logo removed.') }
</script>

<template>
  <LoadState :loading="loading" :error="error" :ready="!!f.currency" @retry="reload">
    <UiCard title="Business" caption="Shown in the dashboard, emails and your shop.">
      <form class="card-body" @submit.prevent="save">
        <UiInput v-model="f.display_name" label="Business name" :disabled="!editable" />
        <div class="grid3">
          <div class="field">
            <label class="label" for="color">Brand colour</label>
            <div class="color"><input id="color" v-model="f.primary_color" type="color" :disabled="!editable" /><input v-model="f.primary_color" class="mono hex" aria-label="Colour code" :disabled="!editable" /></div>
          </div>
          <UiSelect v-model="f.currency" label="Currency" :disabled="!editable" :options="currencies.includes(f.currency) ? currencies : [f.currency, ...currencies]" hint="Used for new orders. Existing orders keep theirs." />
          <UiSelect v-model="f.locale" label="Language" :disabled="!editable" :options="[{ value: 'en', label: 'English' }, { value: 'th', label: 'ไทย (Thai)' }, { value: 'en-GB', label: 'English (UK)' }, { value: 'ja', label: '日本語' }, { value: 'zh', label: '中文' }]" />
        </div>
        <UiSelect v-if="zones" v-model="f.timezone" label="Time zone" :disabled="!editable" :options="zones.includes(f.timezone) ? zones : [f.timezone, ...zones]" hint="Days in sales reports start at midnight here." />
        <UiInput v-else v-model="f.timezone" label="Time zone" :disabled="!editable" />
        <div v-if="editable"><UiButton type="submit" variant="primary" :loading="busy === 'save'">Save</UiButton></div>
      </form>
    </UiCard>
    <UiCard title="Logo" :caption="hasModule('files') ? 'A square or wide image, PNG or SVG works best.' : 'Switch on the Files module to upload a logo.'">
      <div class="card-body logo">
        <img v-if="f.logo_url" :src="f.logo_url" alt="Current logo" />
        <span v-else class="ph">No logo</span>
        <div v-if="editable && hasModule('files')" class="acts">
          <FileButton accept="image/png,image/jpeg,image/webp,image/gif" :loading="busy === 'logo'" @pick="uploadLogo">Upload logo</FileButton>
          <UiButton v-if="f.logo_url" variant="ghost" @click="removeLogo">Remove</UiButton>
        </div>
      </div>
    </UiCard>
  </LoadState>
</template>

<style scoped>
.field { display: flex; flex-direction: column; gap: 6px; }
.color { display: flex; gap: 8px; align-items: center; }
.color input[type=color] { width: 44px; height: 40px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); padding: 2px; background: var(--bg-surface); }
.hex { flex: 1; padding: 10px 12px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); min-width: 0; }
.logo { display: flex !important; align-items: center; gap: 16px; flex-wrap: wrap; }
.logo img { max-height: 64px; max-width: 200px; border: 1px solid var(--border-default); border-radius: var(--radius-md); padding: 6px; background: #fff; }
.ph { width: 64px; height: 64px; display: grid; place-items: center; background: var(--bg-subtle); border-radius: var(--radius-md); font-size: 11px; color: var(--text-secondary); }
.acts { display: flex; gap: 8px; }
</style>
