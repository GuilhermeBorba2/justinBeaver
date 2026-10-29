/** Recently applied data-tab filters, newest first, kept per table so they can be picked again (DBeaver's filter history). */
const LIMIT = 15

export function pushFilter(list: string[], filter: string): string[] {
  const f = filter.trim()
  if (!f) return list
  return [f, ...list.filter((x) => x !== f)].slice(0, LIMIT)
}

const key = (id: string) => `jb.filters.${id}`

export function loadFilters(id: string): string[] {
  try {
    const raw = JSON.parse(localStorage.getItem(key(id)) ?? '[]')
    return Array.isArray(raw) ? raw.filter((x): x is string => typeof x === 'string').slice(0, LIMIT) : []
  } catch {
    return []
  }
}

export function saveFilters(id: string, list: string[]): void {
  try {
    localStorage.setItem(key(id), JSON.stringify(list))
  } catch {
    // storage unavailable: history just is not kept
  }
}
