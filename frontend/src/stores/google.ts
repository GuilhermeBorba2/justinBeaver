import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, ApiError } from '@/api/client'
import type { GoogleStatus, SyncResult, TransferKinds, TransferResult } from '@/api/types'
import { useConnections } from './connections'
import { usePrefs } from './prefs'
import { useUi } from './ui'
import { useWorkspace } from './workspace'

const AUTO_SYNC_MS = 2 * 60 * 1000
const SYNC_SOON_MS = 4000
const LOGIN_WAIT_MS = 3 * 60 * 1000

/**
 * Sign in with Google and the sync that follows it: connections (with passwords, encrypted before they leave this
 * computer), saved scripts and preferences are kept the same on every computer of the account.
 */
export const useGoogle = defineStore('google', () => {
  const status = ref<GoogleStatus | null>(null)
  const syncing = ref(false)
  const waitingForLogin = ref(false)
  const loginUrl = ref<string | null>(null)
  const lastResult = ref<SyncResult | null>(null)
  const ui = useUi()
  let soonTimer: ReturnType<typeof setTimeout> | undefined
  let loginAttempt = 0
  let loginTab: Window | null = null
  let lastShownError = ''

  const signedIn = () => status.value?.signedIn === true
  const syncEnabled = () => status.value?.sync.enabled === true

  async function refresh() {
    try {
      status.value = await api.googleStatus()
    } catch {
      // server unreachable: keep what is shown
    }
    return status.value
  }

  /** Opens Google's consent page in the browser and waits for the sign-in to complete there. */
  async function login(): Promise<boolean> {
    if (waitingForLogin.value) return false
    const attempt = ++loginAttempt
    waitingForLogin.value = true
    loginUrl.value = null
    // Open during the click, before awaiting HTTP, so browsers keep the user gesture.
    const desktop = window.justinBeaverDesktop
    const tab = desktop ? null : window.open('about:blank', '_blank')
    loginTab = tab
    if (tab) tab.opener = null
    try {
      const { url } = desktop ? await desktop.openGoogleSignIn() : await api.googleLogin()
      if (attempt !== loginAttempt) return false
      loginUrl.value = url
      if (tab) tab.location.replace(url)
      const deadline = Date.now() + LOGIN_WAIT_MS
      while (Date.now() < deadline && attempt === loginAttempt) {
        await new Promise((r) => setTimeout(r, 1500))
        if (attempt !== loginAttempt) return false
        await refresh()
        if (attempt !== loginAttempt) return false
        if (signedIn()) return true
        if (tab?.closed) return false
      }
      if (attempt !== loginAttempt) return false
      throw new Error('Google sign-in timed out. Please try again.')
    } catch (e) {
      tab?.close()
      if (attempt !== loginAttempt) return false
      throw e
    } finally {
      if (attempt === loginAttempt) {
        waitingForLogin.value = false
        loginUrl.value = null
        loginTab = null
      }
    }
  }

  function cancelLogin() {
    loginAttempt++
    waitingForLogin.value = false
    loginUrl.value = null
    loginTab?.close()
    loginTab = null
  }

  async function logout() {
    await api.googleLogout()
    await refresh()
  }

  async function enableSync(passphrase: string): Promise<SyncResult> {
    await api.syncEnable(passphrase)
    await refresh()
    return (await syncNow(true))!
  }

  /** Pull, merge and push; what changed locally is reloaded into the screen. */
  async function syncNow(throwErrors = false): Promise<SyncResult | undefined> {
    if (syncing.value || !signedIn() || !syncEnabled()) return undefined
    syncing.value = true
    try {
      const prefs = usePrefs()
      const { settings, updatedAt } = prefs.syncPayload()
      const result = await api.syncNow(settings, updatedAt)
      lastResult.value = result
      if (result.settingsChanged) await prefs.applySync(result.settings, result.settingsUpdatedAt)
      if (result.connectionsChanged) await useConnections().load()
      if (result.scriptsChanged) await useWorkspace().reloadScripts()
      lastShownError = ''
      await refresh()
      return result
    } catch (e) {
      await refresh()
      const message = e instanceof Error ? e.message : String(e)
      if (throwErrors) throw e
      // one toast per distinct problem: the background sync would repeat it every two minutes
      if (message !== lastShownError) {
        lastShownError = message
        ui.error(`Sync: ${message}`)
      }
      if (e instanceof ApiError && e.status === 401) await refresh()
      return undefined
    } finally {
      syncing.value = false
    }
  }

  /** "Save to Google Drive": what is chosen here replaces the Drive copy of the same items. */
  async function exportToDrive(kinds: TransferKinds): Promise<TransferResult> {
    const { settings, updatedAt } = usePrefs().syncPayload()
    syncing.value = true
    try {
      const result = await api.syncExport(kinds, kinds.preferences ? settings : undefined, kinds.preferences ? updatedAt : undefined)
      await refresh()
      return result
    } finally {
      syncing.value = false
    }
  }

  /** "Import from Google Drive": what is chosen replaces the same items here; the screen reloads what changed. */
  async function importFromDrive(kinds: TransferKinds): Promise<TransferResult> {
    syncing.value = true
    try {
      const result = await api.syncImport(kinds)
      if (result.preferences) await usePrefs().applySync(result.settings, result.settingsUpdatedAt)
      if (result.connections) await useConnections().load()
      if (result.scripts) await useWorkspace().reloadScripts()
      await refresh()
      return result
    } finally {
      syncing.value = false
    }
  }

  /** After a change made here: sync shortly, once, however many changes come in a row. */
  function syncSoon() {
    if (!signedIn() || !syncEnabled()) return
    clearTimeout(soonTimer)
    soonTimer = setTimeout(() => void syncNow(), SYNC_SOON_MS)
  }

  /** At startup: learn who is signed in, pull right away, then keep syncing in the background. */
  async function start() {
    await refresh()
    if (signedIn() && syncEnabled()) await syncNow()
    setInterval(() => {
      if (document.visibilityState === 'visible') void syncNow()
    }, AUTO_SYNC_MS)
    window.addEventListener('focus', () => {
      const last = status.value?.sync.lastSyncAt ?? 0
      if (Date.now() - last > 30_000) void syncNow()
    })
  }

  return { status, syncing, waitingForLogin, loginUrl, lastResult, refresh, login, cancelLogin, logout, enableSync, syncNow, exportToDrive, importFromDrive, syncSoon, start }
})
