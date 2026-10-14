export type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral' | 'brand'

export const money = (v: string | number, currency = 'USD') =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(Number(v))

export const number = (v: number) => new Intl.NumberFormat('en-US').format(v)

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

export const orderTone: Record<string, Tone> = { pending: 'warning', paid: 'info', shipped: 'brand', delivered: 'success', cancelled: 'neutral' }
export const deliveryTone: Record<string, Tone> = { succeeded: 'success', pending: 'warning', dead: 'danger' }
export const tenantTone: Record<string, Tone> = { active: 'success', suspended: 'neutral', offboarding: 'danger' }
export const rmaTone: Record<string, Tone> = { requested: 'warning', approved: 'info', received: 'brand', refunded: 'success', rejected: 'neutral', closed: 'neutral' }
export const taskTone: Record<string, Tone> = { queued: 'neutral', running: 'info', succeeded: 'success', failed: 'danger' }

export function dateTime(isoString: string | null | undefined): string {
  if (!isoString) return '—'
  return new Date(isoString).toLocaleString('en-US', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false })
}
export const shortId = (id: string | null | undefined) => (id ? id.slice(0, 8) : '—')
export const isEmail = (s: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)
export const isMoney = (s: string) => /^\d{1,10}(\.\d{1,2})?$/.test(s.trim())
