import { describe, expect, it } from 'vitest'
import { cleanMenu, flattenMenu, SEP, tr } from './menu'

const act = () => undefined

describe('cleanMenu', () => {
  it('drops leading, trailing and repeated separators', () => {
    const items = cleanMenu([SEP, { label: 'A', action: act }, SEP, SEP, { label: 'B', action: act }, SEP])
    expect(items.map((i) => i.label ?? '-')).toEqual(['A', '-', 'B'])
  })

  it('drops falsy entries and empty submenus, then the separator they leave behind', () => {
    const items = cleanMenu([
      { label: 'A', action: act },
      SEP,
      false,
      { label: 'Empty', children: [SEP] },
      SEP,
      { label: 'B', action: act },
    ])
    expect(items.map((i) => i.label ?? '-')).toEqual(['A', '-', 'B'])
  })

  it('cleans submenus recursively', () => {
    const [sub] = cleanMenu([{ label: 'More', children: [SEP, { label: 'X', action: act }, SEP] }])
    expect(sub!.children!.map((i) => i.label)).toEqual(['X'])
  })
})

describe('flattenMenu', () => {
  it('lists runnable leaves with their submenu path', () => {
    const flat = flattenMenu([
      { label: 'Run', action: act },
      { label: 'Tools', children: [{ label: 'Sessions', action: act }, { label: 'Disabled leaf' }] },
      SEP,
    ])
    expect(flat.map((f) => f.label)).toEqual(['Run', 'Tools › Sessions'])
  })
})

describe('tr', () => {
  it('picks Portuguese for pt locales and English otherwise', () => {
    expect(tr('Open', 'Abrir', 'pt-BR')).toBe('Abrir')
    expect(tr('Open', 'Abrir', 'en-US')).toBe('Open')
  })
})
