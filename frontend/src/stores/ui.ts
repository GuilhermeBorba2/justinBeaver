import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface Toast {
  id: number
  kind: 'error' | 'info'
  text: string
}

export const useUi = defineStore('ui', () => {
  const toasts = ref<Toast[]>([])
  let nextId = 1

  function push(kind: Toast['kind'], text: string, ms: number) {
    const id = nextId++
    toasts.value.push({ id, kind, text })
    setTimeout(() => dismiss(id), ms)
  }

  function dismiss(id: number) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  const error = (text: string) => push('error', text, 8000)
  const info = (text: string) => push('info', text, 3500)

  /** Run `work`; show failures as a toast instead of throwing. Returns undefined on failure. */
  async function guard<T>(work: () => Promise<T>): Promise<T | undefined> {
    try {
      return await work()
    } catch (e) {
      error(e instanceof Error ? e.message : String(e))
      return undefined
    }
  }

  return { toasts, error, info, dismiss, guard }
})
