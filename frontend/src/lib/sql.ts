export interface StatementRange {
  from: number
  to: number
  sql: string
}

/**
 * Splits a script on `;` outside string literals, quoted identifiers, comments and PostgreSQL
 * dollar-quoted bodies, keeping each statement's offsets so the editor can find "the statement
 * under the cursor". Mirrors the server's ScriptSplitter.
 */
export function splitStatements(text: string): StatementRange[] {
  const ranges: StatementRange[] = []
  const n = text.length
  let start = 0
  let hasContent = false
  let i = 0

  const flush = (end: number) => {
    if (hasContent) {
      const raw = text.slice(start, end)
      const lead = raw.length - raw.trimStart().length
      const sql = raw.trim()
      ranges.push({ from: start + lead, to: start + lead + sql.length, sql })
    }
    hasContent = false
  }

  while (i < n) {
    const c = text[i]!
    const next = text[i + 1]
    if (c === '-' && next === '-') {
      const eol = text.indexOf('\n', i)
      i = eol < 0 ? n : eol
    } else if (c === '/' && next === '*') {
      const close = text.indexOf('*/', i + 2)
      i = close < 0 ? n : close + 2
    } else if (c === "'" || c === '"' || c === '`') {
      hasContent = true
      i = endOfQuoted(text, i, c)
    } else if (c === '$' && dollarTagEnd(text, i) > 0) {
      hasContent = true
      const tagEnd = dollarTagEnd(text, i)
      const tag = text.slice(i, tagEnd)
      const close = text.indexOf(tag, tagEnd)
      i = close < 0 ? n : close + tag.length
    } else if (c === ';') {
      flush(i)
      start = i + 1
      i++
    } else {
      if (!/\s/.test(c)) hasContent = true
      i++
    }
  }
  flush(n)
  return ranges
}

/** The statement containing `pos`, else the closest one before it, else the first one. */
export function statementAt(text: string, pos: number): StatementRange | undefined {
  const all = splitStatements(text)
  return (
    all.find((s) => pos >= s.from && pos <= s.to + 1) ??
    [...all].reverse().find((s) => s.to < pos) ??
    all[0]
  )
}

function endOfQuoted(text: string, start: number, quote: string): number {
  let i = start + 1
  while (i < text.length) {
    if (text[i] === quote) {
      if (text[i + 1] === quote) {
        i += 2
        continue
      }
      return i + 1
    }
    i++
  }
  return text.length
}

function dollarTagEnd(text: string, i: number): number {
  let j = i + 1
  while (j < text.length && /[\w]/.test(text[j]!)) j++
  return text[j] === '$' ? j + 1 : -1
}

const COMMENTS = /--[^\n]*|\/\*[\s\S]*?\*\//g

/**
 * Title of a result tab, as DBeaver names them: the first table a query reads from, else the
 * statement's verb ("Update", "Insert"…).
 */
export function resultTitle(sql: string): string {
  const text = sql.replace(COMMENTS, ' ').trim()
  const from = /\bfrom\s+((?:"[^"]+"|`[^`]+`|\[[^\]]+\]|[\w$]+)(?:\s*\.\s*(?:"[^"]+"|`[^`]+`|\[[^\]]+\]|[\w$]+))*)/i.exec(text)
  if (/^(select|with|table|values)\b/i.test(text) && from) {
    const last = from[1]!.split('.').pop()!.trim()
    return last.replace(/^["`[]|["`\]]$/g, '')
  }
  const verb = /^[a-z]+/i.exec(text)?.[0]
  return verb ? verb[0]!.toUpperCase() + verb.slice(1).toLowerCase() : 'Result'
}

/**
 * Where to underline a failed statement in the editor: the statement's range in `doc` (the first occurrence
 * at or after `from`), narrowed to the token the database points at (PostgreSQL "Position: N", MySQL
 * "near '…'", "at line N").
 */
export function errorRange(doc: string, statement: string, message: string, from = 0): { from: number; to: number } | undefined {
  const target = statement.trim()
  const ranges = splitStatements(doc)
  const hit = ranges.find((r) => r.from >= from && r.sql === target) ?? ranges.find((r) => r.sql === target)
  let start: number
  let end: number
  if (hit) {
    start = hit.from
    end = hit.to
  } else {
    const at = doc.indexOf(target, from)
    if (at < 0) return undefined
    start = at
    end = at + target.length
  }
  const text = doc.slice(start, end)
  // PostgreSQL localizes the label: Position, Posição, Posición, Positionen…
  const position = /\b(?:position|posi[çc][ãa]o|posici[óo]n)\s*:\s*(\d+)/i.exec(message)
  if (position) {
    const offset = Math.min(text.length - 1, Math.max(0, Number(position[1]) - 1))
    const word = /^[\w$."]+/.exec(text.slice(offset))?.[0]
    return { from: start + offset, to: start + offset + Math.max(1, word?.length ?? 1) }
  }
  const near = /near '([^']{1,80})/i.exec(message)
  if (near) {
    const at = text.indexOf(near[1]!.split('\n')[0]!)
    if (at >= 0) return { from: start + at, to: start + at + Math.max(1, near[1]!.split(/\s/)[0]!.length) }
  }
  return { from: start, to: end }
}
