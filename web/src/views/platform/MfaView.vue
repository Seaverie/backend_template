<script setup lang="ts">
// Two-factor setup for platform admins (required in production before anything else works).
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { loadMe, me, forgetMe } from '@/api/me'
import { clearSession, saveSession } from '@/api/session'
import { useBusy } from '@/composables'
import { resetMeCache } from '@/router'
import { copy } from '@/ui'
import AuthCard from '@/components/AuthCard.vue'
import UiButton from '@/components/UiButton.vue'
import UiInput from '@/components/UiInput.vue'

const router = useRouter()
const setup = ref<{ secret: string; otpauth_uri: string; qr_svg: string } | null>(null)
const code = ref('')
const { busy, run } = useBusy()
const enabled = computed(() => !!me.platform?.mfa_enabled)
async function start() { await run('setup', async () => { setup.value = await api.post('/platform/auth/mfa/setup') }) }
async function confirm() {
  await run('confirm', async () => {
    const t = await api.post('/platform/auth/mfa/confirm', { code: code.value.trim() })
    saveSession({ access: t.access_token })
    await loadMe()
    router.replace('/platform')
  }, 'Two-factor authentication is on.')
}
async function signOut() {
  try { await api.post('/platform/auth/logout') } catch { /* ignore */ }
  clearSession(); forgetMe(); resetMeCache(); router.push('/platform/login')
}
</script>

<template>
  <AuthCard title="Two-factor authentication" :caption="enabled ? 'On for your account. Another platform admin can reset it if you lose your phone.' : 'Protect the platform console with a code from an authenticator app.'">
    <template v-if="enabled">
      <p class="note">You will be asked for a 6-digit code each time you sign in.</p>
      <RouterLink to="/platform"><UiButton variant="primary" block>Back to the console</UiButton></RouterLink>
    </template>
    <template v-else-if="!setup">
      <p v-if="me.platform?.mfa_required" class="note warn">Required before you can use the platform console.</p>
      <UiButton variant="primary" block :loading="busy === 'setup'" @click="start">Set up now</UiButton>
    </template>
    <form v-else novalidate @submit.prevent="confirm">
      <p class="muted">Scan with Google Authenticator, 1Password, Authy or similar.</p>
      <!-- eslint-disable-next-line vue/no-v-html -- an SVG drawn by our own server from the otpauth link -->
      <div class="qr" role="img" aria-label="QR code for your authenticator app" v-html="setup.qr_svg" />
      <p class="caption">Can't scan? Enter this key: <code class="mono">{{ setup.secret }}</code> <button type="button" class="linkbtn" @click="copy(setup.secret)">Copy</button></p>
      <UiInput v-model="code" label="6-digit code" inputmode="numeric" autocomplete="one-time-code" :maxlength="6" />
      <UiButton type="submit" variant="primary" block :loading="busy === 'confirm'" :disabled="code.trim().length !== 6">Turn on</UiButton>
    </form>
    <template #below><button type="button" class="linkbtn" @click="signOut">Sign out</button></template>
  </AuthCard>
</template>

<style scoped>
.qr { width: 220px; height: 220px; margin: 0 auto; background: #fff; padding: 8px; border: 1px solid var(--border-default); border-radius: var(--radius-md); }
.qr :deep(svg) { width: 100%; height: 100%; }
.linkbtn { border: 0; background: none; color: var(--brand-primary); cursor: pointer; padding: 0; font: inherit; }
code { overflow-wrap: anywhere; }
</style>
