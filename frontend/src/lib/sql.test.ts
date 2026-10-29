import { describe, expect, it } from 'vitest'
import { errorRange, resultTitle, splitStatements, statementAt } from './sql'

describe('splitStatements', () => {
  it('splits on semicolons and keeps offsets', () => {
    const text = 'select 1;\n  select 2;'
    const parts = splitStatements(text)
    expect(parts.map((p) => p.sql)).toEqual(['select 1', 'select 2'])
    expect(text.slice(parts[1]!.from, parts[1]!.to)).toBe('select 2')
  })

  it('ignores semicolons in strings, quoted identifiers and comments', () => {
    expect(splitStatements("select ';'; select \"a;b\"; -- x;y\nselect 3").map((p) => p.sql)).toEqual([
      "select ';'",
      'select "a;b"',
      '-- x;y\nselect 3',
    ])
  })

  it('keeps dollar-quoted bodies whole and drops empty statements', () => {
    expect(splitStatements('do $$ a; b; $$;;  ; ').map((p) => p.sql)).toEqual(['do $$ a; b; $$'])
  })
})

describe('statementAt', () => {
  const text = 'select 1;\nselect 2;\nselect 3'

  it('finds the statement under the cursor', () => {
    expect(statementAt(text, 12)?.sql).toBe('select 2')
  })

  it('falls back to the previous statement when the cursor is in trailing whitespace', () => {
    expect(statementAt('select 1;   \n\n', 13)?.sql).toBe('select 1')
  })

  it('returns undefined for an empty script', () => {
    expect(statementAt('  \n', 0)).toBeUndefined()
  })
})

describe('resultTitle', () => {
  it('names a query after the table it reads', () => {
    expect(resultTitle('select * from sales.orders o where o.id = 1')).toBe('orders')
    expect(resultTitle('-- top\nSELECT 1 FROM "My Table"')).toBe('My Table')
    expect(resultTitle('with x as (select 1) select * from x')).toBe('x')
  })

  it('names other statements after their verb', () => {
    expect(resultTitle('update orders set total = 1')).toBe('Update')
    expect(resultTitle('select 1')).toBe('Select')
  })
})

describe('errorRange', () => {
  const doc = 'select 1;\nselect * from nope where x = 1;'
  it('points at the PostgreSQL position inside the statement', () => {
    const r = errorRange(doc, 'select * from nope where x = 1', 'ERROR: relation "nope" does not exist\n  Position: 15')!
    expect(doc.slice(r.from, r.to)).toBe('nope')
    const pt = errorRange(doc, 'select * from nope where x = 1', 'ERRO: relação "nope" não existe\n  Posição: 15')!
    expect(doc.slice(pt.from, pt.to)).toBe('nope')
  })

  it('uses the MySQL near text, else the whole statement', () => {
    const r = errorRange(doc, 'select * from nope where x = 1', "You have an error near 'where x = 1' at line 1")!
    expect(doc.slice(r.from, r.to)).toBe('where')
    const whole = errorRange(doc, 'select 1', 'boom')!
    expect(doc.slice(whole.from, whole.to)).toBe('select 1')
  })
})
