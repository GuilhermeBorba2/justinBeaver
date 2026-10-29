/**
 * When the preferences last changed on this computer: the sync keeps the newer copy. Stored next to the preferences
 * themselves, so it survives restarts.
 */
const STAMP_KEY = 'justinbeaver.settings.updatedAt'

export function settingsStamp(): number {
  try {
    return Number(localStorage.getItem(STAMP_KEY)) || 0
  } catch {
    return 0
  }
}

export function setSettingsStamp(at: number): void {
  try {
    localStorage.setItem(STAMP_KEY, String(at))
  } catch {
    // best effort
  }
}

export function touchSettings(): void {
  setSettingsStamp(Date.now())
}

/** The localStorage keys that travel with the account. */
export const SYNCED_KEYS = ['justinbeaver.prefs', 'justinbeaver.snippets'] as const
