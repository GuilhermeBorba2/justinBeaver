/** PostgreSQL `EXPLAIN (FORMAT JSON)` as a tree (DBeaver's graphical execution plan). */
export interface PlanNode {
  type: string
  /** relation, index, join type… in one line */
  detail: string
  startupCost: number | null
  totalCost: number | null
  rows: number | null
  /** ANALYZE only */
  actualMs: number | null
  actualRows: number | null
  loops: number | null
  /** condition lines: Filter, Index Cond, Hash Cond… */
  conditions: string[]
  children: PlanNode[]
  /** share of the root's total cost, 0..1 */
  share: number
}

type Raw = Record<string, unknown>

const num = (v: unknown) => (typeof v === 'number' ? v : null)

const CONDITIONS = ['Index Cond', 'Recheck Cond', 'Filter', 'Join Filter', 'Hash Cond', 'Merge Cond', 'Sort Key', 'Group Key', 'One-Time Filter']

function node(raw: Raw, rootCost: number): PlanNode {
  const detail = [
    raw['Join Type'] ? `${raw['Join Type']} join` : '',
    raw['Relation Name'] ? `on ${raw['Schema'] ? `${raw['Schema']}.` : ''}${raw['Relation Name']}${raw['Alias'] && raw['Alias'] !== raw['Relation Name'] ? ` ${raw['Alias']}` : ''}` : '',
    raw['Index Name'] ? `using ${raw['Index Name']}` : '',
    raw['CTE Name'] ? `CTE ${raw['CTE Name']}` : '',
    raw['Strategy'] && raw['Node Type'] === 'Aggregate' ? String(raw['Strategy']) : '',
  ]
    .filter(Boolean)
    .join(' ')
  const totalCost = num(raw['Total Cost'])
  const conditions = CONDITIONS.flatMap((key) => {
    const v = raw[key]
    if (v === undefined) return []
    return [`${key}: ${Array.isArray(v) ? v.join(', ') : String(v)}`]
  })
  if (typeof raw['Rows Removed by Filter'] === 'number') conditions.push(`Rows removed by filter: ${raw['Rows Removed by Filter']}`)
  const children = Array.isArray(raw['Plans']) ? (raw['Plans'] as Raw[]).map((c) => node(c, rootCost)) : []
  return {
    type: String(raw['Node Type'] ?? '?'),
    detail,
    startupCost: num(raw['Startup Cost']),
    totalCost,
    rows: num(raw['Plan Rows']),
    actualMs: num(raw['Actual Total Time']),
    actualRows: num(raw['Actual Rows']),
    loops: num(raw['Actual Loops']),
    conditions,
    children,
    share: rootCost > 0 && totalCost !== null ? Math.min(1, totalCost / rootCost) : 0,
  }
}

/** Parse the single JSON value PostgreSQL returns for EXPLAIN (FORMAT JSON); throws on anything else. */
export function parsePlan(json: unknown): { root: PlanNode; planningMs: number | null; executionMs: number | null } {
  const value = typeof json === 'string' ? JSON.parse(json) : json
  const top = (Array.isArray(value) ? value[0] : value) as Raw | undefined
  const plan = top?.['Plan'] as Raw | undefined
  if (!plan) throw new Error('Not a PostgreSQL JSON plan')
  return {
    root: node(plan, num(plan['Total Cost']) ?? 0),
    planningMs: num(top!['Planning Time']),
    executionMs: num(top!['Execution Time']),
  }
}
