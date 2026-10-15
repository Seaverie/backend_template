<script setup lang="ts">
import { reactive } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { forgetMe, me } from '@/api/me'
import { clearSession, session } from '@/api/session'
import { useBusy } from '@/composables'
import { resetMeCache } from '@/router'
import PageHeader from '@/components/PageHeader.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'

const router = useRouter()
const pw = reactive({ current: '', next: '', again: '' })
const { busy, run } = useBusy()
async function change() {
  if (pw.next !== pw.again) return
  const ok = await run('pw', () => api.post('/auth/change-password', { current_password: pw.current, new_password: pw.next }), 'Password changed. Sign in again with the new one.')
  if (ok) { clearSession(); forgetMe(); resetMeCache(); router.push('/login') }
}
async function signOut() {
  try { await api.post('/auth/logout', { refresh_token: session.refresh }) } catch { /* already signed out */ }
  clearSession(); forgetMe(); resetMeCache(); router.push('/login')
}
</script>

<template>
  <div class="page narrow">
    <PageHeader title="Your account" :caption="me.tenant ? `${me.tenant.user.email} · ${me.tenant.tenant.name}` : ''">
      <UiButton icon="logout" @click="signOut">Sign out</UiButton>
    </PageHeader>
    <UiCard title="Change password" caption="You will be signed out everywhere afterwards.">
      <form class="card-body" @submit.prevent="change">
        <UiInput v-model="pw.current" label="Current password" type="password" autocomplete="current-password" />
        <UiInput v-model="pw.next" label="New password" type="password" autocomplete="new-password" hint="At least 12 characters." />
        <UiInput v-model="pw.again" label="Repeat new password" type="password" autocomplete="new-password" :error="pw.again && pw.again !== pw.next ? 'The two passwords are different.' : undefined" />
        <div><UiButton type="submit" variant="primary" :loading="busy === 'pw'" :disabled="!pw.current || pw.next.length < 12 || pw.next !== pw.again">Change password</UiButton></div>
      </form>
    </UiCard>
  </div>
</template>

<style scoped>.narrow { max-width: 720px; }</style>
