<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, USE_MOCKS } from '@/api/client'
import { saveSession } from '@/api/session'
import AppIcon from '@/components/AppIcon.vue'
import UiButton from '@/components/UiButton.vue'
import UiInput from '@/components/UiInput.vue'

const route = useRoute()
const router = useRouter()
const platform = ref(typeof route.query.next === 'string' && route.query.next.startsWith('/platform'))
const tenant = ref(USE_MOCKS ? 'acme-store' : '')
const email = ref(USE_MOCKS ? 'owner@acme.example.com' : '')
const password = ref(USE_MOCKS ? 'demo-password' : '')
const otp = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  error.value = ''; busy.value = true
  try {
    const pair = platform.value
      ? await api.platformLogin(email.value, password.value, otp.value || undefined)
      : await api.login(tenant.value, email.value, password.value)
    saveSession({ audience: platform.value ? 'platform' : 'tenant', tenant: platform.value ? null : tenant.value, email: email.value, access: pair.access_token, refresh: pair.refresh_token })
    const next = typeof route.query.next === 'string' ? route.query.next : null
    router.push(next ?? (platform.value ? '/platform/tenants' : '/'))
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Sign-in failed.'
  } finally { busy.value = false }
}
</script>

<template>
  <div class="login">
    <aside class="brand-panel">
      <div class="brand"><span class="logo">TC</span><strong>TenantCore</strong></div>
      <div class="pitch">
        <h1>Multi-tenant commerce,<br />secured by default.</h1>
        <ul>
          <li><AppIcon name="shield" :size="20" />Row-level security on every tenant table</li>
          <li><AppIcon name="database" :size="20" />Dedicated databases for enterprise tenants</li>
          <li><AppIcon name="webhook" :size="20" />Signed webhooks with automatic retries</li>
        </ul>
      </div>
      <p class="caption">© 2026 TenantCore</p>
    </aside>

    <main class="form-area">
      <div class="mobile-brand"><span class="logo">TC</span><strong>TenantCore</strong></div>
      <form class="card" @submit.prevent="submit">
        <div>
          <h1>{{ platform ? 'Platform sign in' : 'Sign in' }}</h1>
          <p class="muted">{{ platform ? 'Platform administrators only' : 'to your TenantCore workspace' }}</p>
        </div>
        <UiInput v-if="!platform" v-model="tenant" label="Workspace" placeholder="acme-store" autocomplete="organization" required />
        <UiInput v-model="email" label="Email" type="email" autocomplete="username" inputmode="email" required />
        <UiInput v-model="password" label="Password" type="password" autocomplete="current-password" required />
        <UiInput v-if="platform" v-model="otp" label="Authenticator code" inputmode="numeric" autocomplete="one-time-code" placeholder="123456"
                 hint="6-digit code from your authenticator app (required once MFA is enrolled)" />
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <p v-else-if="route.query.expired" class="demo-note" role="status">Your session ended. Sign in again to continue.</p>
        <UiButton type="submit" variant="primary" block :loading="busy">{{ busy ? 'Signing in…' : 'Sign in' }}</UiButton>
        <hr />
        <button type="button" class="switch" @click="platform = !platform; error = ''">
          <AppIcon name="shield" :size="16" />{{ platform ? 'Back to workspace sign in' : 'Platform administrator? Sign in with MFA' }}
        </button>
        <p v-if="USE_MOCKS" class="demo-note">Demo mode: sample data, any password works.</p>
        <p class="caption">{{ platform ? 'POST /api/v1/platform/auth/login' : 'POST /api/v1/auth/login' }} · accounts lock after repeated failed attempts</p>
      </form>
    </main>
  </div>
</template>

<style scoped>
.login { min-height: 100vh; min-height: 100dvh; display: grid; grid-template-columns: minmax(360px, 640px) 1fr; }
.brand-panel { background: var(--bg-sidebar); color: var(--text-sidebar); padding: 64px; display: flex; flex-direction: column; justify-content: space-between; gap: 32px; }
.brand, .mobile-brand { display: flex; align-items: center; gap: 12px; color: #fff; font-size: 18px; }
.mobile-brand { display: none; color: var(--text-primary); }
.logo { width: 40px; height: 40px; border-radius: var(--radius-md); background: var(--brand-primary); color: #fff; display: grid; place-items: center; font-size: 14px; font-weight: 600; }
.pitch h1 { color: #fff; font-size: clamp(28px, 3vw, 36px); line-height: 1.22; }
.pitch ul { list-style: none; padding: 0; margin: 32px 0 0; display: grid; gap: 16px; }
.pitch li { display: flex; align-items: center; gap: 12px; }
.form-area { display: grid; place-items: center; padding: 32px 16px; gap: 24px; align-content: center; }
.card { width: min(440px, 100%); background: var(--bg-surface); border: 1px solid var(--border-default); border-radius: var(--radius-lg); padding: 40px; display: flex; flex-direction: column; gap: 20px; }
.card h1 + p { margin-top: 4px; }
hr { border: 0; border-top: 1px solid var(--border-default); margin: 0; width: 100%; }
.switch { display: inline-flex; align-items: center; gap: 8px; border: 0; background: none; padding: 0; color: var(--brand-primary); cursor: pointer; font-size: 13px; text-align: left; }
.demo-note { font-size: 13px; color: var(--warning); background: var(--warning-bg); padding: 8px 12px; border-radius: var(--radius-md); }
.error { color: var(--danger); background: var(--danger-bg); padding: 10px 12px; border-radius: var(--radius-md); font-size: 13px; }
@media (max-width: 959px) {
  .login { grid-template-columns: 1fr; }
  .brand-panel { display: none; }
  .mobile-brand { display: flex; }
}
@media (max-width: 479px) { .card { padding: 24px 20px; border: 0; background: transparent; } .form-area { align-content: start; padding-top: 40px; } }
</style>
