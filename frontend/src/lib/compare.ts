/** Schema compare (DBeaver "Compare/Migrate → Simple compare") on table and column names and types. */
export type TableColumns = Record<string, string[]>

export interface ColumnTypes {
  /** table -> column -> type (only for tables whose types were fetched) */
  [table: string]: Record<string, string>
}

export interface CompareReport {
  onlyLeft: string[]
  onlyRight: string[]
  /** tables present on both sides with different columns */
  changed: { table: string; onlyLeft: string[]; onlyRight: string[]; typeDiffs: { column: string; left: string; right: string }[] }[]
  identical: number
}

const norm = (s: string, ignoreCase: boolean) => (ignoreCase ? s.toLowerCase() : s)

export function compareSchemas(
  left: TableColumns,
  right: TableColumns,
  ignoreCase: boolean,
  leftTypes: ColumnTypes = {},
  rightTypes: ColumnTypes = {},
): CompareReport {
  const rightByKey = new Map(Object.keys(right).map((t) => [norm(t, ignoreCase), t]))
  const leftKeys = new Set(Object.keys(left).map((t) => norm(t, ignoreCase)))
  const report: CompareReport = { onlyLeft: [], onlyRight: [], changed: [], identical: 0 }

  for (const table of Object.keys(left).sort()) {
    const rightName = rightByKey.get(norm(table, ignoreCase))
    if (!rightName) {
      report.onlyLeft.push(table)
      continue
    }
    const leftCols = left[table]!
    const rightCols = right[rightName]!
    const rightColByKey = new Map(rightCols.map((c) => [norm(c, ignoreCase), c]))
    const leftColKeys = new Set(leftCols.map((c) => norm(c, ignoreCase)))
    const onlyLeft = leftCols.filter((c) => !rightColByKey.has(norm(c, ignoreCase)))
    const onlyRight = rightCols.filter((c) => !leftColKeys.has(norm(c, ignoreCase)))
    const typeDiffs: CompareReport['changed'][number]['typeDiffs'] = []
    const lt = leftTypes[table]
    const rt = rightTypes[rightName]
    if (lt && rt) {
      for (const c of leftCols) {
        const rc = rightColByKey.get(norm(c, ignoreCase))
        if (rc && lt[c] && rt[rc] && lt[c]!.toLowerCase() !== rt[rc]!.toLowerCase()) {
          typeDiffs.push({ column: c, left: lt[c]!, right: rt[rc]! })
        }
      }
    }
    if (onlyLeft.length || onlyRight.length || typeDiffs.length) {
      report.changed.push({ table, onlyLeft, onlyRight, typeDiffs })
    } else {
      report.identical++
    }
  }
  report.onlyRight = Object.keys(right)
    .filter((t) => !leftKeys.has(norm(t, ignoreCase)))
    .sort()
  return report
}

export function reportText(report: CompareReport, leftLabel: string, rightLabel: string): string {
  const lines = [`Compare ${leftLabel} → ${rightLabel}`, `Identical tables: ${report.identical}`, '']
  if (report.onlyLeft.length) lines.push(`Only in ${leftLabel}:`, ...report.onlyLeft.map((t) => `  - ${t}`), '')
  if (report.onlyRight.length) lines.push(`Only in ${rightLabel}:`, ...report.onlyRight.map((t) => `  + ${t}`), '')
  for (const c of report.changed) {
    lines.push(`Table ${c.table}:`)
    for (const col of c.onlyLeft) lines.push(`  - column ${col} (only in ${leftLabel})`)
    for (const col of c.onlyRight) lines.push(`  + column ${col} (only in ${rightLabel})`)
    for (const d of c.typeDiffs) lines.push(`  ~ column ${d.column}: ${d.left} → ${d.right}`)
  }
  return lines.join('\n').trimEnd() + '\n'
}
