import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import { useGoogle } from './google'
import type { Connection, ConnectionInput, DbInfo, Driver, IsolationLevel, Scope } from '@/api/types'

export const useConnections = defineStore('connections', () => {
  const items = ref<Connection[]>([])
  const drivers = ref<Driver[]>([])
  const loaded = ref(false)

  const byId = computed(() => new Map(items.value.map((c) => [c.id, c])))

  /**
   * Session keys are a connection id, or `<id>::<database>` for another database of the same
   * PostgreSQL server (see the backend's SessionManager).
   */
  const SEPARATOR = '::'
  const baseId = (key: string) => key.split(SEPARATOR)[0]!
  const databaseOf = (key: string): string | null => (key.includes(SEPARATOR) ? key.slice(key.indexOf(SEPARATOR) + 2) : null)
  const connectionOf = (key: string) => byId.value.get(baseId(key))
  const isConnected = (key: string) => connectionOf(key)?.connected === true
  /** "name" or "name / database" for keys of other databases. */
  const labelOf = (key: string) => {
    const name = connectionOf(key)?.name ?? '?'
    const database = databaseOf(key)
    return database ? `${name} / ${database}` : name
  }
  const sessionKey = (connectionId: string, database: string) => `${connectionId}${SEPARATOR}${database}`

  /** Bumped when a script changed the schema, so open navigator nodes reload. */
  const schemaEpoch = ref<Record<string, number>>({})

  function schemaChanged(id: string) {
    schemaEpoch.value[id] = (schemaEpoch.value[id] ?? 0) + 1
  }

  /** Per-session transaction state, shared by every SQL editor and table viewer of a connection. */
  const autoCommit = ref<Record<string, boolean>>({})
  /** Something ran inside an open manual transaction since the last commit/rollback. */
  const txDirty = ref<Record<string, boolean>>({})
  /** Statements run in the open manual transaction (the "pending" counter of DBeaver's toolbar). */
  const txCount = ref<Record<string, number>>({})
  const isolation = ref<Record<string, IsolationLevel>>({})

  function clearTx(id: string) {
    txDirty.value[id] = false
    txCount.value[id] = 0
  }

  async function refreshAutoCommit(id: string) {
    const state = await api.autoCommit(id)
    autoCommit.value[id] = state.autoCommit
    isolation.value[id] = state.isolation
  }

  async function setAutoCommit(id: string, value: boolean) {
    autoCommit.value[id] = (await api.setAutoCommit(id, value)).autoCommit
    clearTx(id)
  }

  async function setIsolation(id: string, level: IsolationLevel) {
    const state = await api.setIsolation(id, level)
    autoCommit.value[id] = state.autoCommit
    isolation.value[id] = state.isolation
  }

  async function endTransaction(id: string, kind: 'commit' | 'rollback') {
    await (kind === 'commit' ? api.commit(id) : api.rollback(id))
    clearTx(id)
  }

  /** Default catalog/schema per session (the toolbar's active schema). */
  const contexts = ref<Record<string, Scope>>({})

  async function refreshContext(id: string): Promise<Scope> {
    return (contexts.value[id] = await api.context(id))
  }

  async function setContext(id: string, scope: Scope): Promise<Scope> {
    return (contexts.value[id] = await api.setContext(id, scope))
  }

  /** Product/dialect/quoting of an open connection, cached per session. */
  const infos = ref<Record<string, DbInfo>>({})

  async function dbInfo(id: string): Promise<DbInfo> {
    return (infos.value[id] ??= await api.info(id))
  }

  const isProduction = (id: string) => connectionOf(id)?.connectionType === 'PROD'

  /** Bumped when a statement changed data, so open table tabs of that session reload. */
  const dataEpoch = ref<Record<string, number>>({})

  function dataChanged(id: string) {
    dataEpoch.value[id] = (dataEpoch.value[id] ?? 0) + 1
  }

  function markDirty(id: string, statements = 1) {
    if (autoCommit.value[id] !== false || statements <= 0) return
    txDirty.value[id] = true
    txCount.value[id] = (txCount.value[id] ?? 0) + statements
  }

  function replace(updated: Connection) {
    const index = items.value.findIndex((c) => c.id === updated.id)
    if (index >= 0) items.value[index] = updated
    else items.value.push(updated)
  }

  async function load() {
    const [connections, driverList] = await Promise.all([api.connections(), api.drivers()])
    items.value = connections
    drivers.value = driverList
    loaded.value = true
  }

  async function save(input: ConnectionInput, id?: string) {
    const saved = id ? await api.updateConnection(id, input) : await api.createConnection(input)
    replace(saved)
    useGoogle().syncSoon()
    return saved
  }

  async function remove(id: string) {
    await api.deleteConnection(id)
    items.value = items.value.filter((c) => c.id !== id)
    useGoogle().syncSoon()
  }

  async function connect(id: string) {
    replace(await api.connect(id))
  }

  async function disconnect(id: string) {
    replace(await api.disconnect(id))
    delete autoCommit.value[id]
    for (const key of Object.keys(autoCommit.value).concat(Object.keys(contexts.value))) {
      if (baseId(key) !== id) continue
      delete autoCommit.value[key]
      delete txDirty.value[key]
      delete txCount.value[key]
      delete isolation.value[key]
      delete contexts.value[key]
      delete infos.value[key]
    }
    delete txDirty.value[id]
    delete infos.value[id]
  }

  return {
    items,
    drivers,
    loaded,
    byId,
    baseId,
    databaseOf,
    connectionOf,
    isConnected,
    labelOf,
    sessionKey,
    schemaEpoch,
    schemaChanged,
    autoCommit,
    txDirty,
    txCount,
    isolation,
    refreshAutoCommit,
    setAutoCommit,
    setIsolation,
    contexts,
    refreshContext,
    setContext,
    endTransaction,
    markDirty,
    dataEpoch,
    dataChanged,
    isProduction,
    dbInfo,
    load,
    save,
    remove,
    connect,
    disconnect,
  }
})
