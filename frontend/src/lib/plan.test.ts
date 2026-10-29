import { describe, expect, it } from 'vitest'
import { parsePlan } from './plan'

const json = JSON.stringify([
  {
    Plan: {
      'Node Type': 'Hash Join',
      'Join Type': 'Inner',
      'Startup Cost': 1,
      'Total Cost': 40,
      'Plan Rows': 10,
      'Hash Cond': '(o.customer_id = c.id)',
      Plans: [
        { 'Node Type': 'Seq Scan', 'Relation Name': 'orders', Alias: 'o', 'Total Cost': 20, 'Plan Rows': 100, Filter: '(total > 5)' },
        { 'Node Type': 'Index Scan', 'Relation Name': 'customers', 'Index Name': 'customers_pkey', 'Total Cost': 10, 'Plan Rows': 3 },
      ],
    },
    'Planning Time': 0.2,
    'Execution Time': 1.5,
  },
])

describe('parsePlan', () => {
  it('builds the tree with details, conditions and cost shares', () => {
    const { root, planningMs, executionMs } = parsePlan(json)
    expect(root.type).toBe('Hash Join')
    expect(root.detail).toBe('Inner join')
    expect(root.conditions).toEqual(['Hash Cond: (o.customer_id = c.id)'])
    expect(root.children.map((c) => c.detail)).toEqual(['on orders o', 'on customers using customers_pkey'])
    expect(root.children[0]!.share).toBe(0.5)
    expect(root.children[0]!.conditions).toEqual(['Filter: (total > 5)'])
    expect([planningMs, executionMs]).toEqual([0.2, 1.5])
  })

  it('rejects other output', () => {
    expect(() => parsePlan('[1]')).toThrow()
  })
})
