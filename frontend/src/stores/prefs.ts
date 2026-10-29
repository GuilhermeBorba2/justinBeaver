import { defineStore } from 'pinia'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { backgroundIsDark, customVars } from '@/lib/colors'
import { uiLanguage, type UiLanguage } from '@/lib/menu'
import { SYNCED_KEYS, setSettingsStamp, settingsStamp, touchSettings } from '@/lib/settingsStamp'

/** Preferences (DBeaver's Window → Preferences), kept in this browser. */
export type Theme = 'system' | 'light' | 'dark'

export interface Bookmark {
  connectionId: string
  catalog: string | null
  schema: string | null
  table: string
}

interface Saved {
  theme: Theme
  /** Language of the menus and dialogs: 'auto' follows the browser. */
  language: UiLanguage
  /** Hex accent color replacing the orange; '' keeps the default. */
  accentColor: string
  /** Hex background replacing the theme's (surfaces, borders and text follow it); '' keeps the theme's. */
  backgroundColor: string
  hideSystemObjects: boolean
  editorFontSize: number
  /** Type `select` and it becomes `SELECT` when the word ends. */
  uppercaseKeywords: boolean
  defaultMaxRows: number
  confirmProductionWrites: boolean
  bookmarks: Bookmark[]
}

const KEY = 'justinbeaver.prefs'

const DEFAULTS: Saved = {
  theme: 'system',
  language: 'auto',
  accentColor: '',
  backgroundColor: '',
  hideSystemObjects: false,
  editorFontSize: 13,
  uppercaseKeywords: false,
  defaultMaxRows: 200,
  confirmProductionWrites: true,
  bookmarks: [],
}

function restore(): Saved {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(KEY) ?? '{}')
    return { ...DEFAULTS, ...(typeof parsed === 'object' && parsed ? parsed : {}) }
  } catch {
    return { ...DEFAULTS }
  }
}

export const sameBookmark = (a: Bookmark, b: Bookmark) =>
  a.connectionId === b.connectionId && a.catalog === b.catalog && a.schema === b.schema && a.table === b.table

export const usePrefs = defineStore('prefs', () => {
  const state = reactive<Saved>(restore())

  const media = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null
  const systemDark = ref(media?.matches ?? false)
  media?.addEventListener('change', (e) => (systemDark.value = e.matches))
  /** A chosen background decides light or dark (editor theme, native controls); otherwise the theme does. */
  const dark = computed(() => backgroundIsDark(state.backgroundColor) ?? (state.theme === 'system' ? systemDark.value : state.theme === 'dark'))

  /** CSS variables set on the page for the custom colors, so they can be removed when a color goes back to the default. */
  let applied: string[] = []
  function applyColors() {
    if (typeof document === 'undefined') return
    const style = document.documentElement.style
    for (const name of applied) style.removeProperty(name)
    const vars = customVars({ accent: state.accentColor, background: state.backgroundColor }, dark.value)
    for (const [name, value] of Object.entries(vars)) style.setProperty(name, value)
    applied = Object.keys(vars)
  }
  watch(() => [state.accentColor, state.backgroundColor, dark.value], applyColors, { immediate: true })
  watch(
    () => state.language,
    (language) => {
      uiLanguage.value = language === 'en' || language === 'pt' ? language : 'auto'
      if (typeof document !== 'undefined') document.documentElement.lang = uiLanguage.value === 'auto' ? navigator.language : uiLanguage.value
    },
    { immediate: true },
  )

  watch(
    () => state.theme,
    (theme) => {
      if (typeof document === 'undefined') return
      if (theme === 'system') delete document.documentElement.dataset.theme
      else document.documentElement.dataset.theme = theme
    },
    { immediate: true },
  )

  /** True while settings pulled from the cloud are being applied: they are not a change made here. */
  let applyingRemote = false

  watch(
    state,
    () => {
      try {
        localStorage.setItem(KEY, JSON.stringify(state))
      } catch {
        // best effort
      }
      if (!applyingRemote) touchSettings()
    },
    { deep: true },
  )

  /** The synced preferences (and templates) as stored, with when they last changed here. */
  function syncPayload(): { settings: Record<string, string>; updatedAt: number } {
    const settings: Record<string, string> = {}
    for (const key of SYNCED_KEYS) {
      try {
        const value = localStorage.getItem(key)
        if (value !== null) settings[key] = value
      } catch {
        // storage unavailable
      }
    }
    return { settings, updatedAt: settingsStamp() }
  }

  /** Preferences pulled from the cloud replace the ones here. */
  async function applySync(settings: Record<string, string>, updatedAt: number) {
    applyingRemote = true
    try {
      for (const key of SYNCED_KEYS) {
        if (key in settings) localStorage.setItem(key, settings[key]!)
      }
      setSettingsStamp(updatedAt)
      Object.assign(state, { ...DEFAULTS, ...restore() })
      await nextTick()
    } finally {
      applyingRemote = false
    }
  }

  function isBookmarked(b: Bookmark) {
    return state.bookmarks.some((x) => sameBookmark(x, b))
  }

  function toggleBookmark(b: Bookmark) {
    const index = state.bookmarks.findIndex((x) => sameBookmark(x, b))
    if (index >= 0) state.bookmarks.splice(index, 1)
    else state.bookmarks.push({ ...b })
  }

  function reset() {
    Object.assign(state, { ...DEFAULTS, bookmarks: state.bookmarks })
  }

  return { state, dark, isBookmarked, toggleBookmark, reset, syncPayload, applySync }
})
