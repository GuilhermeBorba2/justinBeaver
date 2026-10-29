/**
 * SQL variables (DBeaver "SQL parameters"): `:name` and `${name}` outside string literals,
 * quoted identifiers and comments. PostgreSQL casts (`::int`) and `:=` are not variables.
 * Values are substituted as SQL text, so the user types literals as SQL: `'text'`, `42`, `NULL`.
 */

interface Segment {
  code: boolean
  text: string
}

/** Split into code and non-code (strings, quoted identifiers, comments, dollar-quoted bodies). */
function segments(sql: string): Segment[] {
  const out: Segment[] = []
  let code = ''
  let i = 0
  const push = (text: string) => {
    if (code) out.push({ code: true, text: code })
    code = ''
    out.push({ code: false, text })
  }
  while (i < sql.length) {
    const c = sql[i]!
    const next = sql[i + 1]
    let end = -1
    if (c === '-' && next === '-') {
      const eol = sql.indexOf('\n', i)
      end = eol < 0 ? sql.length : eol
    } else if (c === '/' && next === '*') {
      const close = sql.indexOf('*/', i + 2)
      end = close < 0 ? sql.length : close + 2
    } else if (c === "'" || c === '"' || c === '`') {
      end = i + 1
      while (end < sql.length) {
        if (sql[end] === c) {
          if (sql[end + 1] === c) {
            end += 2
            continue
          }
          end++
          break
        }
        end++
      }
    } else if (c === '$' && /^\$[A-Za-z0-9_]*\$/.test(sql.slice(i)) && !sql.startsWith('${', i)) {
      const tag = /^\$[A-Za-z0-9_]*\$/.exec(sql.slice(i))![0]
      const close = sql.indexOf(tag, i + tag.length)
      end = close < 0 ? sql.length : close + tag.length
    }
    if (end > i) {
      push(sql.slice(i, end))
      i = end
    } else {
      code += c
      i++
    }
  }
  if (code) out.push({ code: true, text: code })
  return out
}

const VARIABLE = /\$\{([A-Za-z_][\w.]*)\}|(?<![:\w]):([A-Za-z_]\w*)/g

/** Variable names in order of first appearance. */
export function findParams(sql: string): string[] {
  const names: string[] = []
  for (const seg of segments(sql)) {
    if (!seg.code) continue
    for (const match of seg.text.matchAll(VARIABLE)) {
      const name = match[1] ?? match[2]!
      if (!names.includes(name)) names.push(name)
    }
  }
  return names
}

export function substituteParams(sql: string, values: Record<string, string>): string {
  return segments(sql)
    .map((seg) =>
      seg.code
        ? seg.text.replace(VARIABLE, (whole, braced: string | undefined, colon: string | undefined) => {
            const name = braced ?? colon!
            return name in values ? values[name]! : whole
          })
        : seg.text,
    )
    .join('')
}

const STORAGE_KEY = 'justinbeaver.params'

/** Last values typed for each variable name, to prefill the dialog. */
export function rememberedValues(): Record<string, string> {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') as Record<string, string>
  } catch {
    return {}
  }
}

export function rememberValues(values: Record<string, string>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...rememberedValues(), ...values }))
  } catch {
    // convenience only
  }
}
