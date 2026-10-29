<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { Connection, ConnectionInput, Container, IsolationLevel } from '@/api/types'
import { readHistory, type HistoryEntry } from '@/lib/history'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import { useWorkspace, type AdminTab, type SqlTab } from '@/stores/workspace'
import AppIcon from './AppIcon.vue'
import DriverIcon from './DriverIcon.vue'
import SchemaPickerDialog from './SchemaPickerDialog.vue'
import ToolMenu from './ToolMenu.vue'
import { tr } from '@/lib/menu'

/** DBeaver's main toolbar: connection, SQL editor, transaction, active datasource/schema, tools. */
export type ToolId = 'compare-data' | 'migrate' | 'query-manager' | 'import-dbeaver' | 'drivers' | 'preferences' | 'tasks'
const emit = defineEmits<{ 'new-connection': []; find: []; compare: []; tool: [id: ToolId] }>()

const connections = useConnections()
const workspace = useWorkspace()
const ui = useUi()

const ISOLATION_LEVELS: { id: IsolationLevel; label: string }[] = [
  { id: 'READ_UNCOMMITTED', label: 'Read uncommitted' },
  { id: 'READ_COMMITTED', label: 'Read committed' },
  { id: 'REPEATABLE_READ', label: 'Repeatable read' },
  { id: 'SERIALIZABLE', label: 'Serializable' },
]

/** Session the toolbar acts on: the active tab's, else the navigator's selection, else the first connected. */
const target = computed<string | null>(() => {
  const tab = workspace.active
  if (tab) return tab.connectionId
  const focused = workspace.focusedConnectionId
  if (focused && connections.byId.has(focused)) return focused
  return connections.items.find((c) => c.connected)?.id ?? connections.items[0]?.id ?? null
})
const connection = computed<Connection | undefined>(() => (target.value ? connections.connectionOf(target.value) : undefined))
const connected = computed(() => target.value !== null && connections.isConnected(target.value))
const connectedCount = computed(() => connections.items.filter((c) => c.connected).length)
const sqlTab = computed<SqlTab | null>(() => (workspace.active?.kind === 'sql' ? workspace.active : null))
/** Table and admin tabs stay on their connection, as in DBeaver. */
const selectorsLocked = computed(() => workspace.active !== undefined && sqlTab.value === null)

const autoCommit = computed(() => (target.value ? (connections.autoCommit[target.value] ?? null) : null))
const pending = computed(() => (target.value ? (connections.txCount[target.value] ?? 0) : 0))
const dirty = computed(() => target.value !== null && connections.txDirty[target.value] === true)
const isolation = computed(() => (target.value ? connections.isolation[target.value] : undefined))
const manual = computed(() => connected.value && autoCommit.value === false)

const pendingLabel = computed(() => {
  if (!connected.value || autoCommit.value === null) return 'None'
  if (autoCommit.value) return 'Auto'
  return pending.value > 0 ? String(pending.value) : dirty.value ? '1' : 'None'
})

const context = computed(() => (target.value ? connections.contexts[target.value] : undefined))
const schemaLabel = computed(() => {
  if (!connected.value) return 'N/A'
  const scope = context.value
  if (!scope) return '…'
  if (scope.schema && scope.catalog) return `${scope.schema}@${scope.catalog}`
  return scope.schema ?? scope.catalog ?? 'N/A'
})
const datasourceLabel = computed(() => (target.value && connection.value ? connections.labelOf(target.value) : 'N/A'))

watch(
  [target, connected],
  async ([id, isConnected]) => {
    if (!id || !isConnected) return
    if (connections.autoCommit[id] === undefined || connections.isolation[id] === undefined) {
      await ui.guard(() => connections.refreshAutoCommit(id))
    }
    if (!connections.contexts[id]) await ui.guard(() => connections.refreshContext(id))
  },
  { immediate: true },
)

function hasPending(baseId: string): boolean {
  return Object.entries(connections.txDirty).some(([key, value]) => value && connections.baseId(key) === baseId)
}

async function connect() {
  const c = connection.value
  if (c) await ui.guard(() => connections.connect(c.id))
}

async function disconnect() {
  const c = connection.value
  if (!c) return
  if (hasPending(c.id) && !window.confirm(`"${c.name}" has uncommitted changes. Disconnecting rolls them back. Disconnect anyway?`)) return
  await ui.guard(() => connections.disconnect(c.id))
}

/** "Invalidate/Reconnect": a fresh connection with the saved settings. */
async function reconnect() {
  const c = connection.value
  if (!c) return
  if (hasPending(c.id) && !window.confirm(`"${c.name}" has uncommitted changes. Reconnecting rolls them back. Reconnect anyway?`)) return
  const done = await ui.guard(async () => {
    await connections.disconnect(c.id)
    await connections.connect(c.id)
    return true
  })
  if (done) ui.info(`Reconnected ${c.name}`)
}

async function newScript(sql = '') {
  const c = connection.value
  if (!c || !target.value) {
    ui.info('Create or select a connection first')
    return
  }
  if (!connections.isConnected(target.value)) await connect()
  if (connections.isConnected(target.value)) workspace.openSql(target.value, connections.labelOf(target.value), sql)
}

const scriptsOfTarget = computed(() =>
  workspace.tabs.filter((t): t is SqlTab => t.kind === 'sql' && connection.value !== undefined && connections.baseId(t.connectionId) === connection.value.id),
)

async function endTransaction(kind: 'commit' | 'rollback') {
  const id = target.value
  if (!id) return
  const done = await ui.guard(async () => {
    await connections.endTransaction(id, kind)
    return true
  })
  if (done) {
    ui.info(kind === 'commit' ? 'Committed' : 'Rolled back')
    if (kind === 'rollback') connections.dataChanged(id)
  }
}

async function setMode(auto: boolean, close: () => void) {
  close()
  const id = target.value
  if (!id || autoCommit.value === auto) return
  if (auto && dirty.value && !window.confirm('Switching to auto-commit commits the pending changes. Continue?')) return
  await ui.guard(() => connections.setAutoCommit(id, auto))
}

async function setIsolation(level: IsolationLevel, close: () => void) {
  close()
  const id = target.value
  if (id) await ui.guard(() => connections.setIsolation(id, level))
}

/** The lock button: persist the connection's read-only flag and reconnect so it applies. */
async function toggleReadOnly() {
  const c = connection.value
  if (!c) return
  const value = !c.readOnly
  if (c.connected && hasPending(c.id) && !window.confirm('Changing read-only reconnects and rolls back the uncommitted changes. Continue?')) return
  const input: ConnectionInput = {
    name: c.name,
    driverId: c.driverId,
    host: c.host,
    port: c.port,
    database: c.database,
    user: c.user,
    url: c.url,
    properties: c.properties,
    autoCommit: c.autoCommit,
    ssh: c.ssh
      ? {
          enabled: c.ssh.enabled,
          host: c.ssh.host,
          port: c.ssh.port,
          user: c.ssh.user,
          auth: c.ssh.auth,
          privateKeyPath: c.ssh.privateKeyPath,
          acceptNewHostKeys: c.ssh.acceptNewHostKeys,
        }
      : null,
    ssl: c.ssl,
    connectionType: c.connectionType,
    readOnly: value,
    showAllDatabases: c.showAllDatabases,
    folder: c.folder,
  }
  const done = await ui.guard(async () => {
    const wasConnected = c.connected
    await connections.save(input, c.id)
    if (wasConnected) {
      await connections.disconnect(c.id)
      await connections.connect(c.id)
    }
    return true
  })
  if (done) ui.info(`${c.name} is now ${value ? 'read-only' : 'writable'}`)
}

const history = ref<HistoryEntry[]>([])

function loadHistory() {
  history.value = target.value ? readHistory(target.value).slice(0, 20) : []
}

function short(sql: string): string {
  const line = sql.replace(/\s+/g, ' ').trim()
  return line.length > 70 ? `${line.slice(0, 70)}…` : line
}

/** Active datasource: points the SQL script at another connection, or selects it for the toolbar. */
async function chooseConnection(c: Connection, close: () => void) {
  close()
  const tab = sqlTab.value
  if (!tab) {
    workspace.focusedConnectionId = c.id
    return
  }
  if (!c.connected) {
    await ui.guard(() => connections.connect(c.id))
    if (!connections.isConnected(c.id)) return
  }
  workspace.setConnection(tab.id, c.id, c.name)
}

const databases = ref<string[]>([])
const containers = ref<Container[]>([])
const loadingSchemas = ref(false)

const picker = ref<InstanceType<typeof SchemaPickerDialog> | null>(null)

async function openPicker() {
  picker.value?.open()
  await loadSchemas()
}

async function loadSchemas() {
  const id = target.value
  const c = connection.value
  if (!id || !c || !connected.value) return
  loadingSchemas.value = true
  try {
    const [dbs, list] = await Promise.all([
      api.databases(c.id).catch(() => ({ current: null, databases: [] as string[] })),
      api.containers(id),
      connections.refreshContext(id),
    ])
    databases.value = dbs.databases
    containers.value = list
  } catch (e) {
    ui.error(e instanceof Error ? e.message : String(e))
  } finally {
    loadingSchemas.value = false
  }
}

const currentDatabase = computed(() => (target.value ? (connections.databaseOf(target.value) ?? connection.value?.database ?? context.value?.catalog ?? null) : null))

async function chooseContainer(container: Container, close: () => void) {
  close()
  const id = target.value
  if (!id) return
  await ui.guard(() => connections.setContext(id, { catalog: container.catalog, schema: container.schema }))
}

/** PostgreSQL: another database of the server is another session (`<id>::<db>`). */
async function chooseDatabase(database: string, close: () => void) {
  close()
  const tab = sqlTab.value
  const c = connection.value
  if (!tab || !c) return
  const key = database === c.database ? c.id : connections.sessionKey(c.id, database)
  workspace.setConnection(tab.id, key, connections.labelOf(key))
}

function openAdmin(section: NonNullable<AdminTab['section']>) {
  const id = target.value
  if (!id || !connected.value) return
  workspace.openAdmin(id, connections.labelOf(id), section)
}
</script>

<template>
  <div class="main-toolbar" role="toolbar" aria-label="Main toolbar">
    <ToolMenu title="New database connection" split @main="emit('new-connection')">
      <template #button><AppIcon name="plus" /><AppIcon name="database" /></template>
      <template #default="{ close }">
        <button class="item" @click="close(), emit('new-connection')"><AppIcon name="database" /> New connection…</button>
        <button class="item" :disabled="!connection" @click="close(), newScript()"><AppIcon name="sql" /> New SQL script</button>
      </template>
    </ToolMenu>
    <span class="sep" />

    <button class="tool" title="Connect" aria-label="Connect" :disabled="!connection || connected" @click="connect">
      <AppIcon name="plug" />
    </button>
    <button class="tool" title="Invalidate/Reconnect" aria-label="Invalidate/Reconnect" :disabled="!connected" @click="reconnect">
      <AppIcon name="reconnect" />
    </button>
    <button class="tool danger" title="Disconnect" aria-label="Disconnect" :disabled="!connected" @click="disconnect">
      <AppIcon name="plug-off" />
    </button>
    <span class="sep" />

    <ToolMenu title="Open SQL editor (new script)" split :disabled="!connection" @main="newScript()">
      <template #button><AppIcon name="sql" /> SQL</template>
      <template #default="{ close }">
        <button class="item" @click="close(), newScript()"><AppIcon name="plus" /> New script</button>
        <template v-if="scriptsOfTarget.length">
          <hr />
          <div class="heading">Open scripts</div>
          <button v-for="tab in scriptsOfTarget" :key="tab.id" class="item" @click="close(), (workspace.activeId = tab.id)">
            <AppIcon name="script" /> {{ tab.title }}
          </button>
        </template>
      </template>
    </ToolMenu>
    <span class="sep" />

    <button class="tool" :class="{ pending: dirty }" title="Commit" :disabled="!manual" @click="endTransaction('commit')">
      <AppIcon name="commit" /> Commit
    </button>
    <button class="tool" title="Rollback" :disabled="!manual" @click="endTransaction('rollback')">
      <AppIcon name="rollback" /> Rollback
    </button>
    <ToolMenu title="Transaction mode" :disabled="!connected">
      <template #button><AppIcon name="tx" /></template>
      <template #default="{ close }">
        <div class="heading">Mode</div>
        <button class="item" :class="{ on: autoCommit !== false }" role="menuitemradio" :aria-checked="autoCommit !== false" @click="setMode(true, close)">
          Auto-commit
        </button>
        <button class="item" :class="{ on: autoCommit === false }" role="menuitemradio" :aria-checked="autoCommit === false" @click="setMode(false, close)">
          Manual commit
        </button>
        <hr />
        <div class="heading">Isolation level</div>
        <button
          v-for="level in ISOLATION_LEVELS"
          :key="level.id"
          class="item"
          :class="{ on: isolation === level.id }"
          role="menuitemradio"
          :aria-checked="isolation === level.id"
          @click="setIsolation(level.id, close)"
        >
          {{ level.label }}
        </button>
      </template>
    </ToolMenu>
    <button
      class="tool"
      :class="{ on: connection?.readOnly }"
      :title="connection?.readOnly ? 'Read-only connection (click to allow changes)' : 'Make the connection read-only'"
      :aria-pressed="connection?.readOnly === true"
      :disabled="!connection"
      @click="toggleReadOnly"
    >
      <AppIcon :name="connection?.readOnly ? 'lock' : 'unlock'" />
    </button>
    <span class="sep" />

    <span
      class="pending-box"
      :class="{ warn: manual && pendingLabel !== 'None', manual }"
      :title="
        !connected
          ? 'Not connected'
          : autoCommit !== false
            ? 'Auto-commit: every statement is saved immediately'
            : `Manual commit: ${pending} statement(s) waiting for Commit`
      "
    >
      {{ pendingLabel }}
    </span>
    <ToolMenu title="Query history" :disabled="!connection" @open="loadHistory">
      <template #button><AppIcon name="history" /></template>
      <template #default="{ close }">
        <div class="heading">Recent statements · {{ datasourceLabel }}</div>
        <div v-if="history.length === 0" class="heading">None yet</div>
        <button
          v-for="entry in history"
          :key="entry.at"
          class="item mono"
          :class="{ bad: !entry.ok }"
          :title="entry.sql"
          @click="close(), newScript(entry.sql)"
        >
          {{ short(entry.sql) }}
        </button>
        <hr />
        <button class="item" @click="close(), emit('tool', 'query-manager')"><AppIcon name="history" /> Query manager (all connections)…</button>
      </template>
    </ToolMenu>
    <span class="sep" />

    <ToolMenu title="Active datasource (connection)" wide :disabled="connections.items.length === 0 || selectorsLocked">
      <template #button>
        <DriverIcon v-if="connection" :driver-id="connection.driverId" :size="16" />
        <span class="value">{{ datasourceLabel }}</span>
      </template>
      <template #default="{ close }">
        <button
          v-for="c in connections.items"
          :key="c.id"
          class="item"
          :class="{ on: connection?.id === c.id }"
          @click="chooseConnection(c, close)"
        >
          <span class="dot" :class="{ live: c.connected }" />
          <DriverIcon :driver-id="c.driverId" :size="16" />
          {{ c.name }}
          <span v-if="c.connectionType === 'PROD'" class="prod">PROD</span>
        </button>
      </template>
    </ToolMenu>
    <button class="tool schema-button" :title="tr('Active schema (select database/schema)', 'Schema ativo (selecionar database/schema)')" :aria-label="tr('Active schema', 'Schema ativo')" :disabled="!connected" @click="openPicker">
      <AppIcon name="schema" />
      <span class="value">{{ schemaLabel }}</span>
      <AppIcon name="caret" :size="12" />
    </button>
    <SchemaPickerDialog
      ref="picker"
      :loading="loadingSchemas"
      :databases="databases"
      :containers="containers"
      :current-database="currentDatabase"
      :current-schema="context?.schema ?? null"
      :can-switch-database="!!sqlTab"
      @database="(db) => chooseDatabase(db, () => {})"
      @container="(c) => chooseContainer(c, () => {})"
    />
    <span class="sep" />

    <ToolMenu title="Dashboard and administration" :disabled="!connected">
      <template #button><AppIcon name="gauge" /></template>
      <template #default="{ close }">
        <button class="item" @click="close(), openAdmin('dashboard')"><AppIcon name="gauge" /> Dashboard</button>
        <button class="item" @click="close(), openAdmin('sessions')"><AppIcon name="activity" /> Sessions</button>
        <button class="item" @click="close(), openAdmin('locks')"><AppIcon name="lock" /> Locks</button>
        <button class="item" @click="close(), openAdmin('users')"><AppIcon name="key" /> Users & roles</button>
        <button class="item" @click="close(), openAdmin('backup')"><AppIcon name="download" /> Backup & restore</button>
      </template>
    </ToolMenu>
    <ToolMenu title="Database tools" :disabled="connectedCount === 0">
      <template #button><AppIcon name="diagram" /></template>
      <template #default="{ close }">
        <button class="item" @click="close(), emit('compare')"><AppIcon name="diagram" /> Compare schemas…</button>
        <button class="item" @click="close(), emit('tool', 'compare-data')"><AppIcon name="table" /> Compare data…</button>
        <button class="item" @click="close(), emit('tool', 'migrate')"><AppIcon name="copy" /> Migrate tables…</button>
      </template>
    </ToolMenu>
    <span class="sep" />
    <ToolMenu title="Settings: preferences, drivers, import">
      <template #button><AppIcon name="settings" /></template>
      <template #default="{ close }">
        <button class="item" @click="close(), emit('tool', 'preferences')"><AppIcon name="settings" /> Preferences…</button>
        <button class="item" @click="close(), emit('tool', 'tasks')"><AppIcon name="history" /> Tasks (scheduled jobs)…</button>
        <button class="item" @click="close(), emit('tool', 'drivers')"><AppIcon name="plug" /> Driver manager…</button>
        <button class="item" @click="close(), emit('tool', 'import-dbeaver')"><AppIcon name="upload" /> Import connections from DBeaver…</button>
      </template>
    </ToolMenu>
    <ToolMenu title="Search" split :disabled="connectedCount === 0" @main="emit('find')">
      <template #button><AppIcon name="search" /></template>
      <template #default="{ close }">
        <button class="item" @click="close(), emit('find')"><AppIcon name="search" /> Open database object… <kbd>Ctrl+Shift+D</kbd></button>
      </template>
    </ToolMenu>
  </div>
</template>

<style scoped>
.main-toolbar {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-wrap: wrap;
  min-width: 0;
}

@media (max-width: 1180px) {
  .main-toolbar :deep(.wide .whole) {
    max-width: 150px;
  }

  .main-toolbar :deep(.tool) {
    padding: 0 3px;
  }

  .sep {
    margin: 0 1px;
  }
}

.tool {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 6px;
  border-color: transparent;
  background: transparent;
  white-space: nowrap;
}

.tool:hover:not(:disabled) {
  border-color: var(--border);
  background: var(--accent-soft);
}

.tool.schema-button {
  border-color: var(--border);
  background: var(--panel-2);
  max-width: 240px;
}

.tool.on {
  color: var(--accent);
  border-color: var(--accent);
}

.tool.danger:not(:disabled) {
  color: var(--danger);
}

.tool.pending:not(:disabled) {
  color: var(--warn);
  font-weight: 600;
}

.sep {
  width: 0;
  height: 18px;
  margin: 0 4px;
  border-left: 2px dotted var(--border);
}

.pending-box {
  min-width: 84px;
  height: 24px;
  line-height: 22px;
  padding: 0 8px;
  text-align: center;
  border: 1px solid var(--border);
  border-radius: 4px;
  background: var(--panel-2);
  color: var(--muted);
  font-size: 12px;
}

.pending-box.manual {
  color: var(--text);
}

.pending-box.warn {
  color: var(--warn);
  border-color: var(--warn);
  font-weight: 600;
}

.value {
  overflow: hidden;
  text-overflow: ellipsis;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--border);
}

.dot.live {
  background: var(--ok);
}

.prod {
  margin-left: auto;
  font-size: 10px;
  font-weight: 700;
  color: var(--danger);
}

.bad {
  color: var(--danger);
}

kbd {
  margin-left: auto;
  font-size: 10px;
  color: var(--muted);
}
</style>
