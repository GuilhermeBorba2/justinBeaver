import { describe, expect, it, vi } from 'vitest'
import { loadFilters, pushFilter, saveFilters } from './recentFilters'

describe('recent filters', () => {
  it('puts the newest first, drops repeats and blanks', () => {
    expect(pushFilter(['a = 1', 'b = 2'], 'b = 2')).toEqual(['b = 2', 'a = 1'])
    expect(pushFilter(['a'], '   ')).toEqual(['a'])
    expect(pushFilter([], ' x > 1 ')).toEqual(['x > 1'])
  })

  it('keeps at most 15', () => {
    const many = Array.from({ length: 20 }, (_, i) => `c = ${i}`)
    expect(pushFilter(many, 'new')).toHaveLength(15)
  })

  it('round-trips through storage and survives junk', () => {
    const store = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
    })
    saveFilters('t1', ['a', 'b'])
    expect(loadFilters('t1')).toEqual(['a', 'b'])
    localStorage.setItem('jb.filters.bad', '{oops')
    expect(loadFilters('bad')).toEqual([])
  })
})
