export type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'brand'

export function money(v: string | number | null | undefined, currency = 'USD'): string {
  if (v === null || v === undefined || v === '') return '—'
  try { return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(Number(v)) }
  catch { return `${Number(v).toFixed(2)} ${currency}` }
}
export function bytes(n: number | null | undefined): string {
  if (n === null || n === undefined) return '—'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0; let v = n
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${v < 10 && i ? v.toFixed(1) : Math.round(v)} ${u[i]}`
}
/** Today in local time as YYYY-MM-DD, offset by `days`. */
export function isoDay(days = 0): string {
  const d = new Date(); d.setDate(d.getDate() + days)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export const number = (v: number | null | undefined) => (v === null || v === undefined ? '—' : new Intl.NumberFormat().format(v))

export function when(isoString: string | null | undefined): string {
  if (!isoString) return '—'
  const d = new Date(isoString), now = new Date()
  const time = d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false })
  const days = Math.floor((new Date(now.toDateString()).getTime() - new Date(d.toDateString()).getTime()) / 86_400_000)
  if (days === 0) return `Today, ${time}`
  if (days === 1) return `Yesterday, ${time}`
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) + `, ${time}`
}

export const title = (s: string) => s.charAt(0).toUpperCase() + s.slice(1).replace(/_/g, ' ')

export const tones: Record<string, Tone> = {
  // generic states
  active: 'success', draft: 'neutral', archived: 'neutral', suspended: 'neutral', offboarding: 'danger', inactive: 'neutral',
  queued: 'neutral', running: 'info', done: 'success', dead: 'danger', failed: 'danger', succeeded: 'success', pending: 'warning',
  sent: 'success', cancelled: 'neutral', canceled: 'neutral', expired: 'neutral', revoked: 'neutral',
  // payments
  unpaid: 'warning', partially_paid: 'info', paid: 'success', partially_refunded: 'info', refunded: 'neutral',
  // purchase orders
  ordered: 'info', partially_received: 'info', received: 'success', closed: 'neutral',
  // returns
  requested: 'warning', approved: 'info', rejected: 'neutral',
  // deliveries
  assigned: 'info', picked_up: 'brand', in_transit: 'brand', delivered: 'success',
  // imports
  uploaded: 'neutral', validating: 'info', importing: 'info', completed: 'success', completed_with_errors: 'warning',
}
export const tone = (s: string | null | undefined): Tone => (s ? tones[s] ?? 'neutral' : 'neutral')

export function dateTime(isoString: string | null | undefined): string {
  if (!isoString) return '—'
  return new Date(isoString).toLocaleString('en-US', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false })
}
export const shortId = (id: string | null | undefined) => (id ? id.slice(0, 8) : '—')
export const isEmail = (s: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)
export const isMoney = (s: string) => /^\d{1,10}(\.\d{1,2})?$/.test(s.trim())
