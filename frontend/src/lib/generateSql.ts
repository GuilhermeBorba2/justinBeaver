import type { ColumnInfo } from '@/api/types'
import { q, qualified, type DdlContext, type TableRef } from './ddl'

/** "Generate SQL" of a table (DBeaver's right-click ▸ Generate SQL). Values are `:name` variables, which the editor asks for when run. */

/** A bind-variable name for a column: word characters only, so the editor's parameter dialog recognizes it. */
export function paramName(column: string): string {
  const clean = column.replace(/\W+/g, '_').replace(/^_+|_+$/g, '')
  return /^\d/.test(clean) ? `p_${clean}` : clean || 'value'
}

function columnList(ctx: DdlContext, columns: ColumnInfo[]): string {
  return columns.map((c) => q(ctx, c.name)).join(', ')
}

function whereKey(ctx: DdlContext, columns: ColumnInfo[]): string {
  const key = columns.filter((c) => c.primaryKey)
  if (key.length === 0) return `WHERE /* condition */`
  return `WHERE ${key.map((c) => `${q(ctx, c.name)} = :${paramName(c.name)}`).join('\n  AND ')}`
}

export function selectAll(ctx: DdlContext, table: TableRef): string {
  return `SELECT *\nFROM ${qualified(ctx, table)};\n`
}

export function selectColumns(ctx: DdlContext, table: TableRef, columns: ColumnInfo[]): string {
  if (columns.length === 0) return selectAll(ctx, table)
  return `SELECT ${columns.map((c) => q(ctx, c.name)).join(',\n       ')}\nFROM ${qualified(ctx, table)};\n`
}

export function insertInto(ctx: DdlContext, table: TableRef, columns: ColumnInfo[]): string {
  // generated keys are filled in by the database
  const own = columns.filter((c) => !c.autoIncrement)
  const used = own.length ? own : columns
  return `INSERT INTO ${qualified(ctx, table)}\n  (${columnList(ctx, used)})\nVALUES\n  (${used.map((c) => `:${paramName(c.name)}`).join(', ')});\n`
}

export function updateRow(ctx: DdlContext, table: TableRef, columns: ColumnInfo[]): string {
  const set = columns.filter((c) => !c.primaryKey)
  const assigned = set.length ? set : columns
  return `UPDATE ${qualified(ctx, table)}\nSET ${assigned.map((c) => `${q(ctx, c.name)} = :${paramName(c.name)}`).join(',\n    ')}\n${whereKey(ctx, columns)};\n`
}

export function deleteRow(ctx: DdlContext, table: TableRef, columns: ColumnInfo[]): string {
  return `DELETE FROM ${qualified(ctx, table)}\n${whereKey(ctx, columns)};\n`
}
