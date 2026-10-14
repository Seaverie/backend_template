import { createMemoryHistory, createRouter, createWebHashHistory, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { session } from './api/session'

// history: clean URLs (server must fall back to index.html) · hash: static hosting · memory: embedded previews
const mode = import.meta.env.VITE_ROUTER_MODE
const history = mode === 'hash' ? createWebHashHistory() : mode === 'memory' ? createMemoryHistory() : createWebHistory(import.meta.env.BASE_URL)

const tenant = (path: string, title: string, component: RouteRecordRaw['component']): RouteRecordRaw =>
  ({ path, component, meta: { audience: 'tenant', title } }) as RouteRecordRaw
const platform = (path: string, title: string, component: RouteRecordRaw['component']): RouteRecordRaw =>
  ({ path, component, meta: { audience: 'platform', title } }) as RouteRecordRaw

export const router = createRouter({
  history,
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue'), meta: { public: true, title: 'Sign in' } },
    tenant('/', 'Dashboard', () => import('./views/DashboardView.vue')),
    tenant('/orders', 'Orders', () => import('./views/OrdersView.vue')),
    tenant('/catalog', 'Catalog & stock', () => import('./views/CatalogView.vue')),
    tenant('/data', 'Data tables', () => import('./views/DataView.vue')),
    tenant('/imports', 'Imports', () => import('./views/ImportsView.vue')),
    tenant('/users', 'Users & roles', () => import('./views/UsersView.vue')),
    tenant('/api-keys', 'API keys', () => import('./views/ApiKeysView.vue')),
    tenant('/webhooks', 'Webhooks', () => import('./views/WebhooksView.vue')),
    tenant('/audit', 'Audit log', () => import('./views/AuditView.vue')),
    tenant('/settings', 'Settings', () => import('./views/SettingsView.vue')),
    platform('/platform/tenants', 'Tenants', () => import('./views/TenantsView.vue')),
    platform('/platform/tables', 'Schema & tables', () => import('./views/SchemaView.vue')),
    platform('/platform/dead-letters', 'Dead letters', () => import('./views/DeadLettersView.vue')),
    platform('/platform/diagnostics', 'Diagnostics', () => import('./views/DiagnosticsView.vue')),
    platform('/platform/security', 'Security & MFA', () => import('./views/SecurityView.vue')),
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

// Client-side guards only decide what to show; the API enforces every permission itself.
router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!session.access) return { path: '/login', query: to.fullPath === '/' ? {} : { next: to.fullPath } }
  if (to.meta.audience && to.meta.audience !== session.audience) return session.audience === 'platform' ? '/platform/tenants' : '/'
  return true
})
router.afterEach((to) => { document.title = `${String(to.meta.title ?? 'Admin')} · TenantCore` })

// The API client fires this when a token is rejected and cannot be refreshed.
window.addEventListener('tc:signed-out', () => {
  const here = router.currentRoute.value
  if (!here.meta.public) router.push({ path: '/login', query: { next: here.fullPath, expired: '1' } })
})
