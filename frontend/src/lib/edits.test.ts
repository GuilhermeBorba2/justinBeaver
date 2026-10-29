import { describe, expect, it } from 'vitest'
import type { CellValue, ResultColumn } from '@/api/types'
import {
  addRow,
  buildChanges,
  changeCount,
  displayRows,
  duplicateRow,
  emptyEdits,
  isChanged,
  setCell,
  toggleDelete,
} from './edits'

const columns: ResultColumn[] = [
  { name: 'id', typeName: 'INT', kind: 'number' },
  { name: 'name', typeName: 'TEXT', kind: 'text' },
  { name: 'score', typeName: 'REAL', kind: 'number' },
]
const rows: CellValue[][] = [
  [1, 'Ana', 9.5],
  [2, 'Bob', null],
]

describe('pending edits', () => {
  it('builds keyed changes in delete, update, insert order', () => {
    const edits = emptyEdits()
    setCell(edits, rows, columns, 0, 1, 'Ann')
    toggleDelete(edits, rows.length, 1)
    addRow(edits)
    setCell(edits, rows, columns, 2, 1, 'Cy')

    expect(buildChanges(edits, rows, columns, ['id'])).toEqual([
      { op: 'DELETE', key: { id: 2 } },
      { op: 'UPDATE', key: { id: 1 }, values: { name: 'Ann' } },
      { op: 'INSERT', values: { name: 'Cy' } },
    ])
    expect(changeCount(edits)).toBe(3)
  })

  it('drops a change when the value is set back, comparing as text', () => {
    const edits = emptyEdits()
    setCell(edits, rows, columns, 0, 2, '1')
    setCell(edits, rows, columns, 0, 2, '9.5')
    expect(changeCount(edits)).toBe(0)
    expect(buildChanges(edits, rows, columns, ['id'])).toEqual([])
  })

  it('distinguishes NULL from empty text', () => {
    const edits = emptyEdits()
    setCell(edits, rows, columns, 1, 2, '')
    expect(buildChanges(edits, rows, columns, ['id'])).toEqual([{ op: 'UPDATE', key: { id: 2 }, values: { score: '' } }])
    setCell(edits, rows, columns, 1, 2, null)
    expect(changeCount(edits)).toBe(0)
  })

  it('keys updates by the original key even when the key itself is edited', () => {
    const edits = emptyEdits()
    setCell(edits, rows, columns, 0, 0, '10')
    expect(buildChanges(edits, rows, columns, ['id'])).toEqual([{ op: 'UPDATE', key: { id: 1 }, values: { id: '10' } }])
  })

  it('shows edits, unset insert cells as undefined, and marks changed cells', () => {
    const edits = emptyEdits()
    setCell(edits, rows, columns, 1, 1, 'Robert')
    addRow(edits)
    setCell(edits, rows, columns, 2, 0, 3)

    expect(displayRows(edits, rows, columns)).toEqual([
      [1, 'Ana', 9.5],
      [2, 'Robert', null],
      [3, undefined, undefined],
    ])
    expect(isChanged(edits, rows.length, columns, 1, 1)).toBe(true)
    expect(isChanged(edits, rows.length, columns, 1, 0)).toBe(false)
    expect(isChanged(edits, rows.length, columns, 2, 0)).toBe(true)
  })

  it('removing an inserted row discards it; deleting a row twice restores it', () => {
    const edits = emptyEdits()
    addRow(edits)
    toggleDelete(edits, rows.length, 2)
    toggleDelete(edits, rows.length, 0)
    toggleDelete(edits, rows.length, 0)
    expect(changeCount(edits)).toBe(0)
  })

  it('duplicates a row without its primary key, including pending edits', () => {
    const edits = emptyEdits()
    setCell(edits, rows, columns, 0, 1, 'Anne')
    duplicateRow(edits, rows, columns, 0, ['id'])
    expect(buildChanges(edits, rows, columns, ['id'])).toContainEqual({ op: 'INSERT', values: { name: 'Anne', score: 9.5 } })
  })

  it('a deleted row does not also produce an update', () => {
    const edits = emptyEdits()
    setCell(edits, rows, columns, 0, 1, 'x')
    toggleDelete(edits, rows.length, 0)
    expect(buildChanges(edits, rows, columns, ['id'])).toEqual([{ op: 'DELETE', key: { id: 1 } }])
    expect(changeCount(edits)).toBe(1)
  })
})
