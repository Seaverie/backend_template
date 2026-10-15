<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/api/client'
import { errText } from '@/ui'
import AuthCard from '@/components/AuthCard.vue'
import UiButton from '@/components/UiButton.vue'
import UiInput from '@/components/UiInput.vue'

const route = useRoute()
const tenant = ref(typeof route.query.workspace === 'string' ? route.query.workspace : '')
const email = ref('')
const sent = ref(false)
const busy = ref(false)
const error = ref('')
async function submit() {
  error.value = ''
  if (!tenant.value.trim() || !email.value.trim()) { error.value = 'Enter your workspace and email.'; return }
  busy.value = true
  try { await api.open('POST', '/auth/forgot-password', { tenant: tenant.value.trim().toLowerCase(), email: email.value.trim() }); sent.value = true }
  catch (e) { error.value = errText(e) } finally { busy.value = false }
}
</script>

<template>
  <AuthCard title="Reset your password" :caption="sent ? '' : 'We will email you a link to choose a new one.'" :error="error">
    <p v-if="sent" class="note">If that address has an account in this workspace, a link is on its way. It works for a short time only.</p>
    <form v-else novalidate @submit.prevent="submit">
      <UiInput v-model="tenant" label="Workspace" autocomplete="organization" />
      <UiInput v-model="email" label="Email" type="email" autocomplete="username" inputmode="email" />
      <UiButton type="submit" variant="primary" block :loading="busy">Send link</UiButton>
    </form>
    <template #below><RouterLink to="/login">Back to sign in</RouterLink></template>
  </AuthCard>
</template>
