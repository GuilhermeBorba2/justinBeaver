import type { CellValue, ResultColumn } from '@/api/types'

/** Same output formats as DBeaver's data transfer processors (minus DbUnit/source code). */
export type ExportFormat = 'csv' | 'tsv' | 'json' | 'markdown' | 'sql' | 'html' | 'xml' | 'txt'

export const EXPORT_FORMATS: { id: ExportFormat; label: string; extension: string; mime: string }[] = [
  { id: 'csv', label: 'CSV', extension: 'csv', mime: 'text/csv' },
  { id: 'tsv', label: 'TSV', extension: 'tsv', mime: 'text/tab-separated-values' },
  { id: 'json', label: 'JSON', extension: 'json', mime: 'application/json' },
  { id: 'markdown', label: 'Markdown table', extension: 'md', mime: 'text/markdown' },
  { id: 'sql', label: 'SQL INSERT statements', extension: 'sql', mime: 'application/sql' },
  { id: 'html', label: 'HTML table', extension: 'html', mime: 'text/html' },
  { id: 'xml', label: 'XML', extension: 'xml', mime: 'application/xml' },
  { id: 'txt', label: 'Plain text (aligned)', extension: 'txt', mime: 'text/plain' },
]

function csvCell(value: CellValue): string {
  if (value === null) return ''
  const text = String(value)
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
}

export function toCsv(columns: ResultColumn[], rows: CellValue[][]): string {
  const lines = [columns.map((c) => csvCell(c.name)).join(',')]
  for (const row of rows) lines.push(row.map(csvCell).join(','))
  return lines.join('\r\n') + '\r\n'
}

export function toTsv(columns: ResultColumn[], rows: CellValue[][]): string {
  const cell = (v: CellValue) => (v === null ? '' : String(v).replace(/[\t\r\n]+/g, ' '))
  return [columns.map((c) => cell(c.name)).join('\t'), ...rows.map((r) => r.map(cell).join('\t'))].join('\n')
}

export function toJson(columns: ResultColumn[], rows: CellValue[][]): string {
  return JSON.stringify(
    rows.map((row) => Object.fromEntries(columns.map((c, i) => [c.name, row[i] ?? null]))),
    null,
    2,
  )
}

export function toMarkdown(columns: ResultColumn[], rows: CellValue[][]): string {
  const cell = (v: CellValue) => (v === null ? 'NULL' : String(v).replaceAll('|', '\\|').replace(/\r?\n/g, ' '))
  const header = `| ${columns.map((c) => cell(c.name)).join(' | ')} |`
  const rule = `| ${columns.map((c) => (c.kind === 'number' ? '---:' : '---')).join(' | ')} |`
  return [header, rule, ...rows.map((r) => `| ${r.map(cell).join(' | ')} |`)].join('\n') + '\n'
}

export function sqlLiteral(value: CellValue, column: ResultColumn): string {
  if (value === null) return 'NULL'
  if (typeof value === 'boolean') return value ? 'TRUE' : 'FALSE'
  if (column.kind === 'number' && /^-?\d+(\.\d+)?([eE][-+]?\d+)?$/.test(String(value))) return String(value)
  return `'${String(value).replaceAll("'", "''")}'`
}

const quoteName = (name: string) => `"${name.replaceAll('"', '""')}"`

export function toSqlInserts(columns: ResultColumn[], rows: CellValue[][], table: string): string {
  const target = table.split('.').map(quoteName).join('.')
  const names = columns.map((c) => quoteName(c.name)).join(', ')
  return (
    rows.map((row) => `INSERT INTO ${target} (${names}) VALUES (${row.map((v, i) => sqlLiteral(v, columns[i]!)).join(', ')});`).join('\n') +
    '\n'
  )
}

function escapeHtml(text: string): string {
  return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')
}

export function toHtml(columns: ResultColumn[], rows: CellValue[][], title: string): string {
  const head = columns.map((c) => `<th>${escapeHtml(c.name)}</th>`).join('')
  const body = rows
    .map((r) => `<tr>${r.map((v) => (v === null ? '<td class="null">NULL</td>' : `<td>${escapeHtml(String(v))}</td>`)).join('')}</tr>`)
    .join('\n')
  return `<!doctype html>
<html><head><meta charset="utf-8"><title>${escapeHtml(title)}</title>
<style>body{font:13px system-ui,sans-serif}table{border-collapse:collapse}th,td{border:1px solid #ccc;padding:3px 6px}th{background:#eee}.null{color:#999;font-style:italic}</style>
</head><body>
<table>
<thead><tr>${head}</tr></thead>
<tbody>
${body}
</tbody>
</table>
</body></html>
`
}

export function toXml(columns: ResultColumn[], rows: CellValue[][], name: string): string {
  const tag = (s: string) => (/^[A-Za-z_][\w.-]*$/.test(s) ? s : 'column')
  const xmlEscape = (s: string) => escapeHtml(s).replaceAll("'", '&apos;')
  const body = rows
    .map(
      (row) =>
        `  <row>\n${columns
          .map((c, i) => {
            const v = row[i] ?? null
            const attr = tag(c.name) === c.name ? '' : ` name="${xmlEscape(c.name)}"`
            return v === null ? `    <${tag(c.name)}${attr} null="true"/>` : `    <${tag(c.name)}${attr}>${xmlEscape(String(v))}</${tag(c.name)}>`
          })
          .join('\n')}\n  </row>`,
    )
    .join('\n')
  return `<?xml version="1.0" encoding="UTF-8"?>\n<export source="${xmlEscape(name)}">\n${body}\n</export>\n`
}

export function toTxt(columns: ResultColumn[], rows: CellValue[][]): string {
  const cell = (v: CellValue) => (v === null ? 'NULL' : String(v).replace(/\r?\n/g, ' '))
  const widths = columns.map((c, i) => Math.min(60, Math.max(c.name.length, ...rows.map((r) => cell(r[i] ?? null).length))))
  const pad = (text: string, i: number) => {
    const cut = text.length > widths[i]! ? text.slice(0, widths[i]! - 1) + '…' : text
    return columns[i]!.kind === 'number' ? cut.padStart(widths[i]!) : cut.padEnd(widths[i]!)
  }
  const line = (cells: string[]) => cells.map(pad).join(' | ').trimEnd()
  return [line(columns.map((c) => c.name)), widths.map((w) => '-'.repeat(w)).join('-+-'), ...rows.map((r) => line(r.map(cell)))].join('\n') + '\n'
}

export function render(format: ExportFormat, columns: ResultColumn[], rows: CellValue[][], name: string): string {
  switch (format) {
    case 'csv':
      return toCsv(columns, rows)
    case 'tsv':
      return toTsv(columns, rows)
    case 'json':
      return toJson(columns, rows)
    case 'markdown':
      return toMarkdown(columns, rows)
    case 'sql':
      return toSqlInserts(columns, rows, name)
    case 'html':
      return toHtml(columns, rows, name)
    case 'xml':
      return toXml(columns, rows, name)
    case 'txt':
      return toTxt(columns, rows)
  }
}

/** Saves text through a temporary link; runs from a user click, so no permission prompt is needed. */
export function downloadText(filename: string, text: string, mime = 'text/csv;charset=utf-8'): void {
  const url = URL.createObjectURL(new Blob([text], { type: mime }))
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

/**
 * Minimal RFC 4180 CSV parser (quoted fields, doubled quotes, CRLF/LF). Auto-detects `,` `;` or tab
 * from the header line.
 */
export function parseCsv(text: string): string[][] {
  const firstLine = text.slice(0, text.search(/\r?\n|$/))
  const counts = [',', ';', '\t'].map((d) => [d, firstLine.split(d).length] as const)
  const delimiter = counts.sort((a, b) => b[1] - a[1])[0]![0]
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let quoted = false
  let i = text.charCodeAt(0) === 0xfeff ? 1 : 0 // skip BOM
  for (; i < text.length; i++) {
    const c = text[i]!
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"'
          i++
        } else {
          quoted = false
        }
      } else {
        field += c
      }
    } else if (c === '"' && field === '') {
      quoted = true
    } else if (c === delimiter) {
      row.push(field)
      field = ''
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++
      row.push(field)
      rows.push(row)
      row = []
      field = ''
    } else {
      field += c
    }
  }
  if (field !== '' || row.length) {
    row.push(field)
    rows.push(row)
  }
  return rows.filter((r) => r.length > 1 || r[0] !== '')
}
