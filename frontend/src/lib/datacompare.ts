import type { CellValue, ResultColumn } from '@/api/types'
import { q, qualified, type DdlContext, type TableRef } from './ddl'
import { equalsCondition } from './filters'

/**
 * Data compare of two tables by key (DBeaver PRO's "Data compare"): rows only on the left, only on the
 * right, and rows whose values differ. Columns are matched by name, case-insensitively; values are compared
 * as text, so 1 and '1' are equal across databases.
 */
export interface RowDiff {
  key: CellValue[]
  diffs: { column: string; left: CellValue; right: CellValue }[]
}

export interface DataCompareResult {
  /** names of the compared columns (left spelling) */
  columns: string[]
  keyColumns: string[]
  onlyLeft: CellValue[][]
  onlyRight: CellValue[][]
  changed: RowDiff[]
  /** full left rows of `changed`, for the sync script */
  changedRows: CellValue[][]
  same: number
}

const text = (v: CellValue | undefined) => (v === null || v === undefined ? null : String(v))

export function compareData(
  left: { columns: ResultColumn[]; rows: CellValue[][] },
  right: { columns: ResultColumn[]; rows: CellValue[][] },
  keyColumns: string[],
): DataCompareResult {
  const rightIndex = new Map(right.columns.map((c, i) => [c.name.toLowerCase(), i]))
  const common = left.columns.flatMap((c, i) => {
    const r = rightIndex.get(c.name.toLowerCase())
    return r === undefined ? [] : [{ name: c.name, left: i, right: r }]
  })
  const keys = keyColumns.map((k) => {
    const col = common.find((c) => c.name.toLowerCase() === k.toLowerCase())
    if (!col) throw new Error(`Key column ${k} is not on both tables`)
    return col
  })
  if (keys.length === 0) throw new Error('Pick the key columns that identify a row')
  const keyOf = (row: CellValue[], side: 'left' | 'right') => JSON.stringify(keys.map((k) => text(row[k[side]])))

  const rightRows = new Map<string, CellValue[]>()
  for (const row of right.rows) rightRows.set(keyOf(row, 'right'), row)

  const result: DataCompareResult = {
    columns: common.map((c) => c.name),
    keyColumns: keys.map((k) => k.name),
    onlyLeft: [],
    onlyRight: [],
    changed: [],
    changedRows: [],
    same: 0,
  }
  const seen = new Set<string>()
  for (const row of left.rows) {
    const key = keyOf(row, 'left')
    seen.add(key)
    const other = rightRows.get(key)
    if (!other) {
      result.onlyLeft.push(common.map((c) => row[c.left] ?? null))
      continue
    }
    const diffs = common
      .filter((c) => text(row[c.left]) !== text(other[c.right]))
      .map((c) => ({ column: c.name, left: row[c.left] ?? null, right: other[c.right] ?? null }))
    if (diffs.length) {
      result.changed.push({ key: keys.map((k) => row[k.left] ?? null), diffs })
      result.changedRows.push(common.map((c) => row[c.left] ?? null))
    } else {
      result.same++
    }
  }
  for (const [key, row] of rightRows) {
    if (!seen.has(key)) result.onlyRight.push(common.map((c) => row[c.right] ?? null))
  }
  return result
}

function literal(value: CellValue, kind: string): string {
  if (value === null) return 'NULL'
  if (typeof value === 'number' || typeof value === 'boolean' || (kind === 'number' && /^-?\d+(\.\d+)?$/.test(String(value)))) {
    return String(value)
  }
  return `'${String(value).replaceAll("'", "''")}'`
}

/** Statements that make the right table equal to the left one (insert missing, update changed, delete extra). */
export function syncScript(
  ctx: DdlContext,
  target: TableRef,
  result: DataCompareResult,
  kinds: Record<string, string>,
  deleteExtra: boolean,
): string[] {
  const t = qualified(ctx, target)
  const kind = (c: string) => kinds[c] ?? 'text'
  const where = (row: CellValue[]) =>
    result.keyColumns.map((k) => equalsCondition(ctx, k, kind(k), row[result.columns.indexOf(k)] ?? null)).join(' AND ')
  const statements: string[] = []
  const names = result.columns.map((c) => q(ctx, c)).join(', ')
  for (const row of result.onlyLeft) {
    statements.push(`INSERT INTO ${t} (${names}) VALUES (${row.map((v, i) => literal(v, kind(result.columns[i]!))).join(', ')})`)
  }
  result.changed.forEach((diff, i) => {
    const sets = diff.diffs.map((d) => `${q(ctx, d.column)} = ${literal(d.left, kind(d.column))}`).join(', ')
    statements.push(`UPDATE ${t} SET ${sets} WHERE ${where(result.changedRows[i]!)}`)
  })
  if (deleteExtra) {
    for (const row of result.onlyRight) statements.push(`DELETE FROM ${t} WHERE ${where(row)}`)
  }
  return statements
}
