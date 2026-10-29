import { describe, expect, it } from 'vitest'
import type { DdlContext } from './ddl'
import { equalsCondition, groupKeys, linkFilter } from './filters'

const pg: DdlContext = { dialect: 'postgresql', quote: '"', supportsCatalogs: false, supportsSchemas: true }

describe('filters', () => {
  it('writes literals by kind', () => {
    expect(equalsCondition(pg, 'id', 'number', '12')).toBe('"id" = 12')
    expect(equalsCondition(pg, 'name', 'text', "O'Hara")).toBe('"name" = \'O\'\'Hara\'')
    expect(equalsCondition(pg, 'x', 'text', null)).toBe('"x" IS NULL')
  })

  it('groups composite keys and builds the filter of one row', () => {
    const [link, other] = groupKeys([
      { name: 'fk_line', column: 'order_id', refCatalog: null, refSchema: 'sales', refTable: 'orders', refColumn: 'id' },
      { name: 'fk_line', column: 'shop', refCatalog: null, refSchema: 'sales', refTable: 'orders', refColumn: 'shop' },
      { name: 'fk_item', column: 'item_id', refCatalog: null, refSchema: 'sales', refTable: 'items', refColumn: 'id' },
    ])
    expect(link!.pairs).toHaveLength(2)
    expect(other!.table).toBe('items')
    const columns = [
      { name: 'order_id', kind: 'number' },
      { name: 'shop', kind: 'text' },
    ]
    expect(linkFilter(pg, link!, columns, [7, 'north'])).toBe('"id" = 7 AND "shop" = \'north\'')
    expect(linkFilter(pg, link!, columns, [null, 'north'])).toBeUndefined()
  })
})
