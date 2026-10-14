import { createMemoryHistory, createRouter, createWebHashHistory, createWebHistory } from 'vue-router'
import { session } from './api/session'

// history: clean URLs (server must fall back to index.html) · hash: static hosting · memory: embedded previews
const mode = import.meta.env.VITE_ROUTER_MODE
const history = mode === 'hash' ? createWebHashHistory() : mode === 'memory' ? createMemoryHistory() : createWebHistory(import.meta.env.BASE_URL)

export const router = createRouter({
  history,
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue'), meta: { public: true, title: 'Sign in' } },
    { path: '/', component: () => import('./views/DashboardView.vue'), meta: { audience: 'tenant', title: 'Dashboard' } },
    { path: '/orders', component: () => import('./views/OrdersView.vue'), meta: { audience: 'tenant', title: 'Orders' } },
    { path: '/users', component: () => import('./views/UsersView.vue'), meta: { audience: 'tenant', title: 'Users & roles' } },
    { path: '/webhooks', component: () => import('./views/WebhooksView.vue'), meta: { audience: 'tenant', title: 'Webhooks' } },
    { path: '/platform/tenants', component: () => import('./views/TenantsView.vue'), meta: { audience: 'platform', title: 'Tenants' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

// Client-side guards only decide what to show; the API enforces every permission itself.
router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!session.access) return { path: '/login', query: { next: to.fullPath } }
  if (to.meta.audience && to.meta.audience !== session.audience) return session.audience === 'platform' ? '/platform/tenants' : '/'
  return true
})
router.afterEach((to) => { document.title = `${String(to.meta.title ?? 'Admin')} · TenantCore` })
