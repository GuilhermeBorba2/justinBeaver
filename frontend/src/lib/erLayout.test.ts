import { describe, expect, it } from 'vitest'
import { layeredLayout, layers, type LayoutNode } from './erLayout'

const node = (name: string, refs: string[] = [], height = 100): LayoutNode => ({ name, refs, height })
const opts = { boxWidth: 200, gapX: 50, gapY: 20, margin: 10 }

describe('layers', () => {
  it('puts referenced tables before the ones that reference them', () => {
    const l = layers([node('order', ['customer', 'product']), node('customer'), node('product'), node('item', ['order'])])
    expect(l.get('customer')).toBe(0)
    expect(l.get('order')).toBe(1)
    expect(l.get('item')).toBe(2)
  })

  it('does not loop on cycles or self references', () => {
    const l = layers([node('a', ['b']), node('b', ['a']), node('tree', ['tree'])])
    expect(l.size).toBe(3)
    expect(l.get('tree')).toBe(0)
  })
})

describe('layeredLayout', () => {
  it('lays columns left to right by layer', () => {
    const p = layeredLayout([node('item', ['order']), node('order', ['customer']), node('customer')], opts)
    expect(p.customer!.x).toBeLessThan(p.order!.x)
    expect(p.order!.x).toBeLessThan(p.item!.x)
    expect(p.customer!.x).toBe(10)
    expect(p.order!.x).toBe(10 + 250)
  })

  it('stacks a column without overlap', () => {
    const p = layeredLayout([node('a', ['c'], 80), node('b', ['c'], 120), node('c')], opts)
    const [first, second] = [p.a!, p.b!].sort((x, y) => x.y - y.y)
    expect(first!.x).toBe(second!.x)
    expect(second!.y - first!.y).toBeGreaterThanOrEqual(80 + 20)
  })

  it('puts tables without links in a grid below the connected ones', () => {
    const p = layeredLayout([node('a', ['b']), node('b'), node('x'), node('y'), node('z')], opts)
    expect(p.x!.y).toBeGreaterThan(p.a!.y + 100)
    expect(p.x!.y).toBe(p.y!.y)
    expect(p.x!.x).toBeLessThan(p.y!.x)
  })

  it('places every table exactly once, whatever the links', () => {
    const nodes = [node('a', ['b']), node('b', ['a']), node('c', ['c']), node('d', ['missing'])]
    const p = layeredLayout(nodes, opts)
    expect(Object.keys(p).sort()).toEqual(['a', 'b', 'c', 'd'])
  })

  it('handles no tables', () => {
    expect(layeredLayout([], opts)).toEqual({})
  })
})
