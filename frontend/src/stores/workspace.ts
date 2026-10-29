import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { ScriptInfo } from '@/api/types'
import { useConnections } from './connections'
import { useGoogle } from './google'

export interface SqlTab {
  kind: 'sql'
  id: string
  title: string
  connectionId: string
  sql: string
  /** The `.sql` file behind this editor (the "Scripts" folder); unset for a scratch editor such as a routine's source. */
  scriptName?: string
  /** The title was given by the caller (e.g. "db · my_function") and does not follow the script name. */
  customTitle?: boolean
}

export interface TableTab {
  kind: 'table'
  id: string
  title: string
  connectionId: string
  catalog: string | null
  schema: string | null
  table: string
  /** Data filter (SQL condition) in effect; restored with the tab. */
  where?: string
}

export interface AdminTab {
  kind: 'admin'
  id: string
  title: string
  connectionId: string
  /** Section to show; the toolbar's Dashboard menu sets it. */
  section?: 'sessions' | 'locks' | 'dashboard' | 'users' | 'backup'
}

/** Editable ER diagram of a schema: the chosen tables and where their boxes were dragged (saved with the workspace). */
export interface DiagramTab {
  kind: 'er'
  id: string
  title: string
  connectionId: string
  catalog: string | null
  schema: string | null
  tables: string[]
  positions: Record<string, { x: number; y: number }>
}

export type Tab = SqlTab | TableTab | AdminTab | DiagramTab

interface SavedWorkspace {
  tabs: Tab[]
  activeId: string | null
}

const STORAGE_KEY = 'justinbeaver.workspace.v1'
const SAVE_DELAY_MS = 500
const SCRIPT_SAVE_DELAY_MS = 700

function isSaved(value: unknown): value is SavedWorkspace {
  return typeof value === 'object' && value !== null && Array.isArray((value as SavedWorkspace).tabs)
}

/** The browser's copy: used until the server's arrives, and when the server has none yet. */
function restoreLocal(): SavedWorkspace {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : null
    if (isSaved(parsed)) return { tabs: parsed.tabs, activeId: parsed.activeId ?? null }
  } catch {
    // storage unavailable or corrupted: start empty
  }
  return { tabs: [], activeId: null }
}

export const useWorkspace = defineStore('workspace', () => {
  const connections = useConnections()
  const saved = restoreLocal()
  const tabs = ref<Tab[]>(saved.tabs)
  const activeId = ref<string | null>(saved.activeId)
  /** Contents of the scripts folder, newest first (the navigator's Scripts section). */
  const scripts = ref<ScriptInfo[]>([])
  /** Set once the server's workspace was read: saving before that would overwrite it with the browser's copy. */
  const loaded = ref(false)

  const active = computed(() => tabs.value.find((t) => t.id === activeId.value))
  /** Connection last clicked in the navigator: the toolbar's target when no tab is open. */
  const focusedConnectionId = ref<string | null>(null)

  function snapshot(): SavedWorkspace {
    return { tabs: tabs.value, activeId: activeId.value }
  }

  let saveTimer: ReturnType<typeof setTimeout> | undefined
  watch(
    [tabs, activeId],
    () => {
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot()))
      } catch {
        // best effort only
      }
      if (!loaded.value) return
      clearTimeout(saveTimer)
      saveTimer = setTimeout(() => void api.saveWorkspace(snapshot()).catch(() => undefined), SAVE_DELAY_MS)
    },
    { deep: true },
  )

  /** Read the tabs saved on the server (they win over the browser's copy) and give every script editor its file. */
  async function load() {
    try {
      const document = await api.workspace()
      if (isSaved(document)) {
        tabs.value = document.tabs
        activeId.value = tabs.value.some((t) => t.id === document.activeId) ? document.activeId : (tabs.value[0]?.id ?? null)
      }
    } catch {
      // server unreachable: keep the browser's copy
    }
    loaded.value = true
    void api.saveWorkspace(snapshot()).catch(() => undefined)
    for (const tab of tabs.value) {
      if (tab.kind === 'sql' && !tab.scriptName && !tab.customTitle) void attachScript(tab)
    }
    await refreshScripts()
  }

  /** Last chance to save when the page goes away (keepalive outlives the page). */
  function flush() {
    clearTimeout(saveTimer)
    for (const id of [...scriptTimers.keys()]) saveScriptNow(id, true)
    if (!loaded.value) return
    try {
      void fetch('/api/workspace', {
        method: 'PUT',
        keepalive: true,
        headers: { 'X-JB-Client': 'web', 'Content-Type': 'application/json' },
        body: JSON.stringify(snapshot()),
      }).catch(() => undefined)
    } catch {
      // body over the keepalive limit: the debounced save already ran
    }
  }

  async function refreshScripts() {
    try {
      scripts.value = await api.scripts()
    } catch {
      // keep the last listing
    }
  }

  /**
   * Scripts changed on disk by the cloud sync: refresh the list and the text of open editors whose file differs
   * (their own edits reach the file within a second, so a difference here came from outside).
   */
  async function reloadScripts() {
    await refreshScripts()
    for (const tab of tabs.value) {
      if (tab.kind !== 'sql' || !tab.scriptName || scriptTimers.has(tab.id)) continue
      const known = scripts.value.some((s) => s.name === tab.scriptName)
      if (!known) {
        tab.scriptName = undefined
        continue
      }
      try {
        const fresh = await api.script(tab.scriptName)
        if (fresh.content !== tab.sql) tab.sql = fresh.content
      } catch {
        // the file went away meanwhile
      }
    }
  }

  function titleFor(connectionId: string, scriptName: string) {
    const label = connectionId ? connections.labelOf(connectionId) : 'N/A'
    return `${label} · ${scriptName}`
  }

  /** Create the `.sql` file of a new editor (named Script-N by the server). */
  async function attachScript(tab: SqlTab) {
    try {
      const script = await api.createScript(tab.connectionId || null, tab.sql)
      const live = tabs.value.find((t): t is SqlTab => t.id === tab.id && t.kind === 'sql')
      if (!live) {
        // closed before the file existed
        if (!script.content.trim()) await api.deleteScript(script.name)
        return
      }
      live.scriptName = script.name
      if (!live.customTitle) live.title = titleFor(live.connectionId, script.name)
      if (live.sql !== script.content) saveScriptNow(live.id)
      await refreshScripts()
    } catch {
      // no scripts folder: the editor still works, its text lives in the workspace only
    }
  }

  const scriptTimers = new Map<string, ReturnType<typeof setTimeout>>()

  function saveScriptNow(tabId: string, keepalive = false) {
    clearTimeout(scriptTimers.get(tabId))
    scriptTimers.delete(tabId)
    const tab = tabs.value.find((t): t is SqlTab => t.id === tabId && t.kind === 'sql')
    if (!tab?.scriptName) return
    if (keepalive) {
      void fetch(`/api/scripts/${encodeURIComponent(tab.scriptName)}`, {
        method: 'PUT',
        keepalive: true,
        headers: { 'X-JB-Client': 'web', 'Content-Type': 'application/json' },
        body: JSON.stringify({ connectionId: tab.connectionId || null, content: tab.sql }),
      }).catch(() => undefined)
      return
    }
    void api
      .saveScript(tab.scriptName, tab.connectionId || null, tab.sql)
      .then(() => useGoogle().syncSoon())
      .catch(() => undefined)
  }

  function scheduleScriptSave(tabId: string) {
    clearTimeout(scriptTimers.get(tabId))
    scriptTimers.set(tabId, setTimeout(() => saveScriptNow(tabId), SCRIPT_SAVE_DELAY_MS))
  }

  /**
   * New SQL editor backed by a new script file. `connectionName` containing " · " is used as the full title
   * (e.g. "db · my_function"); such editors show an object's source and get no file.
   */
  function openSql(connectionId: string, connectionName: string, sql = '') {
    const customTitle = connectionName.includes(' · ')
    const tab: SqlTab = {
      kind: 'sql',
      id: crypto.randomUUID(),
      title: customTitle ? connectionName : `${connectionName} · new script`,
      connectionId,
      sql,
      customTitle: customTitle || undefined,
    }
    tabs.value.push(tab)
    activeId.value = tab.id
    if (!customTitle) void attachScript(tab)
    return tab
  }

  /** Open a saved script; one that is already open is brought to front. */
  async function openScript(name: string, fallbackConnectionId: string | null = null) {
    const open = tabs.value.find((t): t is SqlTab => t.kind === 'sql' && t.scriptName === name)
    if (open) {
      activeId.value = open.id
      return open
    }
    const script = await api.script(name)
    const known = script.connectionId && connections.connectionOf(script.connectionId) ? script.connectionId : null
    const connectionId = known ?? fallbackConnectionId ?? ''
    const tab: SqlTab = {
      kind: 'sql',
      id: crypto.randomUUID(),
      title: titleFor(connectionId, name),
      connectionId,
      sql: script.content,
      scriptName: name,
    }
    tabs.value.push(tab)
    activeId.value = tab.id
    return tab
  }

  async function renameScript(name: string, newName: string) {
    const info = await api.renameScript(name, newName)
    for (const tab of tabs.value) {
      if (tab.kind === 'sql' && tab.scriptName === name) {
        tab.scriptName = info.name
        if (!tab.customTitle) tab.title = titleFor(tab.connectionId, info.name)
      }
    }
    await refreshScripts()
    useGoogle().syncSoon()
  }

  /** Delete a script file and close its editor. */
  async function deleteScript(name: string) {
    await api.deleteScript(name)
    const open = tabs.value.find((t): t is SqlTab => t.kind === 'sql' && t.scriptName === name)
    if (open) {
      clearTimeout(scriptTimers.get(open.id))
      scriptTimers.delete(open.id)
      open.scriptName = undefined
      close(open.id)
    }
    await refreshScripts()
    useGoogle().syncSoon()
  }

  function openTable(connectionId: string, catalog: string | null, schema: string | null, table: string, where?: string) {
    const existing = tabs.value.find(
      (t): t is TableTab =>
        t.kind === 'table' &&
        t.connectionId === connectionId &&
        t.catalog === catalog &&
        t.schema === schema &&
        t.table === table,
    )
    if (existing) {
      if (where !== undefined) existing.where = where
      activeId.value = existing.id
      return existing
    }
    const tab: TableTab = {
      kind: 'table',
      id: crypto.randomUUID(),
      title: table,
      connectionId,
      catalog,
      schema,
      table,
      where,
    }
    tabs.value.push(tab)
    activeId.value = tab.id
    return tab
  }

  /** Sessions, locks, dashboard and backup of one connection; one tab per connection. */
  function openAdmin(connectionId: string, connectionName: string, section?: AdminTab['section']) {
    const existing = tabs.value.find((t): t is AdminTab => t.kind === 'admin' && t.connectionId === connectionId)
    if (existing) {
      if (section) existing.section = section
      activeId.value = existing.id
      return existing
    }
    const tab: AdminTab = { kind: 'admin', id: crypto.randomUUID(), title: `${connectionName} · Admin`, connectionId, section }
    tabs.value.push(tab)
    activeId.value = tab.id
    return tab
  }

  /** One diagram per schema; `tables` seeds a new one (e.g. a table and its neighbours). */
  function openDiagram(connectionId: string, catalog: string | null, schema: string | null, label: string, tables: string[] = []) {
    const existing = tabs.value.find(
      (t): t is DiagramTab => t.kind === 'er' && t.connectionId === connectionId && t.catalog === catalog && t.schema === schema,
    )
    if (existing) {
      for (const t of tables) if (!existing.tables.includes(t)) existing.tables.push(t)
      activeId.value = existing.id
      return existing
    }
    const tab: DiagramTab = {
      kind: 'er',
      id: crypto.randomUUID(),
      title: `${label} · ER`,
      connectionId,
      catalog,
      schema,
      tables: [...tables],
      positions: {},
    }
    tabs.value.push(tab)
    activeId.value = tab.id
    return tab
  }

  /** Closing a script editor keeps its file, except an empty one (DBeaver's "delete empty scripts"). */
  function close(id: string) {
    const index = tabs.value.findIndex((t) => t.id === id)
    if (index < 0) return
    const tab = tabs.value[index]!
    if (tab.kind === 'sql' && tab.scriptName) {
      const name = tab.scriptName
      if (tab.sql.trim()) {
        saveScriptNow(id)
      } else {
        clearTimeout(scriptTimers.get(id))
        scriptTimers.delete(id)
        void api.deleteScript(name).then(refreshScripts, () => undefined)
      }
    }
    tabs.value.splice(index, 1)
    if (activeId.value === id) {
      activeId.value = tabs.value[Math.min(index, tabs.value.length - 1)]?.id ?? null
    }
    void refreshScripts()
  }

  /** Drop every tab of a connection that was deleted (their script files stay). */
  function closeForConnection(connectionId: string) {
    for (const tab of tabs.value.filter((t) => t.connectionId === connectionId)) close(tab.id)
  }

  /** Point a SQL script at another connection or database (the toolbar's active datasource). */
  function setConnection(id: string, connectionId: string, label: string) {
    const tab = tabs.value.find((t): t is SqlTab => t.id === id && t.kind === 'sql')
    if (!tab || tab.connectionId === connectionId) return
    tab.connectionId = connectionId
    if (tab.scriptName && !tab.customTitle) {
      tab.title = `${label} · ${tab.scriptName}`
      saveScriptNow(id)
    }
  }

  function setSql(id: string, sql: string) {
    const tab = tabs.value.find((t): t is SqlTab => t.id === id && t.kind === 'sql')
    if (!tab || tab.sql === sql) return
    tab.sql = sql
    if (tab.scriptName) scheduleScriptSave(id)
  }

  function setTableFilter(id: string, where: string) {
    const tab = tabs.value.find((t): t is TableTab => t.id === id && t.kind === 'table')
    if (tab) tab.where = where || undefined
  }

  return {
    tabs,
    activeId,
    active,
    scripts,
    loaded,
    focusedConnectionId,
    load,
    flush,
    refreshScripts,
    reloadScripts,
    openSql,
    openScript,
    renameScript,
    deleteScript,
    openTable,
    openAdmin,
    openDiagram,
    close,
    closeForConnection,
    setConnection,
    setSql,
    setTableFilter,
  }
})
