import { clearSession, saveSession, session } from './session'

// A thin fetch wrapper for the TenantCore API: bearer auth, one shared token refresh on 401,
// typed errors that carry the server's code, message, request id and field details.

export const API_BASE = '/api/v1'

export interface FieldIssue { loc: string[]; msg: string }
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId?: string, public details?: unknown) {
    super(message)
  }
  /** Validation issues keyed by field name (the last part of loc), for showing next to inputs. */
  get fields(): Record<string, string> {
    const out: Record<string, string> = {}
    if (Array.isArray(this.details)) for (const d of this.details as FieldIssue[]) if (d?.loc && d.msg) out[d.loc[d.loc.length - 1]] = d.msg
    return out
  }
  /** The message plus each field problem, for a toast. */
  get full(): string {
    const f = Object.entries(this.fields)
    return f.length ? `${this.message} ${f.map(([k, v]) => `${k.replace(/_/g, ' ')}: ${v}`).join('; ')}` : this.message
  }
}

type Query = Record<string, string | number | boolean | null | undefined>
interface Options { query?: Query; body?: unknown; form?: FormData; headers?: Record<string, string>; raw?: boolean; auth?: boolean }

let refreshing: Promise<boolean> | null = null
/** Listeners called when the session ends (expired refresh token, account disabled). */
export const onSignedOut: Array<(reason: string) => void> = []

function qs(q?: Query): string {
  if (!q) return ''
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(q)) if (v !== undefined && v !== null && v !== '') p.set(k, String(v))
  const s = p.toString()
  return s ? `?${s}` : ''
}

async function refresh(): Promise<boolean> {
  // Only workspace sessions can refresh; platform sessions sign in again (shorter, MFA-protected).
  if (session.audience !== 'tenant' || !session.refresh) return false
  refreshing ??= (async () => {
    try {
      const r = await fetch(`${API_BASE}/auth/refresh`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refresh_token: session.refresh }),
      })
      if (!r.ok) return false
      const t = await r.json()
      saveSession({ access: t.access_token, refresh: t.refresh_token })
      return true
    } catch { return false } finally { setTimeout(() => { refreshing = null }, 0) }
  })()
  return refreshing
}

async function toError(r: Response): Promise<ApiError> {
  let body: any = null
  try { body = await r.json() } catch { /* not JSON */ }
  const e = body?.error
  if (e) return new ApiError(r.status, e.code ?? 'error', e.message ?? r.statusText, e.request_id, e.details)
  const msg = r.status === 413 ? 'The file is too large.' : r.status >= 500 ? 'The server had a problem. Try again in a moment.' : r.statusText || 'Request failed.'
  return new ApiError(r.status, 'http_' + r.status, msg)
}

export async function request<T = any>(method: string, path: string, o: Options = {}, retried = false): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...o.headers }
  if (o.auth !== false && session.access) headers.Authorization = `Bearer ${session.access}`
  let body: BodyInit | undefined
  if (o.form) body = o.form
  else if (o.body !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(o.body) }
  let r: Response
  try {
    r = await fetch(API_BASE + path + qs(o.query), { method, headers, body })
  } catch {
    throw new ApiError(0, 'network', 'Cannot reach the server. Check your connection.')
  }
  if (r.status === 401 && o.auth !== false && session.access && !retried) {
    if (await refresh()) return request<T>(method, path, o, true)
    const err = await toError(r)
    clearSession()
    onSignedOut.forEach((f) => f(err.message))
    throw err
  }
  if (!r.ok) throw await toError(r)
  if (o.raw) return r as unknown as T
  if (r.status === 204) return undefined as T
  const ct = r.headers.get('content-type') ?? ''
  return (ct.includes('json') ? r.json() : r.text()) as Promise<T>
}

export const api = {
  get: <T = any>(path: string, query?: Query) => request<T>('GET', path, { query }),
  post: <T = any>(path: string, body?: unknown, headers?: Record<string, string>) => request<T>('POST', path, { body, headers }),
  put: <T = any>(path: string, body?: unknown) => request<T>('PUT', path, { body }),
  patch: <T = any>(path: string, body?: unknown) => request<T>('PATCH', path, { body }),
  del: <T = any>(path: string, query?: Query) => request<T>('DELETE', path, { query }),
  upload: <T = any>(path: string, form: FormData) => request<T>('POST', path, { form }),
  /** Unauthenticated call (sign-in, password reset, invitations). */
  open: <T = any>(method: string, path: string, body?: unknown) => request<T>(method, path, { body, auth: false }),
  /** Fetch a protected file and hand it to the browser as a download. */
  async download(path: string, fallbackName: string, query?: Query): Promise<void> {
    const r = await request<Response>('GET', path, { query, raw: true, headers: { Accept: '*/*' } })
    const cd = r.headers.get('content-disposition') ?? ''
    const m = /filename\*=UTF-8''([^;]+)/i.exec(cd) ?? /filename="?([^";]+)"?/i.exec(cd)
    const name = m ? decodeURIComponent(m[1]) : fallbackName
    const url = URL.createObjectURL(await r.blob())
    const a = Object.assign(document.createElement('a'), { href: url, download: name })
    document.body.appendChild(a); a.click(); a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 2000)
  },
  /** Fetch a protected image as an object URL (for previews); revoke it when done. */
  async blobUrl(path: string, query?: Query): Promise<string> {
    const r = await request<Response>('GET', path, { query, raw: true, headers: { Accept: '*/*' } })
    return URL.createObjectURL(await r.blob())
  },
}

/** A random key for the Idempotency-Key header, so a retried create never runs twice. */
export const idemKey = () => (crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).slice(2) + Date.now().toString(36))
