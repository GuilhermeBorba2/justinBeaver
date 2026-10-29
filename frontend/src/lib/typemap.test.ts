import { describe, expect, it } from 'vitest'
import { targetType } from './typemap'

const col = (typeName: string, size = 0, decimalDigits: number | null = null) => ({ typeName, size, decimalDigits })

describe('targetType', () => {
  it('keeps the source spelling on the same database, with sizes', () => {
    expect(targetType(col('varchar', 40), 'postgresql', true)).toBe('varchar(40)')
    expect(targetType(col('numeric', 10, 2), 'postgresql', true)).toBe('numeric(10,2)')
    expect(targetType(col('text', 2147483647), 'postgresql', true)).toBe('text')
  })

  it('translates PostgreSQL types for MySQL and SQL Server', () => {
    expect(targetType(col('int4', 10), 'mysql', false)).toBe('INTEGER')
    expect(targetType(col('text', 2147483647), 'mysql', false)).toBe('LONGTEXT')
    expect(targetType(col('timestamptz', 35), 'sqlserver', false)).toBe('DATETIMEOFFSET')
    expect(targetType(col('bool', 1), 'sqlserver', false)).toBe('BIT')
    expect(targetType(col('numeric', 12, 2), 'oracle', false)).toBe('NUMBER(12,2)')
  })

  it('translates MySQL types for PostgreSQL and keeps unknown ones', () => {
    expect(targetType(col('DATETIME', 19), 'postgresql', false)).toBe('TIMESTAMP')
    expect(targetType(col('TINYINT UNSIGNED', 3), 'postgresql', false)).toBe('SMALLINT')
    expect(targetType(col('LONGBLOB'), 'postgresql', false)).toBe('BYTEA')
    expect(targetType(col('geometry'), 'postgresql', false)).toBe('geometry')
  })
})
