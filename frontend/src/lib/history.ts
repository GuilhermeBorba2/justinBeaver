/** "Show execution log": the statements run per connection, kept in this browser. */
export interface HistoryEntry {
  sql: string
  at: number
  ok: boolean
  elapsedMs: number | null
  summary: string
}

const MAX_ENTRIES = 300
const key = (connectionId: string) => `justinbeaver.history.${connectionId}`

export function readHistory(connectionId: string): HistoryEntry[] {
  try {
    const raw = localStorage.getItem(key(connectionId))
    return raw ? (JSON.parse(raw) as HistoryEntry[]) : []
  } catch {
    return []
  }
}

/** Newest first; an identical statement moves to the top instead of repeating. */
export function addHistory(connectionId: string, entry: HistoryEntry): HistoryEntry[] {
  const entries = [entry, ...readHistory(connectionId).filter((e) => e.sql !== entry.sql)].slice(0, MAX_ENTRIES)
  try {
    localStorage.setItem(key(connectionId), JSON.stringify(entries))
  } catch {
    // storage full or unavailable: history is a convenience only
  }
  return entries
}

export function clearHistory(connectionId: string): void {
  try {
    localStorage.removeItem(key(connectionId))
  } catch {
    // ignore
  }
}
