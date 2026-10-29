import type { CellValue, ResultColumn } from '@/api/types'
import type { CellRange } from './calc'
import type { DisplayCell } from './edits'
import { sqlLiteral } from './export'

/** The part of a result a "Copy" command works on: some columns and some rows, as plain values. */
export interface Slice {
  columns: ResultColumn[]
  rows: CellValue[][]
}

/** The cells of a rectangle (hidden columns left out); a cell that is unset (DEFAULT) counts as NULL. */
export function sliceOf(columns: ResultColumn[], rows: DisplayCell[][], range: CellRange, shown: readonly number[]): Slice {
  const cols = shown.filter((c) => c >= range.c1 && c <= range.c2)
  const out: CellValue[][] = []
  for (let r = range.r1; r <= range.r2; r++) {
    const row = rows[r]
    if (row) out.push(cols.map((c) => row[c] ?? null))
  }
  return { columns: cols.map((c) => columns[c]!), rows: out }
}

/** Every value of the slice, row by row, as plain text; NULL as empty. */
export function valuesOf(slice: Slice): string[] {
  return slice.rows.flatMap((row) => row.map((v) => (v === null ? '' : String(v))))
}

/** One value per line (a column pasted into a chat, a file of ids). */
export function linesOf(slice: Slice): string {
  return valuesOf(slice).join('\n')
}

/** `('a', 'b', 3)`: the distinct non-NULL values written as SQL, ready for `WHERE x IN …`. */
export function inList(slice: Slice): string {
  const seen = new Set<string>()
  const items: string[] = []
  for (const row of slice.rows) {
    row.forEach((value, i) => {
      if (value === null) return
      const literal = sqlLiteral(value, slice.columns[i]!)
      if (!seen.has(literal)) {
        seen.add(literal)
        items.push(literal)
      }
    })
  }
  return `(${items.join(', ')})`
}

/** The column names: `a, b, c`, or with `quote` the SQL identifiers `"a", "b", "c"`. */
export function names(slice: Slice, quote = false): string {
  return slice.columns.map((c) => (quote ? `"${c.name.replaceAll('"', '""')}"` : c.name)).join(', ')
}

/** Tab-separated values with the column names on the first line: pastes into a spreadsheet as a table. */
export function withHeaders(slice: Slice): string {
  const cell = (v: CellValue) => (v === null ? '' : String(v).replace(/[\t\r\n]+/g, ' '))
  return [slice.columns.map((c) => cell(c.name)).join('\t'), ...slice.rows.map((r) => r.map(cell).join('\t'))].join('\n')
}
