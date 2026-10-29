import { touchSettings } from './settingsStamp'

/**
 * SQL templates (DBeaver's "SQL Templates"): type the name and press Tab, or pick it from the completion list.
 * `${name}` marks a field the cursor jumps through with Tab; `${}` is where the cursor ends.
 */
export interface Snippet {
  name: string
  description: string
  template: string
}

export const DEFAULT_SNIPPETS: Snippet[] = [
  { name: 'sf', description: 'select * from', template: 'select * from ${table}' },
  { name: 'sw', description: 'select … where', template: 'select * from ${table} where ${condition}' },
  { name: 'scc', description: 'select count(*)', template: 'select count(*) from ${table}' },
  { name: 'sdc', description: 'select distinct … count', template: 'select ${column}, count(*) from ${table} group by ${column} order by 2 desc' },
  { name: 'ob', description: 'order by', template: 'order by ${column}' },
  { name: 'gb', description: 'group by', template: 'group by ${column}' },
  { name: 'ij', description: 'inner join', template: 'inner join ${table} on ${condition}' },
  { name: 'lj', description: 'left join', template: 'left join ${table} on ${condition}' },
  { name: 'ins', description: 'insert into … values', template: 'insert into ${table} (${columns}) values (${values})' },
  { name: 'upd', description: 'update … set … where', template: 'update ${table} set ${column} = ${value} where ${condition}' },
  { name: 'del', description: 'delete … where', template: 'delete from ${table} where ${condition}' },
  { name: 'ct', description: 'create table', template: 'create table ${name} (\n  id ${type} primary key,\n  ${}\n)' },
  { name: 'cte', description: 'with … as (…)', template: 'with ${name} as (\n  ${query}\n)\nselect * from ${name}' },
]

const KEY = 'justinbeaver.snippets'

/** Templates the user added or changed in Preferences, merged over the defaults (same name wins). */
export function readSnippets(): Snippet[] {
  let custom: Snippet[] = []
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    if (Array.isArray(parsed)) {
      custom = parsed.filter(
        (s): s is Snippet => typeof s?.name === 'string' && typeof s?.template === 'string' && /^\w+$/.test(s.name),
      )
    }
  } catch {
    // storage unavailable or corrupted: defaults only
  }
  const byName = new Map(DEFAULT_SNIPPETS.map((s) => [s.name, s]))
  for (const s of custom) byName.set(s.name, { name: s.name, description: s.description ?? '', template: s.template })
  return [...byName.values()]
}

export function saveSnippets(snippets: Snippet[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(snippets))
    touchSettings()
  } catch {
    // best effort
  }
}

/** The template name right before the cursor ("sf|" → sf), if it is one. */
export function snippetBefore(line: string, snippets: Snippet[]): Snippet | undefined {
  const word = /(\w+)$/.exec(line)?.[1]
  return word ? snippets.find((s) => s.name === word) : undefined
}
