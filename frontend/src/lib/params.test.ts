import { describe, expect, it } from 'vitest'
import { findParams, substituteParams } from './params'

describe('sql variables', () => {
  it('finds :name and ${name} in order, once each', () => {
    expect(findParams('select * from t where a = :id and b = ${since} or c = :id')).toEqual(['id', 'since'])
  })

  it('ignores casts, assignments, strings, quoted identifiers and comments', () => {
    const sql = `select x::int, ':nope', "a:b", $$ :body $$ -- :comment
      /* :block */ from t where y := 1 and z = :real`
    expect(findParams(sql)).toEqual(['real'])
  })

  it('substitutes values as SQL text and keeps unknown variables', () => {
    expect(substituteParams("select :id, ${who}, ':id', :other", { id: '42', who: "'ana'" })).toBe(
      "select 42, 'ana', ':id', :other",
    )
  })

  it('does not treat array slices or time literals inside strings as variables', () => {
    expect(findParams("select arr[1:2], '10:30' from t")).toEqual([])
  })
})
