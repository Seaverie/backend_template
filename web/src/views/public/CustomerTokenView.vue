<script setup lang="ts">
// Default landing pages for shop customers' emails (confirm address, reset password), used until
// the business sets its own website address in the customer account settings.
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/api/client'
import { errText } from '@/ui'
import AuthCard from '@/components/AuthCard.vue'
import UiButton from '@/components/UiButton.vue'
import UiInput from '@/components/UiInput.vue'

const props = defineProps<{ slug: string; mode: 'verify' | 'reset' }>()
const route = useRoute()
const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))
const password = ref('')
const done = ref(false)
const busy = ref(false)
const error = ref('')
const base = computed(() => `/public/shop/${encodeURIComponent(props.slug)}/account`)

onMounted(async () => {
  if (props.mode !== 'verify' || !token.value) return
  busy.value = true
  try { await api.open('POST', `${base.value}/verify-email`, { token: token.value }); done.value = true }
  catch (e) { error.value = errText(e) } finally { busy.value = false }
})
async function reset() {
  error.value = ''
  busy.value = true
  try { await api.open('POST', `${base.value}/reset-password`, { token: token.value, password: password.value }); done.value = true }
  catch (e) { error.value = errText(e) } finally { busy.value = false }
}
</script>

<template>
  <AuthCard :title="mode === 'verify' ? 'Confirm your email' : 'Choose a new password'" :error="!token ? 'This link is incomplete. Open it again from the email.' : error">
    <p v-if="mode === 'verify' && busy" class="muted">Checking your link…</p>
    <p v-if="done" class="note">{{ mode === 'verify' ? 'Thanks, your email address is confirmed. You can close this page.' : 'Your password was changed. Sign in again in the app or shop.' }}</p>
    <form v-else-if="mode === 'reset' && token" novalidate @submit.prevent="reset">
      <UiInput v-model="password" label="New password" type="password" autocomplete="new-password" hint="At least 8 characters." />
      <UiButton type="submit" variant="primary" block :loading="busy">Save password</UiButton>
    </form>
  </AuthCard>
</template>
