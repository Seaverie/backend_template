// One list drives the sidebar, the router titles and the topbar's "jump to" search.
export interface NavItem { to: string; label: string; icon: string; keywords?: string }
export interface NavGroup { title: string; items: NavItem[] }

export const tenantNav: NavGroup[] = [
  { title: 'Commerce', items: [
    { to: '/', label: 'Dashboard', icon: 'dashboard', keywords: 'home sales revenue' },
    { to: '/orders', label: 'Orders', icon: 'orders', keywords: 'checkout payments returns rma refund' },
    { to: '/catalog', label: 'Catalog & stock', icon: 'package', keywords: 'products variants sku warehouses bins inventory' },
  ] },
  { title: 'Data', items: [
    { to: '/data', label: 'Data tables', icon: 'database', keywords: 'records rows customers' },
    { to: '/imports', label: 'Imports', icon: 'import', keywords: 'csv excel upload' },
  ] },
  { title: 'Admin', items: [
    { to: '/users', label: 'Users & roles', icon: 'users', keywords: 'permissions rbac team' },
    { to: '/api-keys', label: 'API keys', icon: 'key', keywords: 'tokens integrations scopes' },
    { to: '/webhooks', label: 'Webhooks', icon: 'webhook', keywords: 'events deliveries' },
    { to: '/audit', label: 'Audit log', icon: 'audit', keywords: 'history activity' },
    { to: '/settings', label: 'Settings', icon: 'settings', keywords: 'rate limit payment gateways stripe sandbox' },
  ] },
]

export const platformNav: NavGroup[] = [
  { title: 'Platform', items: [
    { to: '/platform/tenants', label: 'Tenants', icon: 'tenants', keywords: 'customers organisations offboard database' },
    { to: '/platform/tables', label: 'Schema & tables', icon: 'database', keywords: 'fields columns snapshot' },
  ] },
  { title: 'Operations', items: [
    { to: '/platform/dead-letters', label: 'Dead letters', icon: 'inbox', keywords: 'failed jobs requeue tasks' },
    { to: '/platform/diagnostics', label: 'Diagnostics', icon: 'activity', keywords: 'slow queries performance' },
    { to: '/platform/security', label: 'Security & MFA', icon: 'shield', keywords: 'two factor totp' },
  ] },
]
