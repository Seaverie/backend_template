import { can, hasModule, isAdmin } from './api/me'

// The sidebar: each page shows only when its module is switched on for the workspace and the
// signed-in user's role allows reading it. The server enforces the same rules on every call.
export interface NavItem { to: string; label: string; icon: string; keywords?: string; module?: string; perm?: string; admin?: boolean; any?: () => boolean }
export interface NavGroup { title: string; items: NavItem[] }

const tenantGroups: NavGroup[] = [
  { title: 'Overview', items: [{ to: '/', label: 'Home', icon: 'dashboard', keywords: 'sales analytics revenue' }] },
  { title: 'Sales', items: [
    { to: '/orders', label: 'Orders', icon: 'orders', module: 'orders', perm: 'orders', keywords: 'sales checkout payments' },
    { to: '/returns', label: 'Returns', icon: 'undo', module: 'returns', perm: 'rma', keywords: 'rma refunds' },
    { to: '/customers', label: 'Customers', icon: 'user', module: 'customer_accounts', perm: 'customers', keywords: 'accounts shoppers' },
    { to: '/deliveries', label: 'Deliveries', icon: 'truck', module: 'delivery_tracking', perm: 'deliveries', keywords: 'drivers tracking' },
  ] },
  { title: 'Catalog & stock', items: [
    { to: '/products', label: 'Products', icon: 'tag', module: 'catalog', perm: 'products', keywords: 'catalog variants categories sku' },
    { to: '/inventory', label: 'Inventory', icon: 'warehouse', module: 'inventory', perm: 'inventory', keywords: 'stock warehouses bins movements' },
    { to: '/purchasing', label: 'Purchasing', icon: 'clipboard', module: 'purchasing', perm: 'purchasing', keywords: 'suppliers purchase orders receive' },
  ] },
  { title: 'Data', items: [
    { to: '/data', label: 'Tables', icon: 'database', module: 'data_tables', keywords: 'custom fields records rows schema' },
    { to: '/imports', label: 'Imports', icon: 'import', module: 'imports', perm: 'imports', keywords: 'csv excel upload' },
    { to: '/files', label: 'Files', icon: 'image', module: 'files', perm: 'files', keywords: 'uploads images attachments' },
  ] },
  { title: 'Engage', items: [
    { to: '/notifications', label: 'Push notifications', icon: 'bell', module: 'push_notifications', perm: 'notifications', keywords: 'fcm messages app' },
  ] },
  { title: 'Workspace', items: [
    { to: '/team', label: 'Team', icon: 'users', perm: 'users', keywords: 'users invite staff' },
    { to: '/roles', label: 'Roles', icon: 'shield', perm: 'roles', keywords: 'permissions access' },
    { to: '/developers', label: 'Developers', icon: 'webhook', any: () => can('api_keys') || (hasModule('webhooks') && can('webhooks')), keywords: 'api keys webhooks integrations' },
    { to: '/settings', label: 'Settings', icon: 'settings', any: () => can('settings') || isAdmin() || (hasModule('orders') && can('orders', 'manage')) || (hasModule('payments') && can('payments', 'manage')) || (hasModule('customer_accounts') && can('customers', 'manage')) || (hasModule('maps') && can('maps', 'manage')) || (hasModule('push_notifications') && can('notifications', 'manage')), keywords: 'branding logo email modules checkout tax payments maps qr promptpay' },
    { to: '/audit', label: 'Activity log', icon: 'audit', perm: 'audit_logs', keywords: 'audit history' },
  ] },
]

export const visible = (i: NavItem): boolean =>
  hasModule(i.module) && (!i.perm || can(i.perm)) && (!i.admin || isAdmin()) && (!i.any || i.any())

export function tenantNav(): NavGroup[] {
  return tenantGroups.map((g) => ({ ...g, items: g.items.filter(visible) })).filter((g) => g.items.length)
}

export const platformNav: NavGroup[] = [
  { title: 'Platform', items: [
    { to: '/platform', label: 'Workspaces', icon: 'tenants', keywords: 'tenants modules offboard export' },
    { to: '/platform/admins', label: 'Platform admins', icon: 'shield', keywords: 'super admins mfa' },
  ] },
  { title: 'Operations', items: [
    { to: '/platform/jobs', label: 'Background jobs', icon: 'activity', keywords: 'queue dead retry' },
    { to: '/platform/backups', label: 'Backups', icon: 'archive', keywords: 'restore pg_dump' },
    { to: '/platform/audit', label: 'Activity log', icon: 'audit', keywords: 'audit history' },
  ] },
  { title: 'Configuration', items: [
    { to: '/platform/email', label: 'Email', icon: 'mail', keywords: 'smtp templates outbox' },
    { to: '/platform/storage', label: 'File storage', icon: 'server', keywords: 's3 r2 local' },
    { to: '/platform/settings', label: 'General', icon: 'settings', keywords: 'platform name public url' },
  ] },
]
