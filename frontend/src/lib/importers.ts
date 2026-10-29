import { parseCsv } from './export'

/** Rows read from a file for "Import data" (DBeaver data transfer: Files → Table). */
export interface ImportTable {
  header: string[]
  /** null = SQL NULL */
  records: (string | null)[][]
}

function cell(value: unknown): string | null {
  if (value === null || value === undefined) return null
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

/** JSON: an array of objects, or an object holding one under rows/data/items/records. */
export function parseJsonRows(text: string): ImportTable {
  const parsed: unknown = JSON.parse(text)
  const container = parsed as Record<string, unknown>
  const rows = Array.isArray(parsed)
    ? parsed
    : ['rows', 'data', 'items', 'records'].map((k) => container?.[k]).find(Array.isArray)
  if (!Array.isArray(rows)) throw new Error('JSON must be an array of objects (or have one under "rows" / "data")')
  const header: string[] = []
  for (const row of rows) {
    if (row === null || typeof row !== 'object' || Array.isArray(row)) throw new Error('Every JSON row must be an object')
    for (const key of Object.keys(row)) if (!header.includes(key)) header.push(key)
  }
  return { header, records: rows.map((row) => header.map((h) => cell((row as Record<string, unknown>)[h]))) }
}

/**
 * XML: each child of the root element is a row; each child of a row is a column (tag name, or its
 * `name` attribute when the column name is not a valid tag). `null="true"` marks NULL. This is the
 * format JustinBeaver's XML export writes.
 */
export function parseXmlRows(text: string): ImportTable {
  const doc = new DOMParser().parseFromString(text, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length) throw new Error('The XML file is not well-formed')
  const header: string[] = []
  const rows: Map<string, string | null>[] = []
  for (const rowEl of Array.from(doc.documentElement.children)) {
    const row = new Map<string, string | null>()
    for (const colEl of Array.from(rowEl.children)) {
      const name = colEl.getAttribute('name') ?? colEl.tagName
      if (!header.includes(name)) header.push(name)
      row.set(name, colEl.getAttribute('null') === 'true' ? null : (colEl.textContent ?? ''))
    }
    rows.push(row)
  }
  return { header, records: rows.map((row) => header.map((h) => (row.has(h) ? row.get(h)! : null))) }
}

/** CSV/TSV with a header row; empty fields are NULL. */
export function parseCsvRows(text: string): ImportTable {
  const [header, ...records] = parseCsv(text)
  if (!header) return { header: [], records: [] }
  return { header, records: records.map((r) => header.map((_, i) => (r[i] === undefined || r[i] === '' ? null : r[i]!))) }
}

export function parseImport(fileName: string, text: string): ImportTable {
  const lower = fileName.toLowerCase()
  const start = text.trimStart()[0]
  if (lower.endsWith('.json') || (!lower.endsWith('.csv') && (start === '[' || start === '{'))) return parseJsonRows(text)
  if (lower.endsWith('.xml') || (!lower.endsWith('.csv') && start === '<')) return parseXmlRows(text)
  return parseCsvRows(text)
}
