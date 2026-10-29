import type { DisplayCell } from './edits'

/** DBeaver's "Calc" panel: aggregates of the selected cells. */
export interface CalcStats {
  /** selected cells */
  cells: number
  nulls: number
  /** non-null values that are numbers (or numeric strings such as BigDecimal values) */
  numbers: number
  distinct: number
  sum: number | null
  /** The exact sum as decimal text when every value is a plain decimal (BigDecimal money values do not drift). */
  exactSum: string | null
  avg: number | null
  min: string | number | null
  max: string | number | null
}

const NUMERIC = /^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i

export function numeric(value: DisplayCell): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string' && NUMERIC.test(value.trim())) return Number(value)
  return null
}

const PLAIN_DECIMAL = /^([-+]?)(\d+)(?:\.(\d+))?$/

/** Exact sum of plain decimals with BigInt; null when a value is not one (exponent, Infinity…). */
export function exactDecimalSum(texts: string[]): string | null {
  let scale = 0
  const parsed: { negative: boolean; digits: string; fraction: string }[] = []
  for (const text of texts) {
    const m = PLAIN_DECIMAL.exec(text.trim())
    if (!m) return null
    const fraction = m[3] ?? ''
    scale = Math.max(scale, fraction.length)
    parsed.push({ negative: m[1] === '-', digits: m[2]!, fraction })
  }
  let total = 0n
  for (const p of parsed) {
    const scaled = BigInt(p.digits + p.fraction.padEnd(scale, '0'))
    total += p.negative ? -scaled : scaled
  }
  const negative = total < 0n
  const digits = (negative ? -total : total).toString().padStart(scale + 1, '0')
  const whole = digits.slice(0, digits.length - scale)
  const fraction = scale ? digits.slice(digits.length - scale).replace(/0+$/, '') : ''
  return `${negative ? '-' : ''}${whole}${fraction ? '.' + fraction : ''}`
}

/** Drops float noise such as 0.1 + 0.2 = 0.30000000000000004. */
function tidy(value: number): number {
  return Number(value.toPrecision(15))
}

/**
 * Sum and average count numeric values only; min and max compare numbers when every value is numeric,
 * else text. `undefined` cells (DEFAULT of a new row) count as null.
 */
export function calc(values: DisplayCell[]): CalcStats {
  let nulls = 0
  let sum = 0
  const nums: number[] = []
  const numTexts: string[] = []
  const texts: string[] = []
  const distinct = new Set<string>()
  for (const value of values) {
    if (value === null || value === undefined) {
      nulls++
      continue
    }
    const text = String(value)
    distinct.add(text)
    texts.push(text)
    const n = numeric(value)
    if (n !== null) {
      nums.push(n)
      numTexts.push(typeof value === 'number' ? String(value) : text)
      sum += n
    }
  }
  const allNumeric = texts.length > 0 && nums.length === texts.length
  let min: string | number | null = null
  let max: string | number | null = null
  if (allNumeric) {
    min = nums.reduce((a, b) => (b < a ? b : a))
    max = nums.reduce((a, b) => (b > a ? b : a))
  } else if (texts.length) {
    min = texts.reduce((a, b) => (b < a ? b : a))
    max = texts.reduce((a, b) => (b > a ? b : a))
  }
  return {
    cells: values.length,
    nulls,
    numbers: nums.length,
    distinct: distinct.size,
    sum: nums.length ? tidy(sum) : null,
    exactSum: nums.length ? exactDecimalSum(numTexts) : null,
    avg: nums.length ? tidy(sum / nums.length) : null,
    min,
    max,
  }
}

export interface CellRange {
  r1: number
  c1: number
  r2: number
  c2: number
}

/** Columns of a rectangle, skipping the hidden ones (`shown` lists the visible column indexes). */
function columnsOf(range: CellRange, shown?: readonly number[]): number[] {
  const all: number[] = []
  for (let c = range.c1; c <= range.c2; c++) all.push(c)
  return shown ? all.filter((c) => shown.includes(c)) : all
}

/** The cells of a rectangle, row by row. */
export function cellsIn(rows: DisplayCell[][], range: CellRange, shown?: readonly number[]): DisplayCell[] {
  const out: DisplayCell[] = []
  const cols = columnsOf(range, shown)
  for (let r = range.r1; r <= range.r2; r++) {
    const row = rows[r]
    if (!row) continue
    for (const c of cols) out.push(row[c])
  }
  return out
}

/** Tab-separated text of a rectangle (what Ctrl+C puts on the clipboard); NULL as empty. */
export function rangeText(rows: DisplayCell[][], range: CellRange, shown?: readonly number[]): string {
  const lines: string[] = []
  const cols = columnsOf(range, shown)
  for (let r = range.r1; r <= range.r2; r++) {
    const row = rows[r]
    if (!row) continue
    const cells: string[] = []
    for (const c of cols) {
      const value = row[c]
      cells.push(value === null || value === undefined ? '' : String(value))
    }
    lines.push(cells.join('\t'))
  }
  return lines.join('\n')
}

/** Cells whose text contains `query` (case-insensitive), row by row; at most `limit`. */
export function findCells(
  rows: DisplayCell[][],
  shown: readonly number[],
  query: string,
  limit = 10000,
): { row: number; col: number }[] {
  const needle = query.toLowerCase()
  const found: { row: number; col: number }[] = []
  if (!needle) return found
  for (let r = 0; r < rows.length && found.length < limit; r++) {
    const row = rows[r]!
    for (const c of shown) {
      const value = row[c]
      const text = value === undefined ? 'default' : value === null ? 'null' : String(value).toLowerCase()
      if (text.includes(needle)) {
        found.push({ row: r, col: c })
        if (found.length >= limit) break
      }
    }
  }
  return found
}
