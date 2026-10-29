import { describe, expect, it } from 'vitest'
import type { CellValue, ResultColumn } from '@/api/types'
import { parseCsv, toCsv, toJson, toMarkdown, toSqlInserts, toTxt, toXml } from './export'

const columns: ResultColumn[] = [
  { name: 'id', typeName: 'INT', kind: 'number' },
  { name: 'name', typeName: 'TEXT', kind: 'text' },
]
const rows: CellValue[][] = [
  [1, "O'Brien"],
  [2, null],
]

describe('export formats', () => {
  it('csv quotes only when needed', () => {
    expect(toCsv(columns, [[1, 'a,b'], [2, 'say "hi"']])).toBe('id,name\r\n1,"a,b"\r\n2,"say ""hi"""\r\n')
  })

  it('json keeps nulls', () => {
    expect(JSON.parse(toJson(columns, rows))).toEqual([
      { id: 1, name: "O'Brien" },
      { id: 2, name: null },
    ])
  })

  it('sql inserts escape quotes and keep numbers unquoted', () => {
    expect(toSqlInserts(columns, rows, 'public.person')).toBe(
      'INSERT INTO "public"."person" ("id", "name") VALUES (1, \'O\'\'Brien\');\n' +
        'INSERT INTO "public"."person" ("id", "name") VALUES (2, NULL);\n',
    )
  })

  it('markdown right-aligns numbers and escapes pipes', () => {
    expect(toMarkdown(columns, [[1, 'a|b']])).toBe('| id | name |\n| ---: | --- |\n| 1 | a\\|b |\n')
  })

  it('xml escapes and marks nulls', () => {
    const xml = toXml(columns, [[1, '<x>'], [2, null]], 'person')
    expect(xml).toContain('<name>&lt;x&gt;</name>')
    expect(xml).toContain('<name null="true"/>')
  })

  it('txt aligns columns', () => {
    expect(toTxt(columns, rows).split('\n')[2]).toBe(" 1 | O'Brien")
  })
})

describe('parseCsv', () => {
  it('handles quotes, doubled quotes, newlines in fields and CRLF', () => {
    expect(parseCsv('id,name\r\n1,"a, ""b"""\r\n2,"multi\nline"\r\n')).toEqual([
      ['id', 'name'],
      ['1', 'a, "b"'],
      ['2', 'multi\nline'],
    ])
  })

  it('detects semicolon delimiters and strips a BOM', () => {
    expect(parseCsv('﻿id;name\n1;x\n')).toEqual([
      ['id', 'name'],
      ['1', 'x'],
    ])
  })

  it('keeps empty fields', () => {
    expect(parseCsv('a,b,c\n1,,3')).toEqual([
      ['a', 'b', 'c'],
      ['1', '', '3'],
    ])
  })
})
