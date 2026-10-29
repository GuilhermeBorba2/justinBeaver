import { describe, expect, it } from 'vitest'
import { looksReadOnly, writingStatements } from './guard'

describe('read-only guard', () => {
  it('accepts queries, also after comments', () => {
    expect(looksReadOnly('  select 1')).toBe(true)
    expect(looksReadOnly('-- note\n/* x */ WITH a AS (select 1) select * from a')).toBe(true)
    expect(looksReadOnly('explain select 1')).toBe(true)
  })

  it('flags anything else', () => {
    expect(writingStatements(['select 1', 'update t set a = 1', 'drop table t'])).toEqual(['update t set a = 1', 'drop table t'])
  })
})
