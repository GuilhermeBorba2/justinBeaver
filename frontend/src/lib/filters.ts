import type { CellValue, ForeignKeyInfo } from '@/api/types'
import { q, type DdlContext } from './ddl'

/** `column = value` as SQL, with the value as a literal (numbers unquoted, NULL as IS NULL). */
export function equalsCondition(ctx: DdlContext, column: string, kind: string, value: CellValue): string {
  const name = q(ctx, column)
  if (value === null) return `${name} IS NULL`
  if (typeof value === 'number' || typeof value === 'boolean' || (kind === 'number' && /^-?\d+(\.\d+)?$/.test(String(value)))) {
    return `${name} = ${String(value)}`
  }
  return `${name} = '${String(value).replaceAll("'", "''")}'`
}

/** One foreign key with all its columns (JDBC reports a row per column). */
export interface KeyLink {
  name: string
  catalog: string | null
  schema: string | null
  table: string
  /** local column → column on the other table */
  pairs: { column: string; other: string }[]
}

/** Groups per-column key rows into keys; works for `foreignKeys` and `referencedBy` alike. */
export function groupKeys(keys: ForeignKeyInfo[]): KeyLink[] {
  const groups = new Map<string, KeyLink>()
  for (const k of keys) {
    const id = [k.name ?? '', k.refCatalog ?? '', k.refSchema ?? '', k.refTable].join('\u0000')
    let group = groups.get(id)
    if (!group) {
      group = { name: k.name ?? k.refTable, catalog: k.refCatalog, schema: k.refSchema, table: k.refTable, pairs: [] }
      groups.set(id, group)
    }
    if (!group.pairs.some((p) => p.column === k.column && p.other === k.refColumn)) {
      group.pairs.push({ column: k.column, other: k.refColumn })
    }
  }
  return [...groups.values()]
}

/**
 * The filter that finds, on the other table of `link`, the rows matching one row here; undefined when a
 * key column of the row is NULL (it references nothing) or missing from the result.
 */
export function linkFilter(
  ctx: DdlContext,
  link: KeyLink,
  columns: { name: string; kind: string }[],
  row: (CellValue | undefined)[],
): string | undefined {
  const parts: string[] = []
  for (const { column, other } of link.pairs) {
    const index = columns.findIndex((c) => c.name === column)
    const value = index < 0 ? undefined : row[index]
    if (value === undefined || value === null) return undefined
    parts.push(equalsCondition(ctx, other, columns[index]!.kind, value))
  }
  return parts.join(' AND ')
}
