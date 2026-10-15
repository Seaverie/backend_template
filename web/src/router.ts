import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { forgetMe, hasModule, can, loadMe, me } from './api/me'
import { onSignedOut } from './api/client'
import { session } from './api/session'
import { toast } from './ui'

declare module 'vue-router' {
  interface RouteMeta { public?: boolean; platform?: boolean; module?: string; perm?: string; title?: string; bare?: boolean }
}

const T = (name: string) => () => import(`./views/tenant/${name}.vue`)
const P = (name: string) => () => import(`./views/platform/${name}.vue`)
const O = (name: string) => () => import(`./views/public/${name}.vue`)

const routes: RouteRecordRaw[] = [
  // Signed out
  { path: '/login', component: O('LoginView'), meta: { public: true, title: 'Sign in' } },
  { path: '/platform/login', component: O('LoginView'), meta: { public: true, platform: true, title: 'Platform sign in' } },
  { path: '/forgot-password', component: O('ForgotView'), meta: { public: true, title: 'Reset password' } },
  { path: '/reset-password', component: O('TokenView'), meta: { public: true, title: 'Choose a new password' }, props: { mode: 'reset' } },
  { path: '/accept-invite', component: O('TokenView'), meta: { public: true, title: 'Accept invitation' }, props: { mode: 'invite' } },
  // Links in shop customer emails (when the business has no website of its own yet)
  { path: '/shop/:slug/verify-email', component: O('CustomerTokenView'), meta: { public: true, title: 'Confirm email' }, props: (r) => ({ slug: r.params.slug, mode: 'verify' }) },
  { path: '/shop/:slug/reset-password', component: O('CustomerTokenView'), meta: { public: true, title: 'Reset password' }, props: (r) => ({ slug: r.params.slug, mode: 'reset' }) },

  // Workspace
  { path: '/', component: T('HomeView'), meta: { title: 'Home' } },
  { path: '/orders', component: T('OrdersView'), meta: { module: 'orders', perm: 'orders', title: 'Orders' } },
  { path: '/orders/:id', component: T('OrderDetailView'), props: true, meta: { module: 'orders', perm: 'orders', title: 'Order' } },
  { path: '/returns', component: T('ReturnsView'), meta: { module: 'returns', perm: 'rma', title: 'Returns' } },
  { path: '/customers', component: T('CustomersView'), meta: { module: 'customer_accounts', perm: 'customers', title: 'Customers' } },
  { path: '/deliveries', component: T('DeliveriesView'), meta: { module: 'delivery_tracking', perm: 'deliveries', title: 'Deliveries' } },
  { path: '/products', component: T('ProductsView'), meta: { module: 'catalog', perm: 'products', title: 'Products' } },
  { path: '/products/:id', component: T('ProductEditView'), props: true, meta: { module: 'catalog', perm: 'products', title: 'Product' } },
  { path: '/inventory', component: T('InventoryView'), meta: { module: 'inventory', perm: 'inventory', title: 'Inventory' } },
  { path: '/purchasing', component: T('PurchasingView'), meta: { module: 'purchasing', perm: 'purchasing', title: 'Purchasing' } },
  { path: '/purchasing/:id', component: T('PurchaseOrderView'), props: true, meta: { module: 'purchasing', perm: 'purchasing', title: 'Purchase order' } },
  { path: '/data', component: T('TablesView'), meta: { module: 'data_tables', title: 'Tables' } },
  { path: '/data/:tableKey', component: T('TableView'), props: true, meta: { module: 'data_tables', title: 'Table' } },
  { path: '/imports', component: T('ImportsView'), meta: { module: 'imports', perm: 'imports', title: 'Imports' } },
  { path: '/files', component: T('FilesView'), meta: { module: 'files', perm: 'files', title: 'Files' } },
  { path: '/notifications', component: T('NotificationsView'), meta: { module: 'push_notifications', perm: 'notifications', title: 'Push notifications' } },
  { path: '/team', component: T('TeamView'), meta: { perm: 'users', title: 'Team' } },
  { path: '/roles', component: T('RolesView'), meta: { perm: 'roles', title: 'Roles' } },
  { path: '/developers/:tab?', component: T('DevelopersView'), props: true, meta: { title: 'Developers' } },
  { path: '/settings/:tab?', component: T('SettingsView'), props: true, meta: { title: 'Settings' } },
  { path: '/audit', component: T('AuditView'), meta: { perm: 'audit_logs', title: 'Activity log' } },
  { path: '/account', component: T('AccountView'), meta: { title: 'Your account' } },

  // Platform console
  { path: '/platform/mfa', component: P('MfaView'), meta: { platform: true, bare: true, title: 'Two-factor authentication' } },
  { path: '/platform', component: P('TenantsView'), meta: { platform: true, title: 'Workspaces' } },
  { path: '/platform/tenants/:id/:tab?', component: P('TenantView'), props: true, meta: { platform: true, title: 'Workspace' } },
  { path: '/platform/admins', component: P('AdminsView'), meta: { platform: true, title: 'Platform admins' } },
  { path: '/platform/jobs', component: P('JobsView'), meta: { platform: true, title: 'Background jobs' } },
  { path: '/platform/backups', component: P('BackupsView'), meta: { platform: true, title: 'Backups' } },
  { path: '/platform/audit', component: P('AuditView'), meta: { platform: true, title: 'Activity log' } },
  { path: '/platform/email', component: P('EmailView'), meta: { platform: true, title: 'Email' } },
  { path: '/platform/storage', component: P('StorageView'), meta: { platform: true, title: 'File storage' } },
  { path: '/platform/settings', component: P('SettingsView'), meta: { platform: true, title: 'General settings' } },

  { path: '/:pathMatch(.*)*', component: O('NotFoundView'), meta: { public: true, title: 'Not found' } },
]

export const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })

let loaded: string | null = null // which session `me` was loaded for

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const wantPlatform = !!to.meta.platform
  if (!session.access || (session.audience === 'platform') !== wantPlatform) {
    // Signed in to the other side: send them to their own home instead of a sign-in loop.
    if (session.access && session.audience) return session.audience === 'platform' ? '/platform' : '/'
    return { path: wantPlatform ? '/platform/login' : '/login', query: to.fullPath !== '/' && to.fullPath !== '/platform' ? { next: to.fullPath } : {} }
  }
  const key = `${session.audience}:${session.email}:${session.tenant}`
  if (loaded !== key || (!me.tenant && !me.platform)) {
    try { await loadMe(); loaded = key } catch { return wantPlatform ? '/platform/login' : '/login' }
  }
  if (wantPlatform) {
    const p = me.platform!
    if (p.mfa_required && !p.mfa_enabled && to.path !== '/platform/mfa') return '/platform/mfa'
    return true
  }
  if ((to.meta.module && !hasModule(to.meta.module)) || (to.meta.perm && !can(to.meta.perm))) {
    toast(to.meta.module && !hasModule(to.meta.module) ? 'That part of the dashboard is not switched on for this workspace.' : 'Your role does not include that page.', 'err')
    return to.path === '/' ? true : '/'
  }
  return true
})

router.afterEach((to) => {
  const name = me.tenant ? me.tenant.settings.display_name || me.tenant.tenant.name : me.platform ? 'Platform' : ''
  document.title = [to.meta.title, name].filter(Boolean).join(' · ') || 'Dashboard'
})

onSignedOut.push((reason) => {
  const platform = router.currentRoute.value.meta.platform
  forgetMe(); loaded = null
  if (!router.currentRoute.value.meta.public) {
    toast(reason && !/token/i.test(reason) ? reason : 'Your session ended. Sign in again.', 'err')
    router.push(platform ? '/platform/login' : '/login')
  }
})

export function resetMeCache(): void { loaded = null }
