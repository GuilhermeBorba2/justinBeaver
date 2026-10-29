import { describe, expect, it } from 'vitest'
import type { ResultColumn } from '@/api/types'
import { columnLetter, crc32, sheetXml, toXlsx } from './xlsx'

describe('xlsx', () => {
  it('computes the standard CRC-32', () => {
    expect(crc32(new TextEncoder().encode('123456789')).toString(16)).toBe('cbf43926')
  })

  it('names columns like Excel', () => {
    expect([0, 25, 26, 27, 701, 702].map(columnLetter)).toEqual(['A', 'Z', 'AA', 'AB', 'ZZ', 'AAA'])
  })

  it('keeps numbers numeric and escapes text', () => {
    const columns: ResultColumn[] = [
      { name: 'id', typeName: 'int4', kind: 'number' },
      { name: 'name', typeName: 'text', kind: 'text' },
      { name: 'big', typeName: 'numeric', kind: 'number' },
    ]
    const sheet = sheetXml(columns, [[1, 'a<b & "c"', '12345678901234567890']])
    expect(sheet).toContain('<c r="A2"><v>1</v></c>')
    expect(sheet).toContain('a&lt;b &amp; &quot;c&quot;')
    // too many digits for a double: kept as text
    expect(sheet).toContain('<c r="C2" t="inlineStr"><is><t xml:space="preserve">12345678901234567890</t></is></c>')
  })

  it('packs a zip that starts with a local header and ends with the directory', () => {
    const bytes = toXlsx([{ name: 'x', typeName: 'int4', kind: 'number' }], [[1]], 'Sheet')
    expect(Array.from(bytes.slice(0, 4))).toEqual([0x50, 0x4b, 0x03, 0x04])
    const end = bytes.length - 22
    expect(Array.from(bytes.slice(end, end + 4))).toEqual([0x50, 0x4b, 0x05, 0x06])
    expect(bytes[end + 10]).toBe(6) // six parts
  })
})
