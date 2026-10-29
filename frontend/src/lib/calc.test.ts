import { describe, expect, it } from 'vitest'
import { calc, cellsIn, exactDecimalSum, findCells, rangeText } from './calc'

describe('calc', () => {
  it('sums numbers and numeric strings, skipping nulls', () => {
    const stats = calc([1, '2.5', null, undefined, 0.1, '0.2'])
    expect(stats).toMatchObject({ cells: 6, nulls: 2, numbers: 4, distinct: 4, sum: 3.8, avg: 0.95, min: 0.1, max: 2.5 })
  })

  it('compares text when a value is not numeric', () => {
    const stats = calc(['pear', 'apple', 10, 'pear'])
    expect(stats).toMatchObject({ numbers: 1, distinct: 3, sum: 10, min: '10', max: 'pear' })
  })

  it('sums decimals exactly', () => {
    expect(exactDecimalSum(['9007199254740993.10', '0.20', '-1'])).toBe('9007199254740992.3')
    expect(exactDecimalSum(['0.1', '0.2'])).toBe('0.3')
    expect(exactDecimalSum(['-0.5', '0.25'])).toBe('-0.25')
    expect(exactDecimalSum(['1e3'])).toBeNull()
  })

  it('has no aggregates for an all-null selection', () => {
    expect(calc([null, null])).toMatchObject({ nulls: 2, sum: null, avg: null, min: null, max: null })
  })
})

describe('ranges', () => {
  const rows = [
    [1, 'a', null],
    [2, 'b', 'x'],
    [3, 'c', 'y'],
  ]

  it('reads a rectangle row by row', () => {
    expect(cellsIn(rows, { r1: 1, c1: 0, r2: 2, c2: 1 })).toEqual([2, 'b', 3, 'c'])
  })

  it('copies tab-separated text with NULL as empty', () => {
    expect(rangeText(rows, { r1: 0, c1: 1, r2: 1, c2: 2 })).toBe('a\t\nb\tx')
  })

  it('skips hidden columns', () => {
    expect(cellsIn(rows, { r1: 0, c1: 0, r2: 0, c2: 2 }, [0, 2])).toEqual([1, null])
    expect(rangeText(rows, { r1: 1, c1: 0, r2: 1, c2: 2 }, [0, 2])).toBe('2\tx')
  })

  it('finds cells by text, NULL included', () => {
    expect(findCells(rows, [0, 1, 2], 'B')).toEqual([{ row: 1, col: 1 }])
    expect(findCells(rows, [0, 1, 2], 'null')).toEqual([{ row: 0, col: 2 }])
    expect(findCells(rows, [0, 1], 'x')).toEqual([])
  })
})
