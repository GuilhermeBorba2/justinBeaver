import { describe, expect, it } from 'vitest'
import type { ResultColumn } from '@/api/types'
import type { DdlContext } from './ddl'
import { compareData, syncScript } from './datacompare'

const pg: DdlContext = { dialect: 'postgresql', quote: '"', supportsCatalogs: false, supportsSchemas: true }
const cols = (...names: string[]): ResultColumn[] => names.map((name) => ({ name, typeName: 'text', kind: name === 'id' ? 'number' : 'text' }))

describe('compareData', () => {
  const left = { columns: cols('id', 'name', 'only_left'), rows: [[1, 'a', 'x'], [2, 'b', 'y'], [3, 'c', 'z']] }
  const right = { columns: cols('ID', 'name'), rows: [['1', 'a'], [2, 'B'], [4, 'd']] }

  it('finds missing, extra and changed rows by key', () => {
    const r = compareData(left, right, ['id'])
    expect(r.columns).toEqual(['id', 'name'])
    expect(r.same).toBe(1)
    expect(r.onlyLeft).toEqual([[3, 'c']])
    expect(r.onlyRight).toEqual([[4, 'd']])
    expect(r.changed).toEqual([{ key: [2], diffs: [{ column: 'name', left: 'b', right: 'B' }] }])
  })

  it('writes the sync script for the right table', () => {
    const r = compareData(left, right, ['id'])
    const sql = syncScript(pg, { catalog: null, schema: 'public', name: 't' }, r, { id: 'number', name: 'text' }, true)
    expect(sql).toEqual([
      `INSERT INTO "public"."t" ("id", "name") VALUES (3, 'c')`,
      `UPDATE "public"."t" SET "name" = 'b' WHERE "id" = 2`,
      `DELETE FROM "public"."t" WHERE "id" = 4`,
    ])
  })

  it('refuses a key that is not on both sides', () => {
    expect(() => compareData(left, right, ['only_left'])).toThrow(/not on both/)
  })
})
