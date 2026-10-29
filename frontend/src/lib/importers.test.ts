// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import type { ResultColumn } from '@/api/types'
import { toJson, toXml } from './export'
import { parseImport } from './importers'

describe('import files', () => {
  it('reads a JSON array, unioning keys and keeping nulls and nested values', () => {
    expect(parseImport('x.json', '[{"id":1,"tags":["a"]},{"id":2,"name":null,"ok":true}]')).toEqual({
      header: ['id', 'tags', 'name', 'ok'],
      records: [
        ['1', '["a"]', null, null],
        ['2', null, null, 'true'],
      ],
    })
  })

  it('reads JSON rows nested under "data"', () => {
    expect(parseImport('x.json', '{"data":[{"a":1}]}').records).toEqual([['1']])
  })

  it('rejects JSON that is not rows', () => {
    expect(() => parseImport('x.json', '{"a":1}')).toThrow(/array of objects/)
  })

  it('round-trips the JSON and XML exports', () => {
    const columns: ResultColumn[] = [
      { name: 'id', typeName: 'INT', kind: 'number' },
      { name: 'first name', typeName: 'TEXT', kind: 'text' },
    ]
    const rows = [
      [1, '<b>&'],
      [2, null],
    ]
    const expected = { header: ['id', 'first name'], records: [['1', '<b>&'], ['2', null]] }
    expect(parseImport('t.json', toJson(columns, rows))).toEqual(expected)
    expect(parseImport('t.xml', toXml(columns, rows, 't'))).toEqual(expected)
  })

  it('reads CSV with empty fields as NULL', () => {
    expect(parseImport('x.csv', 'a,b\n1,\n')).toEqual({ header: ['a', 'b'], records: [['1', null]] })
  })
})
