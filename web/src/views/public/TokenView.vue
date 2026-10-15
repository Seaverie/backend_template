<script setup lang="ts">
// Finishes an emailed link: accept an invitation (choose a password) or reset a forgotten one.
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/api/client'
import { errText } from '@/ui'
import AuthCard from '@/components/AuthCard.vue'
import UiButton from '@/components/UiButton.vue'
import UiInput from '@/components/UiInput.vue'

const props = defineProps<{ mode: 'reset' | 'invite' }>()
const route = useRoute()
const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))
const name = ref('')
const password = ref('')
const confirm = ref('')
const done = ref(false)
const busy = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  if (password.value.length < 12) { error.value = 'Use at least 12 characters.'; return }
  if (password.value !== confirm.value) { error.value = 'The two passwords are different.'; return }
  busy.value = true
  try {
    if (props.mode === 'invite') await api.open('POST', '/auth/accept-invite', { token: token.value, password: password.value, full_name: name.value.trim() || null })
    else await api.open('POST', '/auth/reset-password', { token: token.value, password: password.value })
    done.value = true
  } catch (e) { error.value = errText(e) } finally { busy.value = false }
}
</script>

<template>
  <AuthCard :title="mode === 'invite' ? 'Join the team' : 'Choose a new password'" :error="!token ? 'This link is incomplete. Open it again from the email.' : error">
    <template v-if="done">
      <p class="note">{{ mode === 'invite' ? 'Your account is ready.' : 'Your password was changed. Other devices were signed out.' }}</p>
      <RouterLink to="/login"><UiButton variant="primary" block>Go to sign in</UiButton></RouterLink>
    </template>
    <form v-else-if="token" novalidate @submit.prevent="submit">
      <UiInput v-if="mode === 'invite'" v-model="name" label="Your name" autocomplete="name" />
      <UiInput v-model="password" label="New password" type="password" autocomplete="new-password" hint="At least 12 characters. A short sentence works well." />
      <UiInput v-model="confirm" label="Repeat it" type="password" autocomplete="new-password" />
      <UiButton type="submit" variant="primary" block :loading="busy">{{ mode === 'invite' ? 'Create account' : 'Save password' }}</UiButton>
    </form>
  </AuthCard>
</template>
