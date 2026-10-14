import { onMounted, ref } from 'vue'
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
