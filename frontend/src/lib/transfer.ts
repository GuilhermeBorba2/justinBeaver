import { api } from '@/api/client'
import type { CellValue, RowChange, Scope, TableDetails } from '@/api/types'
import type { ColumnSpec, Dialect } from './ddl'
import { targetType } from './typemap'

/** Data transfer Table → Table (DBeaver's "Export data → Database"): rows read page by page, inserted in batches. */
export interface TableEnd {
  connectionId: string
  scope: Scope
  table: string
}

export interface CopyOutcome {
  copied: number
  /** binary values arrive as truncated previews: they are not copied */
  skippedBinary: string[]
  stopped: boolean
}

const PAGE = 1000
const BATCH = 500

/** Columns for a table created on `dialect` with the source's columns (types translated across databases). */
export function columnSpecs(details: TableDetails, dialect: Dialect, sameDialect: boolean): ColumnSpec[] {
  return details.columns.map((c) => ({
    name: c.name,
    type: targetType(c, dialect, sameDialect),
    nullable: c.nullable,
    primaryKey: c.primaryKey,
  }))
}

/**
 * Copy the rows of `source` (matching `where`) into `target`; columns are matched by name, case-insensitively.
 * `markDirty` is called for batches left uncommitted (manual commit mode on the target).
 */
export async function copyRows(
  source: TableEnd,
  target: TableEnd,
  where: string,
  options: { onProgress?: (copied: number) => void; isCancelled?: () => boolean; markDirty?: () => void } = {},
): Promise<CopyOutcome> {
  const srcDetails = await api.tableDetails(source.connectionId, source.scope, source.table)
  const destDetails = await api.tableDetails(target.connectionId, target.scope, target.table)
  const destByLower = new Map(destDetails.columns.map((c) => [c.name.toLowerCase(), c.name]))
  const orderBy = srcDetails.rowKey[0] ?? srcDetails.columns.find((c) => c.primaryKey)?.name ?? null
  let offset = 0
  let copied = 0
  let skippedBinary: string[] = []
  for (;;) {
    if (options.isCancelled?.()) return { copied, skippedBinary, stopped: true }
    const page = await api.tableData(source.connectionId, source.scope, source.table, { limit: PAGE, offset, orderBy, where })
    skippedBinary = page.columns.filter((c) => c.kind === 'binary').map((c) => c.name)
    const mapping = page.columns.map((c) => (c.kind === 'binary' ? null : (destByLower.get(c.name.toLowerCase()) ?? null)))
    if (!mapping.some(Boolean)) throw new Error(`No column of ${source.table} matches a column of ${target.table}`)
    const changes: RowChange[] = page.rows.map((row) => {
      const values: Record<string, CellValue> = {}
      mapping.forEach((name, i) => {
        if (name) values[name] = row[i] ?? null
      })
      return { op: 'INSERT', values }
    })
    for (let i = 0; i < changes.length; i += BATCH) {
      if (options.isCancelled?.()) return { copied, skippedBinary, stopped: true }
      const result = await api.applyEdits(target.connectionId, target.scope, target.table, changes.slice(i, i + BATCH))
      copied += result.rowsAffected
      if (!result.committed) options.markDirty?.()
      options.onProgress?.(copied)
    }
    if (!page.hasMore) break
    offset += page.rows.length
  }
  return { copied, skippedBinary, stopped: false }
}
