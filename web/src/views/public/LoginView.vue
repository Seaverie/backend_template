<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, api } from '@/api/client'
import { forgetMe } from '@/api/me'
import { saveSession, session } from '@/api/session'
import { resetMeCache } from '@/router'
import AuthCard from '@/components/AuthCard.vue'
import UiButton from '@/components/UiButton.vue'
import UiInput from '@/components/UiInput.vue'

const route = useRoute()
const router = useRouter()
const platform = computed(() => !!route.meta.platform)
const tenant = ref(session.tenant ?? (typeof route.query.workspace === 'string' ? route.query.workspace : ''))
const email = ref('')
const password = ref('')
const otp = ref('')
const needOtp = ref(false)
const busy = ref(false)
const error = ref('')

const nextPath = () => {
  const n = route.query.next
  return typeof n === 'string' && n.startsWith('/') && !n.startsWith('//') ? n : platform.value ? '/platform' : '/'
}

async function submit() {
  error.value = ''
  if (!platform.value && !tenant.value.trim()) { error.value = 'Enter your workspace.'; return }
  if (!email.value.trim() || !password.value) { error.value = 'Enter your email and password.'; return }
  busy.value = true
  try {
    const t = platform.value
      ? await api.open('POST', '/platform/auth/login', { email: email.value.trim(), password: password.value, otp: otp.value.trim() || null })
      : await api.open('POST', '/auth/login', { tenant: tenant.value.trim().toLowerCase(), email: email.value.trim(), password: password.value })
    forgetMe(); resetMeCache()
    saveSession({ audience: platform.value ? 'platform' : 'tenant', tenant: platform.value ? session.tenant : tenant.value.trim().toLowerCase(),
                  email: email.value.trim(), access: t.access_token, refresh: t.refresh_token })
    await router.replace(nextPath())
  } catch (e) {
    if (e instanceof ApiError && e.code === 'otp_required') { needOtp.value = true; error.value = otp.value ? 'That code did not work. Try the current one.' : '' }
    else error.value = e instanceof ApiError ? (e.status === 422 ? 'Check what you entered.' : e.message) : 'Could not sign in.'
  } finally { busy.value = false }
}
</script>

<template>
  <AuthCard :title="platform ? 'Platform console' : 'Sign in'" :caption="platform ? 'For the people who run this platform.' : 'Sign in to your workspace.'" :error="error">
    <form novalidate @submit.prevent="submit">
      <UiInput v-if="!platform" v-model="tenant" label="Workspace" placeholder="your-business" autocomplete="organization" hint="The short name your admin gave you." />
      <UiInput v-model="email" label="Email" type="email" autocomplete="username" inputmode="email" />
      <UiInput v-model="password" label="Password" type="password" autocomplete="current-password" />
      <UiInput v-if="needOtp" v-model="otp" label="Authenticator code" inputmode="numeric" autocomplete="one-time-code" placeholder="123456" :maxlength="6" />
      <UiButton type="submit" variant="primary" block :loading="busy">Sign in</UiButton>
      <RouterLink v-if="!platform" class="caption" :to="{ path: '/forgot-password', query: { workspace: tenant || undefined } }">Forgot your password?</RouterLink>
    </form>
    <template #below>
      <RouterLink v-if="platform" to="/login">Workspace sign in</RouterLink>
      <RouterLink v-else to="/platform/login">Platform admin? Sign in here</RouterLink>
    </template>
  </AuthCard>
</template>
