import { onMounted, reactive, ref, watch } from 'vue'
import { errText, toast } from './ui'

/** Load data on mount; exposes loading/error state and a reload function. */
export function useLoader(fn: () => Promise<unknown>, immediate = true) {
  const loading = ref(false)
  const error = ref('')
  async function run(): Promise<void> {
    loading.value = true; error.value = ''
    try { await fn() } catch (e) { error.value = errText(e, 'Could not load this page.') } finally { loading.value = false }
  }
  if (immediate) onMounted(run)
  return { loading, error, reload: run }
}

/** Run a user action: tracks which button is busy, toasts the outcome, returns true on success. */
export function useBusy() {
  const busy = ref('')
  async function run(key: string, fn: () => Promise<unknown>, success?: string): Promise<boolean> {
    busy.value = key
    try { await fn(); if (success) toast(success); return true }
    catch (e) { toast(errText(e), 'err'); return false }
    finally { busy.value = '' }
  }
  return { busy, run }
}

/**
 * A paged, filterable list from an endpoint returning {items, total?}. Changing a filter resets to the
 * first page; typing in `q` waits briefly before loading.
 */
type Filters = Record<string, string | number | boolean>
export function useList<F extends Filters = Filters, T = any>(fetcher: (q: F & { limit: number; offset: number }) => Promise<{ items: any[]; total?: number }>,
                                                        filters: F = {} as F, limit = 50) {
  const items = ref<T[]>([]) as { value: T[] }
  const total = ref<number | null>(null)
  const offset = ref(0)
  const f = reactive({ ...filters }) as F
  const { loading, error, reload } = useLoader(async () => {
    const r = await fetcher({ ...f, limit, offset: offset.value })
    items.value = r.items; total.value = r.total ?? null
  })
  let timer: ReturnType<typeof setTimeout> | undefined
  watch(() => ({ ...f }) as Filters, (now, before) => {
    clearTimeout(timer)
    const typed = now.q !== before?.q
    timer = setTimeout(() => { if (offset.value) offset.value = 0; else reload() }, typed ? 300 : 0)
  })
  watch(offset, () => reload())
  return { items, total, offset, filters: f, limit, loading, error, reload }
}

/** Read and parse a number from a text input; empty → null. */
export const num = (s: string | number | null | undefined): number | null => {
  if (s === null || s === undefined) return null
  const t = String(s).trim()
  if (!t) return null
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}
/** Text input value for a number; null → ''. */
export const str = (n: number | string | null | undefined): string => (n === null || n === undefined ? '' : String(n))
/** '' → null, for optional text fields. */
export const opt = (s: string | null | undefined): string | null => (s && s.trim() ? s.trim() : null)
