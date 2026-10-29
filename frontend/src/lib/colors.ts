/** Custom colors: from one accent color and one background color to every CSS variable the app uses. */

export type Rgb = [number, number, number]

/** `#rgb` or `#rrggbb` (any case) to channels; null for anything else. */
export function parseHex(value: string): Rgb | null {
  const m = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(value.trim())
  if (!m) return null
  let hex = m[1]!
  if (hex.length === 3) hex = [...hex].map((c) => c + c).join('')
  return [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16)) as Rgb
}

export function toHex([r, g, b]: Rgb): string {
  return '#' + [r, g, b].map((c) => Math.round(Math.min(255, Math.max(0, c))).toString(16).padStart(2, '0')).join('')
}

/** `amount` of `to` mixed into `from` (0 = from, 1 = to). */
export function mix(from: Rgb, to: Rgb, amount: number): Rgb {
  return [0, 1, 2].map((i) => from[i]! + (to[i]! - from[i]!) * amount) as Rgb
}

/** Relative luminance (WCAG), 0 black … 1 white. */
export function luminance([r, g, b]: Rgb): number {
  const [lr, lg, lb] = [r, g, b].map((c) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }) as Rgb
  return 0.2126 * lr + 0.7152 * lg + 0.0722 * lb
}

const WHITE: Rgb = [255, 255, 255]
const BLACK: Rgb = [0, 0, 0]

export const isDarkColor = (rgb: Rgb) => luminance(rgb) < 0.2

/** Text that stays readable on `rgb`: white or near-black. */
export function readableOn(rgb: Rgb): Rgb {
  return luminance(rgb) > 0.4 ? [27, 26, 24] : WHITE
}

/** Status colors that keep their meaning (red/green/amber) but fit a dark or a light background. */
const STATUS = {
  dark: { '--danger': '#e0695d', '--ok': '#6cc183', '--warn': '#e0b04f' },
  light: { '--danger': '#b3382c', '--ok': '#3b8a4e', '--warn': '#b7791f' },
}

/** Palette of the whole app from a background color: surfaces step away from it, text and borders follow its lightness. */
export function backgroundVars(background: Rgb): Record<string, string> {
  const dark = isDarkColor(background)
  const vars: Record<string, string> = dark
    ? {
        '--bg': toHex(background),
        '--panel': toHex(mix(background, WHITE, 0.04)),
        '--panel-2': toHex(mix(background, WHITE, 0.09)),
        '--border': toHex(mix(background, WHITE, 0.17)),
        '--row-alt': toHex(mix(background, WHITE, 0.06)),
        '--text': toHex(mix(background, WHITE, 0.9)),
        '--muted': toHex(mix(background, WHITE, 0.55)),
        ...STATUS.dark,
      }
    : {
        '--bg': toHex(background),
        '--panel': toHex(mix(background, WHITE, 0.75)),
        '--panel-2': toHex(mix(background, BLACK, 0.04)),
        '--border': toHex(mix(background, BLACK, 0.14)),
        '--row-alt': toHex(mix(background, WHITE, 0.5)),
        '--text': toHex(mix(background, BLACK, 0.86)),
        '--muted': toHex(mix(background, BLACK, 0.5)),
        ...STATUS.light,
      }
  vars['color-scheme'] = dark ? 'dark' : 'light'
  return vars
}

/** Accent variables; `surface` is the panel color the soft highlight is blended into. */
export function accentVars(accent: Rgb, surface: Rgb): Record<string, string> {
  const soft = toHex(mix(surface, accent, luminance(surface) < 0.2 ? 0.28 : 0.2))
  return {
    '--accent': toHex(accent),
    '--accent-contrast': toHex(readableOn(accent)),
    '--accent-soft': soft,
    '--selected': soft,
  }
}

export interface Customization {
  accent: string
  background: string
}

/**
 * Every CSS variable to set for the chosen colors; empty when both are default.
 * `systemDark` decides the surface the accent is blended into when only the accent was changed.
 */
export function customVars({ accent, background }: Customization, systemDark: boolean): Record<string, string> {
  const bg = parseHex(background)
  const ac = parseHex(accent)
  const vars: Record<string, string> = bg ? backgroundVars(bg) : {}
  if (ac) {
    const surface = vars['--panel'] ? parseHex(vars['--panel'])! : systemDark ? ([35, 33, 32] as Rgb) : WHITE
    Object.assign(vars, accentVars(ac, surface))
  }
  return vars
}

/** True when the chosen background is dark, so editors and other themed parts switch with it; null when none is chosen. */
export function backgroundIsDark(background: string): boolean | null {
  const rgb = parseHex(background)
  return rgb ? isDarkColor(rgb) : null
}

export const ACCENT_PRESETS = [
  { name: 'Orange', value: '' },
  { name: 'Blue', value: '#3b82f6' },
  { name: 'Teal', value: '#14b8a6' },
  { name: 'Green', value: '#22c55e' },
  { name: 'Purple', value: '#8b5cf6' },
  { name: 'Pink', value: '#ec4899' },
  { name: 'Red', value: '#ef4444' },
  { name: 'Yellow', value: '#eab308' },
]

export const BACKGROUND_PRESETS = [
  { name: 'Default', value: '' },
  { name: 'Black', value: '#000000' },
  { name: 'Graphite', value: '#1e1f22' },
  { name: 'Navy', value: '#0f172a' },
  { name: 'Forest', value: '#0f1f17' },
  { name: 'Plum', value: '#1d1526' },
  { name: 'Paper', value: '#f6f5f3' },
  { name: 'Sky', value: '#e8f0fb' },
  { name: 'White', value: '#ffffff' },
]
