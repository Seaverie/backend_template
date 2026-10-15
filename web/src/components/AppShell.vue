<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/api/client'
import { forgetMe, me } from '@/api/me'
import { clearSession, session } from '@/api/session'
import { platformNav, tenantNav, type NavItem } from '@/nav'
import { resetMeCache } from '@/router'
import AppIcon from './AppIcon.vue'

const route = useRoute()
const router = useRouter()
const open = ref(false)
watch(() => route.fullPath, () => { open.value = false; query.value = '' })

const isPlatform = computed(() => session.audience === 'platform')
const groups = computed(() => (isPlatform.value ? platformNav : tenantNav()))
const brandName = computed(() => (isPlatform.value ? 'Platform console' : me.tenant?.settings.display_name || me.tenant?.tenant.name || ''))
const logo = computed(() => (isPlatform.value ? null : me.tenant?.settings.logo_url ?? null))
const userName = computed(() => (isPlatform.value ? me.platform?.full_name || me.platform?.email : me.tenant?.user.full_name || me.tenant?.user.email) ?? session.email ?? '')
const initials = computed(() => (userName.value || 'U').split(/[\s@.]+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase())
const roleLabel = computed(() => (isPlatform.value ? 'Platform admin' : me.tenant?.is_tenant_admin ? 'Workspace admin' : 'Team member'))
const accountLink = computed(() => (isPlatform.value ? '/platform/mfa' : '/account'))
const isActive = (to: string) => (to === '/' || to === '/platform' ? route.path === to : route.path === to || route.path.startsWith(to + '/')) ||
  (to === '/platform' && route.path.startsWith('/platform/tenants'))

// "Jump to" search over the pages in the menu.
const query = ref('')
const active = ref(0)
const results = computed<NavItem[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return []
  return groups.value.flatMap((g) => g.items).filter((i) => `${i.label} ${i.keywords ?? ''}`.toLowerCase().includes(q)).slice(0, 6)
})
watch(query, () => { active.value = 0 })
function go(i?: NavItem) { const t = i ?? results.value[active.value]; if (t) router.push(t.to) }
function onSearchKey(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') { active.value = Math.min(active.value + 1, results.value.length - 1); e.preventDefault() }
  else if (e.key === 'ArrowUp') { active.value = Math.max(active.value - 1, 0); e.preventDefault() }
  else if (e.key === 'Escape') query.value = ''
}

async function signOut() {
  const platform = isPlatform.value
  try {
    if (platform) await api.post('/platform/auth/logout')
    else await api.post('/auth/logout', { refresh_token: session.refresh })
  } catch { /* already signed out on the server */ }
  clearSession(); forgetMe(); resetMeCache()
  router.push(platform ? '/platform/login' : '/login')
}
</script>

<template>
  <div class="shell">
    <a class="skip" href="#main">Skip to content</a>
    <div v-if="open" class="scrim" aria-hidden="true" @click="open = false" />
    <nav class="sidebar" :class="{ open }" aria-label="Main">
      <div class="brand">
        <img v-if="logo" class="logo img" :src="logo" alt="" />
        <span v-else class="logo">{{ (brandName || 'TC').slice(0, 2).toUpperCase() }}</span>
        <div class="wordmark"><strong>{{ brandName }}</strong><span>{{ isPlatform ? 'All workspaces' : session.tenant }}</span></div>
        <button class="icon-btn close" type="button" aria-label="Close menu" @click="open = false"><AppIcon name="close" /></button>
      </div>
      <template v-for="g in groups" :key="g.title">
        <p class="section">{{ g.title.toUpperCase() }}</p>
        <RouterLink v-for="n in g.items" :key="n.to" :to="n.to" class="nav-item" :class="{ active: isActive(n.to) }"
                    :aria-current="isActive(n.to) ? 'page' : undefined">
          <AppIcon :name="n.icon" /><span>{{ n.label }}</span>
        </RouterLink>
      </template>
      <div class="grow" />
      <div class="user">
        <span class="avatar">{{ initials }}</span>
        <RouterLink class="who" :to="accountLink"><strong>{{ userName }}</strong><span>{{ roleLabel }}</span></RouterLink>
        <button class="icon-btn" type="button" aria-label="Sign out" title="Sign out" @click="signOut"><AppIcon name="logout" /></button>
      </div>
    </nav>

    <div class="main">
      <header class="topbar">
        <button class="icon-btn menu" type="button" aria-label="Open menu" :aria-expanded="open" @click="open = true"><AppIcon name="menu" /></button>
        <form class="search" role="search" @submit.prevent="go()">
          <AppIcon name="search" :size="16" />
          <label class="sr-only" for="jump">Jump to a page</label>
          <input id="jump" v-model="query" type="search" placeholder="Jump to a page…" autocomplete="off"
                 role="combobox" :aria-expanded="results.length > 0" aria-controls="jump-list" @keydown="onSearchKey" />
          <ul v-if="results.length" id="jump-list" class="jump" role="listbox">
            <li v-for="(r, i) in results" :key="r.to" role="option" :aria-selected="i === active" :class="{ on: i === active }"
                @mousedown.prevent="go(r)" @mouseenter="active = i"><AppIcon :name="r.icon" :size="16" />{{ r.label }}</li>
          </ul>
        </form>
        <div class="grow" />
        <RouterLink class="avatar small" :to="accountLink" :title="`Signed in as ${userName}`">{{ initials }}</RouterLink>
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
.grow { flex: 1; }
.user { display: flex; align-items: center; gap: 10px; background: var(--bg-sidebar-active); border-radius: var(--radius-md); padding: 10px 12px; margin-top: 12px; }
.who { color: inherit; text-decoration: none !important; display: flex; flex-direction: column; min-width: 0; flex: 1; font-size: 12px; }
.who span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.who strong { color: #fff; font-size: 13px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.avatar { width: 32px; height: 32px; border-radius: 50%; background: var(--brand-soft); color: var(--brand-primary); display: grid; place-items: center; font-size: 12px; font-weight: 600; flex: none; }
.icon-btn { width: 40px; height: 40px; display: grid; place-items: center; border: 0; background: transparent; color: inherit; border-radius: var(--radius-md); cursor: pointer; flex: none; }
.icon-btn:hover { background: rgba(148, 163, 184, 0.15); }
.close, .menu { display: none; }

.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.topbar { position: sticky; top: env(safe-area-inset-top, 0px); z-index: 10; height: var(--topbar-height); display: flex; align-items: center; gap: 16px; padding: 0 32px; background: var(--bg-surface); border-bottom: 1px solid var(--border-default); }
.search { position: relative; display: flex; align-items: center; gap: 8px; width: min(360px, 100%); background: var(--bg-subtle); border-radius: var(--radius-md); padding: 0 12px; color: var(--text-secondary); }
.search input { border: 0; background: transparent; padding: 8px 0; width: 100%; font-size: 13px; outline: none; }
.scrim { display: none; }
.jump { position: absolute; top: calc(100% + 6px); left: 0; right: 0; z-index: 20; list-style: none; margin: 0; padding: 6px; background: var(--bg-surface); border: 1px solid var(--border-default); border-radius: var(--radius-md); box-shadow: var(--shadow-overlay); }
.jump li { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: var(--radius-sm); cursor: pointer; color: var(--text-primary); }
.jump li.on { background: var(--brand-soft); color: var(--brand-primary); }
a.avatar { text-decoration: none; }
.logo.img { object-fit: contain; background: #fff; }

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
}
</style>
