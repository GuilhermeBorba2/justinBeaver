import { describe, expect, it } from 'vitest'
import type { ResultColumn } from '@/api/types'
import { inList, linesOf, names, sliceOf, valuesOf, withHeaders } from './copy'

const col = (name: string, kind: ResultColumn['kind'] = 'text'): ResultColumn => ({ name, typeName: kind, kind }) as ResultColumn
const columns = [col('id', 'number'), col('name'), col('note')]
const rows = [
  [1, 'Ana', null],
  [2, "O'Neil", 'x\ty'],
  [3, 'Ana', undefined],
]

describe('sliceOf', () => {
  it('takes the rectangle, skipping hidden columns and turning unset cells into NULL', () => {
    const s = sliceOf(columns, rows, { r1: 1, r2: 2, c1: 0, c2: 2 }, [0, 2])
    expect(s.columns.map((c) => c.name)).toEqual(['id', 'note'])
    expect(s.rows).toEqual([[2, 'x\ty'], [3, null]])
  })

  it('ignores rows that do not exist', () => {
    expect(sliceOf(columns, rows, { r1: 2, r2: 9, c1: 0, c2: 0 }, [0]).rows).toEqual([[3]])
  })
})

describe('text forms', () => {
  const s = sliceOf(columns, rows, { r1: 0, r2: 2, c1: 0, c2: 1 }, [0, 1])

  it('lists values one per line with NULL as empty', () => {
    expect(valuesOf(s)).toEqual(['1', 'Ana', '2', "O'Neil", '3', 'Ana'])
    expect(linesOf(sliceOf(columns, rows, { r1: 0, r2: 2, c1: 2, c2: 2 }, [2]))).toBe('\nx\ty\n')
  })

  it('writes an IN list: distinct, escaped, numbers bare, NULL left out', () => {
    expect(inList(s)).toBe("(1, 'Ana', 2, 'O''Neil', 3)")
    expect(inList(sliceOf(columns, rows, { r1: 0, r2: 2, c1: 2, c2: 2 }, [2]))).toBe("('x\ty')")
    expect(inList(sliceOf(columns, [], { r1: 0, r2: 0, c1: 0, c2: 0 }, [0]))).toBe('()')
  })

  it('gives the column names plain or as SQL identifiers', () => {
    expect(names(s)).toBe('id, name')
    expect(names({ columns: [col('a"b')], rows: [] }, true)).toBe('"a""b"')
  })

  it('makes a spreadsheet table with headers, one line per row', () => {
    expect(withHeaders(s)).toBe("id\tname\n1\tAna\n2\tO'Neil\n3\tAna")
  })
})
