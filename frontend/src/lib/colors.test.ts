import { describe, expect, it } from 'vitest'
import { accentVars, backgroundIsDark, backgroundVars, customVars, isDarkColor, luminance, mix, parseHex, readableOn, toHex } from './colors'

describe('hex colors', () => {
  it('parses 3 and 6 digit forms, with or without #', () => {
    expect(parseHex('#fff')).toEqual([255, 255, 255])
    expect(parseHex('1e1f22')).toEqual([30, 31, 34])
    expect(parseHex('#ABCDEF')).toEqual([171, 205, 239])
  })

  it('rejects anything else', () => {
    expect(parseHex('')).toBeNull()
    expect(parseHex('red')).toBeNull()
    expect(parseHex('#12345')).toBeNull()
  })

  it('round-trips and clamps', () => {
    expect(toHex([30, 31, 34])).toBe('#1e1f22')
    expect(toHex([300, -4, 0.4])).toBe('#ff0000')
  })
})

describe('luminance and contrast', () => {
  it('orders black below white', () => {
    expect(luminance([0, 0, 0])).toBe(0)
    expect(luminance([255, 255, 255])).toBeCloseTo(1)
  })

  it('picks readable text for the accent', () => {
    expect(readableOn([255, 255, 255])).toEqual([27, 26, 24])
    expect(readableOn([0, 0, 0])).toEqual([255, 255, 255])
    expect(toHex(readableOn(parseHex('#eab308')!))).toBe('#1b1a18')
  })

  it('tells dark backgrounds from light ones', () => {
    expect(isDarkColor(parseHex('#0f172a')!)).toBe(true)
    expect(isDarkColor(parseHex('#e8f0fb')!)).toBe(false)
    expect(backgroundIsDark('')).toBeNull()
    expect(backgroundIsDark('#000')).toBe(true)
  })
})

describe('palettes', () => {
  it('lightens surfaces on a dark background and keeps text light', () => {
    const v = backgroundVars(parseHex('#000000')!)
    expect(v['--bg']).toBe('#000000')
    expect(luminance(parseHex(v['--panel']!)!)).toBeGreaterThan(0)
    expect(luminance(parseHex(v['--text']!)!)).toBeGreaterThan(0.6)
    expect(v['color-scheme']).toBe('dark')
  })

  it('keeps text dark on a light background', () => {
    const v = backgroundVars(parseHex('#e8f0fb')!)
    expect(luminance(parseHex(v['--text']!)!)).toBeLessThan(0.1)
    expect(v['color-scheme']).toBe('light')
  })

  it('blends the accent into the surface for the soft highlight', () => {
    const v = accentVars([59, 130, 246], [255, 255, 255])
    expect(v['--accent']).toBe('#3b82f6')
    expect(v['--accent-soft']).toBe(toHex(mix([255, 255, 255], [59, 130, 246], 0.2)))
    expect(v['--selected']).toBe(v['--accent-soft'])
  })

  it('sets nothing for the defaults and ignores invalid values', () => {
    expect(customVars({ accent: '', background: '' }, false)).toEqual({})
    expect(customVars({ accent: 'nope', background: 'nope' }, true)).toEqual({})
  })

  it('combines accent and background', () => {
    const v = customVars({ accent: '#3b82f6', background: '#0f172a' }, false)
    expect(v['--accent']).toBe('#3b82f6')
    expect(v['--bg']).toBe('#0f172a')
    expect(v['--accent-contrast']).toBe('#ffffff')
  })
})
