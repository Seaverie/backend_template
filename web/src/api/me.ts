import { reactive } from 'vue'
import { api } from './client'
import { session } from './session'

// Who is signed in: modules, permissions and branding of the workspace, or the platform admin.
export interface TenantSettings {
  display_name: string | null; primary_color: string | null; currency: string; timezone: string; locale: string
  extra: Record<string, unknown>; logo_file_id: string | null; logo_url: string | null; updated_at?: string
}
export interface TenantMe {
  kind: 'user'; tenant: { id: string; slug: string; name: string }; user: { id: string; email: string; full_name: string | null }
  tenant_id: string; user_id: string; is_tenant_admin: boolean; modules: string[]; permissions: string[]; settings: TenantSettings
}
export interface PlatformMe { kind: 'platform'; id: string; email: string; full_name: string | null; mfa_enabled: boolean; mfa_verified: boolean; mfa_required: boolean }

export const me = reactive<{ tenant: TenantMe | null; platform: PlatformMe | null }>({ tenant: null, platform: null })

export async function loadMe(): Promise<void> {
  if (session.audience === 'platform') { me.platform = await api.get<PlatformMe>('/platform/auth/me'); me.tenant = null }
  else if (session.audience === 'tenant') { me.tenant = await api.get<TenantMe>('/auth/me'); me.platform = null; applyBrand() }
}
export function forgetMe(): void { me.tenant = null; me.platform = null; applyBrand() }

/** Workspace modules switched on by the platform admin. */
export const hasModule = (code?: string | null): boolean => !code || !!me.tenant?.modules.includes(code)
export const isAdmin = (): boolean => !!me.tenant?.is_tenant_admin

/** Mirrors the server: admins can do everything; `manage` implies `read`. The server still checks every call. */
export function can(resource: string, action = 'read'): boolean {
  const t = me.tenant
  if (!t) return false
  if (t.is_tenant_admin || t.permissions.includes('*')) return true
  return t.permissions.includes(`${resource}:${action}`) || (action === 'read' && t.permissions.includes(`${resource}:manage`))
}

export const currency = (): string => me.tenant?.settings.currency ?? 'USD'

/** Uses the workspace colour for buttons and highlights. */
export function applyBrand(): void {
  const c = me.tenant?.settings.primary_color
  const root = document.documentElement.style
  if (c && /^#[0-9a-fA-F]{6}$/.test(c)) {
    root.setProperty('--brand-primary', c)
    root.setProperty('--brand-primary-hover', `color-mix(in srgb, ${c} 85%, black)`)
    root.setProperty('--brand-soft', `color-mix(in srgb, ${c} 10%, white)`)
  } else ['--brand-primary', '--brand-primary-hover', '--brand-soft'].forEach((p) => root.removeProperty(p))
  document.title = me.tenant ? `${me.tenant.settings.display_name || me.tenant.tenant.name} · Dashboard` : 'Dashboard'
}
