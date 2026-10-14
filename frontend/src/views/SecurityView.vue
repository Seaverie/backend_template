<script setup lang="ts">
import { reactive } from 'vue'
import { api } from '@/api/client'
import { saveSession, session } from '@/api/session'
import UiBadge from '@/components/UiBadge.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import { useBusy } from '@/composables'
import { copy } from '@/ui'

const mfa = reactive({ secret: '', uri: '', code: '', done: false })
const { busy, run } = useBusy()
async function setup() { await run('setup', async () => { const r = await api.mfaSetup(); mfa.secret = r.secret; mfa.uri = r.otpauth_uri; mfa.code = '' }) }
async function confirm() {
  // Confirming returns a fresh, MFA-verified token for this session.
  const ok = await run('confirm', async () => { const t = await api.mfaConfirm(mfa.code.trim()); saveSession({ access: t.access_token }) }, 'Two-factor authentication is on.')
  if (ok) { mfa.done = true; mfa.secret = ''; mfa.uri = '' }
}
const grouped = (s: string) => s.replace(/(.{4})/g, '$1 ').trim()
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="titles"><h1>Security &amp; MFA</h1><p class="muted">Protect the platform admin account with a time-based one-time password (TOTP).</p></div>
    </div>
    <div class="split">
      <UiCard title="Two-factor authentication" caption="Required for platform admins in production.">
        <div class="card-body">
          <template v-if="mfa.done">
            <p class="note">Two-factor authentication is enabled. You will be asked for a 6-digit code when you sign in.</p>
          </template>
          <template v-else-if="!mfa.secret">
            <p class="muted">Use an authenticator app such as 1Password, Google Authenticator or Authy. If MFA is already on, an operator has to reset it before it can be set up again.</p>
            <div><UiButton variant="primary" icon="shield" :loading="busy === 'setup'" @click="setup">Set up authenticator</UiButton></div>
          </template>
          <template v-else>
            <p class="section-label">1 · Add this key to your app</p>
            <div class="secret-out"><code>{{ grouped(mfa.secret) }}</code><UiButton icon="copy" @click="copy(mfa.secret)">Copy</UiButton></div>
            <p class="caption">Most apps accept the key typed in, or the setup link below (open it on the phone that has the app).</p>
            <div class="secret-out small"><code>{{ mfa.uri }}</code><UiButton variant="ghost" icon="copy" aria-label="Copy setup link" @click="copy(mfa.uri)" /></div>
            <p class="section-label">2 · Enter the 6-digit code it shows</p>
            <form class="confirm" @submit.prevent="confirm">
              <UiInput v-model="mfa.code" label="Code" inputmode="numeric" autocomplete="one-time-code" placeholder="123456" :maxlength="6" mono />
              <UiButton type="submit" variant="primary" :loading="busy === 'confirm'" :disabled="!/^\d{6}$/.test(mfa.code.trim())">Turn on</UiButton>
            </form>
          </template>
        </div>
      </UiCard>
      <UiCard title="This session">
        <div class="card-body">
          <div class="kv"><span>Signed in as</span><span>{{ session.email }}</span></div>
          <div class="kv"><span>Role</span><UiBadge tone="brand">Platform admin</UiBadge></div>
          <div class="kv"><span>Token storage</span><span>This browser tab only</span></div>
          <p class="caption">Platform sessions are short-lived and are not refreshed; sign in again when one expires.</p>
        </div>
      </UiCard>
    </div>
  </div>
</template>

<style scoped>
.confirm { display: grid; grid-template-columns: minmax(0, 200px) auto; gap: 10px; align-items: end; }
.secret-out code { letter-spacing: 0.05em; }
.secret-out.small code { font-size: 11px; letter-spacing: 0; }
</style>
