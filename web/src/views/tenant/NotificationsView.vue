<script setup lang="ts">
import { reactive, ref } from 'vue'
import { api } from '@/api/client'
import { can, hasModule } from '@/api/me'
import { useBusy, useList, useLoader } from '@/composables'
import { title, when } from '@/format'
import DataTable from '@/components/DataTable.vue'
import LoadState from '@/components/LoadState.vue'
import PageHeader from '@/components/PageHeader.vue'
import UiButton from '@/components/UiButton.vue'
import UiCard from '@/components/UiCard.vue'
import UiInput from '@/components/UiInput.vue'
import UiModal from '@/components/UiModal.vue'
import UiPager from '@/components/UiPager.vue'
import UiSelect from '@/components/UiSelect.vue'

const settings = ref<any>(null)
useLoader(async () => { settings.value = await api.get('/notifications/settings') })
const list = useList((q) => api.get('/notifications', q), {})
const { busy, run } = useBusy()
const form = reactive({ open: false, title: '', body: '', audience: 'customers', link: '' })
async function send() {
  const audience = form.audience === 'customers' ? { all_customers: true } : form.audience === 'staff' ? { all_users: true } : { all_customers: true, all_users: true }
  const ok = await run('send', async () => {
    await api.post('/notifications', { title: form.title.trim(), body: form.body.trim(), audience, data: form.link.trim() ? { link: form.link.trim() } : undefined })
    await list.reload()
  }, 'Sending. Progress shows in the list.')
  if (ok) form.open = false
}
</script>

<template>
  <div class="page">
    <PageHeader title="Push notifications" caption="Messages to your app on customers' and staff phones (Firebase Cloud Messaging).">
      <UiButton v-if="can('notifications', 'manage')" icon="bell" variant="primary" :disabled="!settings?.configured" @click="Object.assign(form, { open: true, title: '', body: '', audience: hasModule('customer_accounts') ? 'customers' : 'staff', link: '' })">Send notification</UiButton>
    </PageHeader>
    <p v-if="settings && !settings.configured" class="note warn">
      Not set up yet. Add your Firebase service account in <RouterLink to="/settings/push">Settings → Push</RouterLink> to send notifications.
    </p>
    <UiCard>
      <LoadState :loading="list.loading.value" :error="list.error.value" :ready="list.items.value.length > 0" @retry="list.reload">
        <DataTable :columns="[{ key: 'title', label: 'Message', primary: true }, { key: 'source', label: 'Sent by' }, { key: 'result', label: 'Delivered', align: 'right' }]"
                   :rows="list.items.value" row-key="id" empty="Nothing sent yet.">
          <template #cell-title="{ row }"><strong>{{ row.title }}</strong><span class="caption">{{ row.body }}</span><span class="caption">{{ when(row.created_at) }}</span></template>
          <template #cell-source="{ row }">{{ title(row.source) }}</template>
          <template #cell-result="{ row }"><span>{{ row.sent }} / {{ row.target_count }}</span><span v-if="row.failed" class="caption">{{ row.failed }} failed</span><span v-if="row.pending" class="caption">{{ row.pending }} waiting</span></template>
        </DataTable>
        <UiPager v-model="list.offset.value" :total="list.total.value" :limit="list.limit" :count="list.items.value.length" />
      </LoadState>
    </UiCard>
    <UiModal :open="form.open" title="Send a notification" submit-label="Send" :busy="busy === 'send'" :submit-disabled="!form.title.trim() || !form.body.trim()" @close="form.open = false" @submit="send">
      <UiSelect v-model="form.audience" label="To" :options="[...(hasModule('customer_accounts') ? [{ value: 'customers', label: 'All customers with the app' }] : []), { value: 'staff', label: 'All staff with the app' }, ...(hasModule('customer_accounts') ? [{ value: 'everyone', label: 'Everyone' }] : [])]" />
      <UiInput v-model="form.title" label="Title" :maxlength="200" />
      <UiInput v-model="form.body" label="Message" multiline :rows="3" :maxlength="1000" />
      <UiInput v-model="form.link" label="Link (optional)" placeholder="myapp://promo/october" hint="Sent to the app as data.link; your app decides what to open." />
    </UiModal>
  </div>
</template>
