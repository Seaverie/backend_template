<script setup lang="ts">
import { reactive } from 'vue'
import { api } from '@/api/client'
import { me } from '@/api/me'
import { useBusy, useLoader } from '@/composables'
import LoadState from '@/components/LoadState.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiCheck from '@/components/UiCheck.vue'
import UiInput from '@/components/UiInput.vue'

const f = reactive({ loaded: false, allow_signup: true, require_verified_email: false, storefront_url: '' })
const { loading, error, reload } = useLoader(async () => {
  const s = await api.get('/customers/settings')
  Object.assign(f, { loaded: true, allow_signup: s.allow_signup, require_verified_email: s.require_verified_email, storefront_url: s.storefront_url ?? '' })
})
const { busy, run } = useBusy()
async function save() { await run('save', () => api.put('/customers/settings', { allow_signup: f.allow_signup, require_verified_email: f.require_verified_email, storefront_url: f.storefront_url.trim() }), 'Saved.') }
const apiBase = `${location.origin}/api/v1/public/shop/${me.tenant?.tenant.slug ?? '<workspace>'}/account`
</script>

<template>
  <LoadState :loading="loading" :error="error" :ready="f.loaded" @retry="reload">
    <UiCard title="Customer accounts" caption="Let shoppers sign up in your website or app to see their orders and get notifications.">
      <form class="card-body" @submit.prevent="save">
        <UiCheck v-model="f.allow_signup" toggle label="New customers can sign up" hint="Turn off to stop new sign-ups; existing customers can still sign in." />
        <UiCheck v-model="f.require_verified_email" toggle label="Confirm the email address before the first sign-in" />
        <UiInput v-model="f.storefront_url" label="Your shop's web address" type="url" placeholder="https://shop.example.com" hint="Email links go to /verify-email and /reset-password on this site. Leave empty to use simple pages hosted here." />
        <div><UiButton type="submit" variant="primary" :loading="busy === 'save'">Save</UiButton></div>
      </form>
    </UiCard>
    <UiCard title="For your developer">
      <div class="card-body">
        <p class="muted">Sign-up, sign-in, profile and order history live under:</p>
        <code class="mono path">{{ apiBase }}</code>
        <p class="caption">POST /register, /login, /refresh, /logout, /forgot-password, /reset-password, /verify-email · GET /me, /orders.</p>
      </div>
    </UiCard>
  </LoadState>
</template>

<style scoped>.path { overflow-wrap: anywhere; background: var(--bg-subtle); padding: 8px 10px; border-radius: var(--radius-md); }</style>
