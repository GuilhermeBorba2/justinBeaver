<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Connection } from '@/api/types'
import { useConnectionActions } from '@/composables/connectionActions'
import { openContextMenu } from '@/composables/contextMenu'
import { useMenus } from '@/composables/menus'
import { loadChildren, type NavDescriptor } from '@/lib/navigator'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import { useWorkspace } from '@/stores/workspace'
import AppIcon from './AppIcon.vue'
import NavNode from './NavNode.vue'

const props = defineProps<{ connection: Connection }>()
const emit = defineEmits<{ edit: [connection: Connection]; remove: [connection: Connection] }>()

const connections = useConnections()
const workspace = useWorkspace()
const ui = useUi()

const menus = useMenus()
const actions = useConnectionActions()

const expanded = ref(false)
const loading = ref(false)
const busy = ref(false)
const children = ref<NavDescriptor[]>([])
/** Part of the child keys: a new value forces every descendant to reload instead of reusing cached state. */
const epoch = ref(0)

const descriptor = computed<NavDescriptor>(() => ({
  key: props.connection.id,
  kind: 'connection',
  label: props.connection.name,
  connectionId: props.connection.id,
  postgres: connections.drivers.find((d) => d.id === props.connection.driverId)?.providerId === 'postgresql',
}))

const driverLabel = computed(() => connections.drivers.find((d) => d.id === props.connection.driverId)?.label ?? props.connection.driverId)

async function loadTree() {
  loading.value = true
  try {
    children.value = await loadChildren(descriptor.value)
    epoch.value++
  } catch (e) {
    ui.error(e instanceof Error ? e.message : String(e))
    children.value = []
  } finally {
    loading.value = false
  }
}

watch(
  () => props.connection.connected,
  (connected) => {
    if (!connected) {
      expanded.value = false
      children.value = []
    }
  },
)

// A script changed the schema (CREATE/ALTER/DROP): reload what is open.
watch(
  () => connections.schemaEpoch[props.connection.id],
  () => {
    if (expanded.value && props.connection.connected) void loadTree()
  },
)

async function connect() {
  busy.value = true
  try {
    await ui.guard(() => connections.connect(props.connection.id))
    if (props.connection.connected) {
      expanded.value = true
      await loadTree()
    }
  } finally {
    busy.value = false
  }
}

async function disconnect() {
  const id = props.connection.id
  const pending = Object.entries(connections.txDirty).some(([key, dirty]) => dirty && connections.baseId(key) === id)
  if (pending && !window.confirm(`"${props.connection.name}" has uncommitted changes. Disconnecting rolls them back. Disconnect anyway?`)) {
    return
  }
  busy.value = true
  try {
    await ui.guard(() => connections.disconnect(props.connection.id))
  } finally {
    busy.value = false
  }
}

async function onClick() {
  workspace.focusedConnectionId = props.connection.id
  if (!props.connection.connected) {
    await connect()
  } else if (expanded.value) {
    expanded.value = false
  } else {
    expanded.value = true
    if (children.value.length === 0) await loadTree()
  }
}

function newScript() {
  workspace.openSql(props.connection.id, props.connection.name)
}

/** "Navigate from here": connect when needed, then open the tree of this connection. */
async function navigateHere() {
  workspace.focusedConnectionId = props.connection.id
  if (!props.connection.connected) await connect()
  else {
    expanded.value = true
    if (children.value.length === 0) await loadTree()
  }
}

function onMenu(event: MouseEvent) {
  workspace.focusedConnectionId = props.connection.id
  openContextMenu(event, menus.connectionMenu(props.connection, { refresh: () => void refresh(), navigateHere: () => void navigateHere() }))
}

/** DBeaver's keys on a connection: F4 edit, F2 rename, F5 refresh, Delete, Ctrl+C / Ctrl+V. */
function onKeys(event: KeyboardEvent) {
  if (event.target !== event.currentTarget) return
  const mod = event.ctrlKey || event.metaKey
  if (event.key === 'F4') emit('edit', props.connection)
  else if (event.key === 'F2') void actions.rename(props.connection)
  else if (event.key === 'F5') void refresh()
  else if (event.key === 'Delete') emit('remove', props.connection)
  else if (mod && event.key.toLowerCase() === 'c') actions.copy(props.connection)
  else if (mod && event.key.toLowerCase() === 'v') void actions.paste()
  else return
  event.preventDefault()
}

async function refresh() {
  if (props.connection.connected) {
    expanded.value = true
    await loadTree()
  }
}
</script>

<template>
  <div class="connection" role="treeitem" :aria-expanded="connection.connected ? expanded : undefined">
    <div class="row" :class="`type-${connection.connectionType?.toLowerCase() ?? 'dev'}`" tabindex="0" @click="onClick" @keydown.enter.prevent="onClick" @keydown="onKeys" @contextmenu="onMenu">
      <span class="caret" :class="{ open: expanded, hidden: !connection.connected }">
        <AppIcon name="chevron" :size="10" />
      </span>
      <AppIcon name="database" :size="15" class="db-icon" />
      <span class="status" :class="{ on: connection.connected }" :title="connection.connected ? 'Connected' : 'Disconnected'" />
      <span class="name" :title="connection.name">{{ connection.name }}</span>
      <span v-if="connection.connectionType === 'PROD'" class="badge prod" title="Production">PROD</span>
      <span v-else-if="connection.connectionType === 'TEST'" class="badge test" title="Test">TEST</span>
      <span v-if="connection.readOnly" class="badge" title="Read-only">RO</span>
      <span v-if="loading || busy" class="muted">…</span>
      <span v-else class="driver muted">{{ driverLabel }}</span>
      <span class="actions" @click.stop>
        <button v-if="!connection.connected" class="ghost" title="Connect" aria-label="Connect" :disabled="busy" @click="connect">
          <AppIcon name="plug" />
        </button>
        <template v-else>
          <button class="ghost" title="New SQL script" aria-label="New SQL script" @click="newScript">
            <AppIcon name="sql" />
          </button>
          <button class="ghost" title="Refresh" aria-label="Refresh" @click="refresh"><AppIcon name="refresh" /></button>
          <button class="ghost" title="Sessions, locks, dashboard, backup" aria-label="Administer" @click="workspace.openAdmin(connection.id, connection.name)">
            <AppIcon name="activity" />
          </button>
          <button class="ghost" title="Disconnect" aria-label="Disconnect" :disabled="busy" @click="disconnect">
            <AppIcon name="plug-off" />
          </button>
        </template>
        <button class="ghost" title="Edit connection" aria-label="Edit connection" @click="emit('edit', connection)">
          <AppIcon name="edit" />
        </button>
        <button class="ghost danger" title="Delete connection" aria-label="Delete connection" @click="emit('remove', connection)">
          <AppIcon name="trash" />
        </button>
      </span>
    </div>
    <div v-if="expanded && connection.connected" class="children" role="group">
      <NavNode v-for="child in children" :key="`${child.key}#${epoch}`" :node="child" :depth="1" />
      <div v-if="!loading && children.length === 0" class="empty muted">(nothing to show)</div>
    </div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 4px 0 8px;
  cursor: default;
  user-select: none;
  white-space: nowrap;
  font-weight: 500;
}

.row:hover {
  background: var(--panel-2);
}

.row:focus-visible {
  outline-offset: -2px;
}

.row.type-prod {
  box-shadow: inset 3px 0 0 var(--danger);
}

.row.type-test {
  box-shadow: inset 3px 0 0 var(--warn);
}

.badge {
  flex: none;
  padding: 0 4px;
  border: 1px solid var(--border);
  border-radius: 3px;
  font-size: 9px;
  font-weight: 700;
  color: var(--muted);
}

.badge.prod {
  border-color: var(--danger);
  color: var(--danger);
}

.badge.test {
  border-color: var(--warn);
  color: var(--warn);
}

.caret {
  display: inline-flex;
  width: 12px;
  color: var(--muted);
  transition: transform 0.1s;
}

.caret.open {
  transform: rotate(90deg);
}

.caret.hidden {
  visibility: hidden;
}

.db-icon {
  color: var(--accent);
  flex: none;
}

.status {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--border);
  flex: none;
}

.status.on {
  background: var(--ok);
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
}

.driver {
  margin-left: auto;
  font-size: 11px;
  font-weight: 400;
}

.actions {
  display: none;
  margin-left: auto;
  gap: 0;
}

.row:hover .actions,
.row:focus-within .actions {
  display: inline-flex;
}

.row:hover .driver,
.row:focus-within .driver {
  display: none;
}

.empty {
  padding: 2px 30px;
  font-style: italic;
}
</style>
