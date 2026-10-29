import type { CellValue, ResultColumn, RowChange } from '@/api/types'

/** A grid cell as displayed: `undefined` marks a column of a new row the user has not set (DB default). */
export type DisplayCell = CellValue | undefined

/** Unsaved grid edits for one page of a table. Row indexes refer to the loaded page. */
export interface PendingEdits {
  /** row index -> new values of the cells changed in that row */
  updates: Map<number, Record<string, CellValue>>
  deletes: Set<number>
  /** new rows; only the columns the user set (others take the column default) */
  inserts: Record<string, CellValue>[]
}

export function emptyEdits(): PendingEdits {
  return { updates: new Map(), deletes: new Set(), inserts: [] }
}

function same(a: CellValue | undefined, b: CellValue | undefined): boolean {
  if (a === null || b === null || a === undefined || b === undefined) return a === b
  return String(a) === String(b)
}

/**
 * Record an edit of cell (row, column). `row` beyond the page rows addresses an inserted row.
 * Setting a cell back to its original value drops the change.
 */
export function setCell(
  edits: PendingEdits,
  rows: CellValue[][],
  columns: ResultColumn[],
  row: number,
  col: number,
  value: CellValue,
): void {
  const name = columns[col]!.name
  if (row >= rows.length) {
    const inserted = edits.inserts[row - rows.length]
    if (inserted) inserted[name] = value
    return
  }
  const original = rows[row]![col]
  const changes = { ...(edits.updates.get(row) ?? {}) }
  if (same(original, value)) delete changes[name]
  else changes[name] = value
  if (Object.keys(changes).length) edits.updates.set(row, changes)
  else edits.updates.delete(row)
}

export function addRow(edits: PendingEdits): void {
  edits.inserts.push({})
}

/**
 * "Duplicate row": a new row with the displayed values of `row`, except primary-key columns
 * (left to the database default / sequence, as a duplicate key would fail anyway).
 */
export function duplicateRow(
  edits: PendingEdits,
  rows: CellValue[][],
  columns: ResultColumn[],
  row: number,
  primaryKey: string[],
): void {
  const shown = displayRows(edits, rows, columns)[row]
  if (!shown) return
  const values: Record<string, CellValue> = {}
  columns.forEach((c, i) => {
    const v = shown[i]
    if (!primaryKey.includes(c.name) && v !== undefined) values[c.name] = v
  })
  edits.inserts.push(values)
}

/** Delete a loaded row (toggles), or drop an inserted one. */
export function toggleDelete(edits: PendingEdits, rowCount: number, row: number): void {
  if (row >= rowCount) {
    edits.inserts.splice(row - rowCount, 1)
  } else if (edits.deletes.has(row)) {
    edits.deletes.delete(row)
  } else {
    edits.deletes.add(row)
  }
}

export function changeCount(edits: PendingEdits): number {
  let updates = 0
  for (const row of edits.updates.keys()) if (!edits.deletes.has(row)) updates++
  return updates + edits.deletes.size + edits.inserts.length
}

/** The page as the user sees it: edits applied, new rows appended. */
export function displayRows(edits: PendingEdits, rows: CellValue[][], columns: ResultColumn[]): DisplayCell[][] {
  const shown: DisplayCell[][] = rows.map((cells, r) => {
    const changes = edits.updates.get(r)
    return changes ? cells.map((v, c) => (columns[c]!.name in changes ? changes[columns[c]!.name]! : v)) : cells
  })
  for (const inserted of edits.inserts) {
    shown.push(columns.map((c) => (c.name in inserted ? inserted[c.name]! : undefined)))
  }
  return shown
}

export function isChanged(edits: PendingEdits, rowCount: number, columns: ResultColumn[], row: number, col: number): boolean {
  if (row >= rowCount) return columns[col]!.name in (edits.inserts[row - rowCount] ?? {})
  return columns[col]!.name in (edits.updates.get(row) ?? {})
}

/**
 * Changes for the server, keyed by the original primary-key values. Order: deletes, updates,
 * inserts, so a save can delete a row and insert one with the same key.
 */
export function buildChanges(
  edits: PendingEdits,
  rows: CellValue[][],
  columns: ResultColumn[],
  primaryKey: string[],
): RowChange[] {
  const index = new Map(columns.map((c, i) => [c.name, i]))
  const keyOf = (row: number) => Object.fromEntries(primaryKey.map((k) => [k, rows[row]![index.get(k)!]!]))
  const changes: RowChange[] = []
  for (const row of [...edits.deletes].sort((a, b) => a - b)) {
    changes.push({ op: 'DELETE', key: keyOf(row) })
  }
  for (const [row, values] of [...edits.updates.entries()].sort(([a], [b]) => a - b)) {
    if (!edits.deletes.has(row) && Object.keys(values).length) changes.push({ op: 'UPDATE', key: keyOf(row), values })
  }
  for (const values of edits.inserts) {
    if (Object.keys(values).length) changes.push({ op: 'INSERT', values })
  }
  return changes
}
