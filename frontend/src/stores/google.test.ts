// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api } from '@/api/client'
import { useGoogle } from './google'

vi.mock('@/api/client', () => ({
  api: { googleLogin: vi.fn(), googleStatus: vi.fn() },
  ApiError: class extends Error {},
}))
vi.mock('./connections', () => ({ useConnections: vi.fn() }))
vi.mock('./prefs', () => ({ usePrefs: vi.fn() }))
vi.mock('./workspace', () => ({ useWorkspace: vi.fn() }))

const signedOut = { configured: true, signedIn: false, sync: { enabled: false, lastSyncAt: null, lastError: null } }
const url = 'https://accounts.google.com/o/oauth2/v2/auth?state=test'

function popup() {
  return { opener: window, closed: false, location: { replace: vi.fn() }, close: vi.fn() }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  vi.mocked(api.googleLogin).mockResolvedValue({ url })
  vi.mocked(api.googleStatus).mockResolvedValue(signedOut)
})

afterEach(() => {
  delete window.justinBeaverDesktop
  vi.restoreAllMocks()
  vi.clearAllMocks()
  vi.useRealTimers()
})

describe('Google sign-in', () => {
  it('uses the desktop bridge to open Google outside Electron and detects completion', async () => {
    const open = vi.spyOn(window, 'open')
    window.justinBeaverDesktop = { openGoogleSignIn: vi.fn().mockResolvedValue({ url }) }
    const google = useGoogle()
    const login = google.login()
    await vi.advanceTimersByTimeAsync(0)
    expect(window.justinBeaverDesktop.openGoogleSignIn).toHaveBeenCalledOnce()
    expect(open).not.toHaveBeenCalled()
    expect(api.googleLogin).not.toHaveBeenCalled()
    vi.mocked(api.googleStatus).mockResolvedValue({ ...signedOut, signedIn: true })
    await vi.advanceTimersByTimeAsync(1500)
    expect(await login).toBe(true)
  })
  it('opens the tab during the click, then navigates and detects sign-in', async () => {
    const tab = popup()
    const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window)
    vi.mocked(api.googleLogin).mockImplementation(async () => {
      expect(open).toHaveBeenCalledWith('about:blank', '_blank')
      return { url }
    })
    const google = useGoogle()
    const login = google.login()
    expect(google.waitingForLogin).toBe(true)
    expect(tab.opener).toBeNull()
    await vi.advanceTimersByTimeAsync(0)
    expect(tab.location.replace).toHaveBeenCalledWith(url)
    vi.mocked(api.googleStatus).mockResolvedValue({ ...signedOut, signedIn: true })
    await vi.advanceTimersByTimeAsync(1500)
    expect(await login).toBe(true)
    expect(google.waitingForLogin).toBe(false)
    expect(google.loginUrl).toBeNull()
  })

  it('provides a clickable URL if the browser blocks the tab', async () => {
    vi.spyOn(window, 'open').mockReturnValue(null)
    const google = useGoogle()
    const login = google.login()
    await vi.advanceTimersByTimeAsync(0)
    expect(google.loginUrl).toBe(url)
    vi.mocked(api.googleStatus).mockResolvedValue({ ...signedOut, signedIn: true })
    await vi.advanceTimersByTimeAsync(1500)
    expect(await login).toBe(true)
  })

  it('does not navigate a cancelled attempt when its HTTP response arrives late', async () => {
    const tab = popup()
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window)
    let resolve!: (result: { url: string }) => void
    vi.mocked(api.googleLogin).mockReturnValue(new Promise((done) => { resolve = done }))
    const google = useGoogle()
    const login = google.login()
    google.cancelLogin()
    resolve({ url })
    expect(await login).toBe(false)
    expect(tab.close).toHaveBeenCalled()
    expect(tab.location.replace).not.toHaveBeenCalled()
    expect(google.waitingForLogin).toBe(false)
    expect(google.loginUrl).toBeNull()
  })

  it('cleans up and reports an error when starting sign-in fails', async () => {
    const tab = popup()
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window)
    vi.mocked(api.googleLogin).mockRejectedValue(new Error('Sign-in unavailable'))
    const google = useGoogle()
    await expect(google.login()).rejects.toThrow('Sign-in unavailable')
    expect(tab.close).toHaveBeenCalled()
    expect(google.waitingForLogin).toBe(false)
  })

  it('stops waiting when the user closes the Google tab', async () => {
    const tab = popup()
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window)
    const google = useGoogle()
    const login = google.login()
    tab.closed = true
    await vi.advanceTimersByTimeAsync(1500)
    expect(await login).toBe(false)
    expect(google.waitingForLogin).toBe(false)
  })

  it('reports a timeout so the user can retry', async () => {
    vi.spyOn(window, 'open').mockReturnValue(null)
    const google = useGoogle()
    const assertion = expect(google.login()).rejects.toThrow('timed out')
    await vi.advanceTimersByTimeAsync(3 * 60 * 1000)
    await assertion
    expect(google.waitingForLogin).toBe(false)
    expect(google.loginUrl).toBeNull()
  })
})
