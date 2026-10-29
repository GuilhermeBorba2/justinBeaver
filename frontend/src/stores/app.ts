import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { AppInfo } from '@/api/types'

/** The app this page runs in: the installed (desktop) one quits when its last tab closes. */
export const useApp = defineStore('app', () => {
  const info = ref<AppInfo | null>(null)
  let session: EventSource | undefined

  async function start() {
    try {
      info.value = await api.appInfo()
    } catch {
      return
    }
    if (info.value.desktop && !session) {
      // an open connection tells the server a tab is still here; closing the tab ends it
      session = new EventSource('/api/app/session')
    }
  }

  async function quit() {
    if (!window.confirm('Quit JustinBeaver? Open connections are closed and uncommitted changes are rolled back.')) return
    session?.close()
    session = undefined
    try {
      await api.quitApp()
    } catch {
      // the server may already be gone
    }
    document.body.innerHTML =
      '<div style="font:16px system-ui;display:grid;place-items:center;height:100vh;background:#1b1a18;color:#e8e4de">JustinBeaver was closed. You can close this tab.</div>'
  }

  return { info, start, quit }
})
