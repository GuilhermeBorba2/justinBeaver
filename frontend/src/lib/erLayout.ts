/** Automatic layout of an ER diagram: referenced tables on the left, the tables that point at them to the right. */

export interface LayoutNode {
  name: string
  height: number
  /** Names of the shown tables this one has a foreign key to (itself excluded). */
  refs: string[]
}

export interface LayoutOptions {
  boxWidth: number
  gapX?: number
  gapY?: number
  margin?: number
}

export type Positions = Record<string, { x: number; y: number }>

/** Layer of each table: 0 when it references nothing, else one more than the deepest table it references. Cycles end the walk. */
export function layers(nodes: LayoutNode[]): Map<string, number> {
  const byName = new Map(nodes.map((n) => [n.name, n]))
  const layer = new Map<string, number>()
  const visiting = new Set<string>()
  const depth = (name: string): number => {
    const known = layer.get(name)
    if (known !== undefined) return known
    if (visiting.has(name)) return 0
    visiting.add(name)
    let d = 0
    for (const ref of byName.get(name)?.refs ?? []) {
      if (ref !== name && byName.has(ref)) d = Math.max(d, depth(ref) + 1)
    }
    visiting.delete(name)
    layer.set(name, d)
    return d
  }
  for (const n of nodes) depth(n.name)
  return layer
}

/**
 * Connected tables go in columns by layer, ordered to keep linked tables level with each other (a few barycenter
 * passes); tables without any link are put in a grid below.
 */
export function layeredLayout(nodes: LayoutNode[], options: LayoutOptions): Positions {
  const { boxWidth, gapX = 80, gapY = 30, margin = 20 } = options
  const names = new Set(nodes.map((n) => n.name))
  const neighbours = new Map<string, Set<string>>(nodes.map((n) => [n.name, new Set<string>()]))
  for (const n of nodes) {
    for (const ref of n.refs) {
      if (ref !== n.name && names.has(ref)) {
        neighbours.get(n.name)!.add(ref)
        neighbours.get(ref)!.add(n.name)
      }
    }
  }
  const linked = nodes.filter((n) => neighbours.get(n.name)!.size > 0)
  const alone = nodes.filter((n) => neighbours.get(n.name)!.size === 0).sort((a, b) => a.name.localeCompare(b.name))
  const out: Positions = {}
  let bottom = margin

  if (linked.length) {
    const layer = layers(linked)
    const columns: LayoutNode[][] = []
    for (const n of [...linked].sort((a, b) => a.name.localeCompare(b.name))) {
      const l = layer.get(n.name) ?? 0
      ;(columns[l] ??= []).push(n)
    }
    const order = new Map<string, number>()
    columns.forEach((col) => col?.forEach((n, i) => order.set(n.name, i)))
    const centre = (n: LayoutNode) => {
      const around = [...neighbours.get(n.name)!].map((x) => order.get(x) ?? 0)
      return around.length ? around.reduce((a, b) => a + b, 0) / around.length : (order.get(n.name) ?? 0)
    }
    for (let pass = 0; pass < 4; pass++) {
      const sweep = pass % 2 === 0 ? columns.keys() : [...columns.keys()].reverse()
      for (const c of sweep) {
        const col = columns[c]
        if (!col) continue
        col.sort((a, b) => centre(a) - centre(b) || a.name.localeCompare(b.name))
        col.forEach((n, i) => order.set(n.name, i))
      }
    }
    columns.forEach((col, c) => {
      if (!col) return
      let y = margin
      for (const n of col) {
        out[n.name] = { x: margin + c * (boxWidth + gapX), y }
        y += n.height + gapY
      }
      bottom = Math.max(bottom, y)
    })
  }

  if (alone.length) {
    const perRow = Math.max(3, Math.ceil(Math.sqrt(alone.length)))
    let y = linked.length ? bottom + gapY : margin
    for (let i = 0; i < alone.length; i += perRow) {
      const row = alone.slice(i, i + perRow)
      row.forEach((n, k) => (out[n.name] = { x: margin + k * (boxWidth + gapX), y }))
      y += Math.max(...row.map((n) => n.height)) + gapY
    }
  }
  return out
}
