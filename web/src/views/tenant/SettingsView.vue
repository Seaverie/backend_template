<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { can, hasModule, isAdmin } from '@/api/me'
import EmailPanel from '@/components/EmailPanel.vue'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'

const props = defineProps<{ tab?: string }>()
const router = useRouter()
const S = (n: string) => defineAsyncComponent(() => import(`./settings/${n}.vue`))
const all = [
  { key: 'general', label: 'Business', show: () => can('settings'), comp: S('GeneralTab') },
  { key: 'orders', label: 'Orders & checkout', show: () => hasModule('orders') && can('orders', 'manage'), comp: S('OrdersTab') },
  { key: 'payments', label: 'Payments', show: () => hasModule('payments') && can('payments', 'manage'), comp: S('PaymentsTab') },
  { key: 'customers', label: 'Customer accounts', show: () => hasModule('customer_accounts') && can('customers', 'manage'), comp: S('CustomersTab') },
  { key: 'email', label: 'Email', show: () => hasModule('email') && can('settings', 'manage'), comp: null },
  { key: 'maps', label: 'Maps & QR', show: () => (hasModule('maps') && can('maps', 'manage')) || (hasModule('qr_codes') && can('settings', 'manage')), comp: S('MapsQrTab') },
  { key: 'push', label: 'Push', show: () => hasModule('push_notifications') && can('notifications', 'manage'), comp: S('PushTab') },
  { key: 'modules', label: 'Modules', show: () => true, comp: S('ModulesTab') },
  { key: 'export', label: 'Export data', show: () => isAdmin(), comp: S('ExportTab') },
]
const tabs = computed(() => all.filter((t) => t.show()))
const current = ref(tabs.value.find((t) => t.key === props.tab)?.key ?? tabs.value[0]?.key ?? 'modules')
watch(current, (k) => router.replace(`/settings/${k}`))
if (props.tab !== current.value) router.replace(`/settings/${current.value}`)
const comp = computed(() => all.find((t) => t.key === current.value)?.comp)
</script>

<template>
  <div class="page">
    <PageHeader title="Settings" caption="How this workspace looks and works. Everything here takes effect right away." />
    <div class="layout">
      <nav class="side" aria-label="Settings sections">
        <button v-for="t in tabs" :key="t.key" type="button" class="item" :class="{ on: current === t.key }" :aria-current="current === t.key ? 'page' : undefined" @click="current = t.key">{{ t.label }}</button>
      </nav>
      <div class="content">
        <EmailPanel v-if="current === 'email'" base="/email" workspace />
        <component :is="comp" v-else-if="comp" />
        <EmptyState v-else title="Nothing to change here" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.layout { display: grid; grid-template-columns: 200px minmax(0, 1fr); gap: 24px; align-items: start; }
.side { display: grid; gap: 2px; position: sticky; top: calc(var(--topbar-height) + 16px); }
.item { text-align: left; border: 0; background: none; padding: 9px 12px; border-radius: var(--radius-md); cursor: pointer; color: var(--text-secondary); font-weight: 500; }
.item:hover { background: var(--bg-subtle); color: var(--text-primary); }
.item.on { background: var(--bg-surface); color: var(--text-primary); box-shadow: inset 0 0 0 1px var(--border-strong); }
.content { min-width: 0; display: grid; gap: 24px; }
@media (max-width: 899px) {
  .layout { grid-template-columns: minmax(0, 1fr); }
  .side { position: static; display: flex; overflow-x: auto; gap: 6px; scrollbar-width: none; }
  .item { white-space: nowrap; }
}
</style>
