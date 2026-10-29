import { describe, expect, it } from 'vitest'
import { compareSchemas, reportText } from './compare'

describe('schema compare', () => {
  const left = { person: ['id', 'name', 'born'], pet: ['id', 'owner_id'], audit: ['at'] }
  const right = { PERSON: ['ID', 'NAME', 'EMAIL'], pet: ['id', 'owner_id'], invoice: ['id'] }

  it('finds missing tables and columns, ignoring case when asked', () => {
    const report = compareSchemas(left, right, true)
    expect(report.onlyLeft).toEqual(['audit'])
    expect(report.onlyRight).toEqual(['invoice'])
    expect(report.identical).toBe(1)
    expect(report.changed).toEqual([{ table: 'person', onlyLeft: ['born'], onlyRight: ['EMAIL'], typeDiffs: [] }])
  })

  it('treats different case as different names when case matters', () => {
    expect(compareSchemas(left, right, false).onlyLeft).toEqual(['audit', 'person'])
  })

  it('reports type differences and renders a readable report', () => {
    const report = compareSchemas({ t: ['a'] }, { t: ['a'] }, true, { t: { a: 'INTEGER' } }, { t: { a: 'bigint' } })
    expect(report.changed[0]!.typeDiffs).toEqual([{ column: 'a', left: 'INTEGER', right: 'bigint' }])
    expect(reportText(report, 'dev', 'prod')).toContain('~ column a: INTEGER → bigint')
  })
})
