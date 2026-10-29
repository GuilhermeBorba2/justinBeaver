import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

/** DBeaver's "Query Manager": every statement run from any editor, newest first, with its outcome and time. */
export interface QueryLogEntry {
  id: number
  at: number
  connectionId: string
  sql: string
  ok: boolean
  elapsedMs: number | null
  /** "12 row(s)", "3 row(s) affected" or the error */
  summary: string
}

const KEY = 'justinbeaver.querylog'
const MAX = 1000

function restore(): QueryLogEntry[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    return Array.isArray(parsed) ? (parsed as QueryLogEntry[]).slice(0, MAX) : []
  } catch {
    return []
  }
}

export const useQueryLog = defineStore('querylog', () => {
  const entries = ref<QueryLogEntry[]>(restore())
  let nextId = (entries.value[0]?.id ?? 0) + 1

  let timer: ReturnType<typeof setTimeout> | undefined
  watch(
    entries,
    () => {
      clearTimeout(timer)
      timer = setTimeout(() => {
        try {
          localStorage.setItem(KEY, JSON.stringify(entries.value))
        } catch {
          // full storage: the log stays in memory
        }
      }, 500)
    },
    { deep: true },
  )

  function add(entry: Omit<QueryLogEntry, 'id' | 'at'>) {
    entries.value.unshift({ ...entry, id: nextId++, at: Date.now() })
    if (entries.value.length > MAX) entries.value.length = MAX
  }

  function clear() {
    entries.value = []
  }

  return { entries, add, clear }
})
