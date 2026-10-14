<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, USE_MOCKS } from '@/api/client'
import { clearSession, session } from '@/api/session'
import AppIcon from './AppIcon.vue'
import UiBadge from './UiBadge.vue'

const route = useRoute()
const router = useRouter()
const open = ref(false)
watch(() => route.fullPath, () => { open.value = false })

const tenantNav = [
  { to: '/', label: 'Dashboard', icon: 'dashboard' }, { to: '/orders', label: 'Orders', icon: 'orders' },
  { to: '/users', label: 'Users & roles', icon: 'users' }, { to: '/webhooks', label: 'Webhooks', icon: 'webhook' },
]
const tenantLater = [
  { label: 'Catalog & stock', icon: 'package' }, { label: 'Data tables', icon: 'database' }, { label: 'Imports', icon: 'import' },
  { label: 'API keys', icon: 'key' }, { label: 'Audit log', icon: 'audit' },
]
const platformNav = [{ to: '/platform/tenants', label: 'Tenants', icon: 'tenants' }]
const platformLater = [{ label: 'Schema & tables', icon: 'database' }, { label: 'Background tasks', icon: 'refresh' }, { label: 'Dead letters', icon: 'audit' }]

const isPlatform = computed(() => session.audience === 'platform')
const nav = computed(() => (isPlatform.value ? platformNav : tenantNav))
const later = computed(() => (isPlatform.value ? platformLater : tenantLater))
const initials = computed(() => (session.email ?? 'U').slice(0, 2).toUpperCase())

async function signOut() {
  try { await api.logout() } catch { /* already signed out server-side */ }
  clearSession()
  router.push('/login')
}
</script>

<template>
  <div class="shell">
    <a class="skip" href="#main">Skip to content</a>
    <div v-if="open" class="scrim" aria-hidden="true" @click="open = false" />
    <nav class="sidebar" :class="{ open }" aria-label="Main">
      <div class="brand">
        <span class="logo">TC</span>
        <div class="wordmark"><strong>TenantCore</strong><span>{{ isPlatform ? 'Platform admin' : session.tenant }}</span></div>
        <button class="icon-btn close" type="button" aria-label="Close menu" @click="open = false"><AppIcon name="close" /></button>
      </div>
      <p class="section">MENU</p>
      <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="nav-item" :class="{ active: route.path === n.to }"
                  :aria-current="route.path === n.to ? 'page' : undefined">
        <AppIcon :name="n.icon" /><span>{{ n.label }}</span>
      </RouterLink>
      <p class="section">COMING NEXT</p>
      <span v-for="n in later" :key="n.label" class="nav-item disabled" aria-disabled="true"><AppIcon :name="n.icon" /><span>{{ n.label }}</span></span>
      <div class="grow" />
      <div class="user">
        <span class="avatar">{{ initials }}</span>
        <div class="who"><strong>{{ session.email }}</strong><span>{{ isPlatform ? 'Platform admin' : 'Tenant admin' }}</span></div>
        <button class="icon-btn" type="button" aria-label="Sign out" title="Sign out" @click="signOut"><AppIcon name="logout" /></button>
      </div>
    </nav>

    <div class="main">
      <header class="topbar">
        <button class="icon-btn menu" type="button" aria-label="Open menu" :aria-expanded="open" @click="open = true"><AppIcon name="menu" /></button>
        <label class="search"><AppIcon name="search" :size="16" /><span class="sr-only">Search</span>
          <input type="search" placeholder="Search orders, users, tables…" /></label>
        <div class="grow" />
        <UiBadge v-if="USE_MOCKS" tone="warning" title="Sample data: set VITE_USE_MOCKS=false to use the API">Demo data</UiBadge>
        <UiBadge v-else tone="success" class="health">Live API</UiBadge>
        <span class="avatar small" aria-hidden="true">{{ initials }}</span>
      </header>
      <main id="main" tabindex="-1"><slot /></main>
    </div>
  </div>
</template>

<style scoped>
.shell { min-height: 100vh; min-height: 100dvh; display: flex; }
.skip { position: absolute; left: -999px; top: 8px; z-index: 100; background: var(--bg-surface); padding: 8px 12px; border-radius: var(--radius-md); }
.skip:focus { left: 8px; }
.sidebar {
  width: var(--sidebar-width); flex: 0 0 var(--sidebar-width); background: var(--bg-sidebar); color: var(--text-sidebar);
  display: flex; flex-direction: column; gap: 4px; padding: 20px 16px 16px; position: sticky; top: 0; height: 100vh; height: 100dvh; overflow-y: auto;
}
.brand { display: flex; align-items: center; gap: 10px; padding: 0 8px 16px; }
.logo { width: 32px; height: 32px; border-radius: var(--radius-md); background: var(--brand-primary); color: #fff; display: grid; place-items: center; font-size: 12px; font-weight: 600; flex: none; }
.wordmark { display: flex; flex-direction: column; min-width: 0; flex: 1; }
.wordmark strong { color: #fff; font-size: 15px; }
.wordmark span { font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.section { font-size: 12px; font-weight: 500; letter-spacing: 0.06em; padding: 12px 0 4px; }
.nav-item { display: flex; align-items: center; gap: 12px; height: 40px; padding: 0 12px; border-radius: var(--radius-md); color: var(--text-sidebar); font-weight: 500; text-decoration: none; }
.nav-item:hover { background: var(--bg-sidebar-active); color: #fff; text-decoration: none; }
.nav-item.active { background: var(--bg-sidebar-active); color: #fff; }
.nav-item.disabled { opacity: 0.45; cursor: default; }
.nav-item.disabled:hover { background: none; color: var(--text-sidebar); }
.grow { flex: 1; }
.user { display: flex; align-items: center; gap: 10px; background: var(--bg-sidebar-active); border-radius: var(--radius-md); padding: 10px 12px; margin-top: 12px; }
.who { display: flex; flex-direction: column; min-width: 0; flex: 1; font-size: 12px; }
.who strong { color: #fff; font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.avatar { width: 32px; height: 32px; border-radius: 50%; background: var(--brand-soft); color: var(--brand-primary); display: grid; place-items: center; font-size: 12px; font-weight: 600; flex: none; }
.icon-btn { width: 40px; height: 40px; display: grid; place-items: center; border: 0; background: transparent; color: inherit; border-radius: var(--radius-md); cursor: pointer; flex: none; }
.icon-btn:hover { background: rgba(148, 163, 184, 0.15); }
.close, .menu { display: none; }

.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.topbar { position: sticky; top: env(safe-area-inset-top, 0px); z-index: 10; height: var(--topbar-height); display: flex; align-items: center; gap: 16px; padding: 0 32px; background: var(--bg-surface); border-bottom: 1px solid var(--border-default); }
.search { display: flex; align-items: center; gap: 8px; width: min(360px, 100%); background: var(--bg-subtle); border-radius: var(--radius-md); padding: 0 12px; color: var(--text-secondary); }
.search input { border: 0; background: transparent; padding: 8px 0; width: 100%; font-size: 13px; outline: none; }
.scrim { display: none; }

@media (max-width: 1023px) {
  .sidebar { position: fixed; inset: 0 auto 0 0; z-index: 60; padding-top: calc(20px + env(safe-area-inset-top, 0px)); padding-bottom: calc(16px + env(safe-area-inset-bottom, 0px)); padding-left: calc(16px + env(safe-area-inset-left, 0px)); transform: translateX(-100%); transition: transform 0.2s ease; box-shadow: var(--shadow-overlay); }
  .sidebar.open { transform: none; }
  .scrim { display: block; position: fixed; inset: 0; z-index: 55; background: rgba(15, 23, 42, 0.45); }
  .close, .menu { display: grid; }
  .menu { color: var(--text-primary); margin-left: -8px; }
  .topbar { padding: 0 16px; gap: 8px; }
}
@media (max-width: 639px) {
  .search { display: none; }
  .health { display: none; }
}
</style>
