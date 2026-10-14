import { reactive } from 'vue'

// App-wide feedback: toasts, a promise-based confirm dialog, and small browser helpers.

export interface Toast { id: number; tone: 'ok' | 'err'; text: string }
export const toasts = reactive<Toast[]>([])
let seq = 0

export function dismiss(id: number): void {
  const i = toasts.findIndex((t) => t.id === id)
  if (i >= 0) toasts.splice(i, 1)
}

export function toast(text: string, tone: Toast['tone'] = 'ok'): void {
  const id = ++seq
  toasts.push({ id, tone, text })
  if (toasts.length > 4) toasts.shift()
  setTimeout(() => dismiss(id), tone === 'err' ? 8000 : 4000)
}

export const errText = (e: unknown, fallback = 'Something went wrong.'): string =>
  e instanceof Error && e.message ? e.message : fallback

export const fail = (e: unknown): void => toast(errText(e), 'err')

interface ConfirmOptions { title: string; body?: string; confirmLabel?: string; danger?: boolean; typeToConfirm?: string }
export const confirmState = reactive({
  open: false, title: '', body: '', confirmLabel: 'Confirm', danger: false, typeToConfirm: '', typed: '',
  resolve: (_ok: boolean) => {},
})

/** Ask the user to confirm an action. Resolves true when they confirm. */
export function ask(o: ConfirmOptions): Promise<boolean> {
  confirmState.resolve(false) // settle any dialog that is still open
  return new Promise((resolve) => {
    Object.assign(confirmState, {
      open: true, title: o.title, body: o.body ?? '', confirmLabel: o.confirmLabel ?? 'Confirm', danger: !!o.danger,
      typeToConfirm: o.typeToConfirm ?? '', typed: '',
      resolve: (ok: boolean) => { confirmState.open = false; confirmState.resolve = () => {}; resolve(ok) },
    })
  })
}

export function download(filename: string, content: string, type = 'text/plain'): void {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const a = Object.assign(document.createElement('a'), { href: url, download: filename })
  document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

const csvCell = (v: unknown): string => {
  if (v === null || v === undefined) return ''
  let s = typeof v === 'object' ? JSON.stringify(v) : String(v)
  if (/^[=+\-@\t\r]/.test(s)) s = `'${s}` // stop spreadsheet formula injection
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

export function toCsv(rows: Record<string, unknown>[], columns?: string[]): string {
  const cols = columns ?? [...new Set(rows.flatMap((r) => Object.keys(r)))]
  return [cols.join(','), ...rows.map((r) => cols.map((c) => csvCell(r[c])).join(','))].join('\r\n') + '\r\n'
}

export async function copy(text: string): Promise<void> {
  try { await navigator.clipboard.writeText(text); toast('Copied to clipboard.') }
  catch { toast('Copy failed: select the text and copy it manually.', 'err') }
}
