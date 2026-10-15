import { reactive } from 'vue'

// Where the signed-in user is: a workspace (tenant) or the platform console. Tokens live in
// sessionStorage (cleared when the tab closes), never in localStorage.
export type Audience = 'tenant' | 'platform'
interface SessionState { audience: Audience | null; tenant: string | null; email: string | null; access: string | null; refresh: string | null }

const KEY = 'tc.session'
const empty = (): SessionState => ({ audience: null, tenant: null, email: null, access: null, refresh: null })
function load(): SessionState {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (raw) return { ...empty(), ...(JSON.parse(raw) as SessionState) }
  } catch { /* storage unavailable: start signed out */ }
  return empty()
}

export const session = reactive<SessionState>(load())

export function saveSession(next: Partial<SessionState>): void {
  Object.assign(session, next)
  try { sessionStorage.setItem(KEY, JSON.stringify(session)) } catch { /* ignore */ }
}

export function clearSession(): void {
  const tenant = session.tenant // keep the workspace name for the next sign-in
  saveSession({ ...empty(), tenant })
}
