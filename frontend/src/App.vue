<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import type { Connection } from '@/api/types'
import { useConnections } from '@/stores/connections'
import { useApp } from '@/stores/app'
import { useGoogle } from '@/stores/google'
import { usePrefs } from '@/stores/prefs'
import { useUi } from '@/stores/ui'
import { useWorkspace } from '@/stores/workspace'
import AppIcon from './components/AppIcon.vue'
import ConnectionDialog from './components/ConnectionDialog.vue'
import CompareDialog from './components/CompareDialog.vue'
import DataCompareDialog from './components/DataCompareDialog.vue'
import MigrateDialog from './components/MigrateDialog.vue'
import QueryManagerDialog from './components/QueryManagerDialog.vue'
import DriverManagerDialog from './components/DriverManagerDialog.vue'
import ImportDbeaverDialog from './components/ImportDbeaverDialog.vue'
import PreferencesDialog from './components/PreferencesDialog.vue'
import TasksDialog from './components/TasksDialog.vue'
import AccountMenu from './components/AccountMenu.vue'
import BookmarksPanel from './components/BookmarksPanel.vue'
import GoogleSyncDialog from './components/GoogleSyncDialog.vue'
import ConnectionTree from './components/ConnectionTree.vue'
import DriveMenu from './components/DriveMenu.vue'
import CopyDataDialog from './components/CopyDataDialog.vue'
import EditPreviewDialog from './components/EditPreviewDialog.vue'
import MainToolbar, { type ToolId } from './components/MainToolbar.vue'
import QuickSearch from './components/QuickSearch.vue'
import SchemaDiagram from './components/SchemaDiagram.vue'
import SchemaObjectDialog from './components/SchemaObjectDialog.vue'
import ScriptsPanel from './components/ScriptsPanel.vue'
import SqlEditor from './components/SqlEditor.vue'
import AdminPanel from './components/AdminPanel.vue'
import TableViewer from './components/TableViewer.vue'
import ToastHost from './components/ToastHost.vue'
import ValueViewer from './components/ValueViewer.vue'
import CommandPalette from './components/CommandPalette.vue'
import ContextMenuHost from './components/ContextMenuHost.vue'
import HelpDialog from './components/HelpDialog.vue'
import MenuBar from './components/MenuBar.vue'
import PromptDialog from './components/PromptDialog.vue'
import { APP_ACTIONS } from './composables/appActions'
import { openContextMenu } from './composables/contextMenu'
import { tr } from '@/lib/menu'
import { tabMenuItems } from './composables/menus'
import { createSchemaService, SCHEMA_SERVICE } from './composables/schema'
import { NAV_FILTER } from './composables/navFilter'
import { TOOLS } from './composables/tools'
import { VALUE_VIEWER } from './composables/viewer'

const SIDEBAR_KEY = 'justinbeaver.sidebar.width'

const connections = useConnections()
const workspace = useWorkspace()
const ui = useUi()
// applies the theme to the page as soon as the app starts
usePrefs()

const dialog = ref<InstanceType<typeof ConnectionDialog> | null>(null)
const schemaDialog = ref<InstanceType<typeof SchemaObjectDialog> | null>(null)
const ddlPreview = ref<InstanceType<typeof EditPreviewDialog> | null>(null)
const quickSearch = ref<InstanceType<typeof QuickSearch> | null>(null)
const valueViewer = ref<InstanceType<typeof ValueViewer> | null>(null)
const copyData = ref<InstanceType<typeof CopyDataDialog> | null>(null)
const compare = ref<InstanceType<typeof CompareDialog> | null>(null)
const dataCompare = ref<InstanceType<typeof DataCompareDialog> | null>(null)
const migrate = ref<InstanceType<typeof MigrateDialog> | null>(null)
const queryManager = ref<InstanceType<typeof QueryManagerDialog> | null>(null)
const driverManager = ref<InstanceType<typeof DriverManagerDialog> | null>(null)
const importDbeaver = ref<InstanceType<typeof ImportDbeaverDialog> | null>(null)
const preferences = ref<InstanceType<typeof PreferencesDialog> | null>(null)
const tasks = ref<InstanceType<typeof TasksDialog> | null>(null)
const googleSync = ref<InstanceType<typeof GoogleSyncDialog> | null>(null)
const palette = ref<InstanceType<typeof CommandPalette> | null>(null)
const help = ref<InstanceType<typeof HelpDialog> | null>(null)
const sidebarHidden = ref(false)
const app = useApp()
const google = useGoogle()

function openTool(id: ToolId) {
  switch (id) {
    case 'compare-data':
      return dataCompare.value?.open()
    case 'migrate':
      return migrate.value?.open()
    case 'query-manager':
      return queryManager.value?.open()
    case 'drivers':
      return driverManager.value?.open()
    case 'import-dbeaver':
      return importDbeaver.value?.open()
    case 'preferences':
      return preferences.value?.open()
    case 'tasks':
      return tasks.value?.open()
  }
}

provide(SCHEMA_SERVICE, createSchemaService(schemaDialog, ddlPreview))
provide(APP_ACTIONS, {
  newConnection: () => dialog.value?.show(),
  editConnection: (c) => dialog.value?.show(c),
  deleteConnection: (c) => void remove(c),
  findObject: () => quickSearch.value?.open(),
  compareSchema: () => compare.value?.open(),
  tool: openTool,
  googleSync: () => googleSync.value?.open(),
  shortcuts: () => help.value?.open('shortcuts'),
  about: () => help.value?.open('about'),
  palette: () => palette.value?.open(),
  toggleSidebar: () => (sidebarHidden.value = !sidebarHidden.value),
})
provide(VALUE_VIEWER, (request) => void valueViewer.value?.open(request))
const navFilter = ref('')
provide(NAV_FILTER, navFilter)
provide(TOOLS, { copyData: (source) => copyData.value?.open(source) })

/** Ctrl+Shift+D: "Open Database Object", as in DBeaver. Ctrl+Shift+P: command palette. Ctrl+/: shortcuts. F11: full screen. */
function onGlobalKey(event: KeyboardEvent) {
  const mod = event.ctrlKey || event.metaKey
  const key = event.key.toLowerCase()
  if (mod && event.shiftKey && key === 'd') {
    event.preventDefault()
    quickSearch.value?.open()
  } else if (mod && event.shiftKey && key === 'p') {
    event.preventDefault()
    palette.value?.open()
  } else if (mod && !event.shiftKey && key === '/' && !(event.target instanceof HTMLElement && event.target.closest('.cm-editor'))) {
    // inside the SQL editor Ctrl+/ toggles a comment
    event.preventDefault()
    help.value?.open('shortcuts')
  } else if (event.key === 'F11') {
    event.preventDefault()
    void (document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen()).catch(() => undefined)
  }
}
const sidebarWidth = ref(readSidebarWidth())
const loadError = ref<string | null>(null)

const connectedCount = computed(() => connections.items.filter((c) => c.connected).length)

function readSidebarWidth(): number {
  try {
    const stored = Number(localStorage.getItem(SIDEBAR_KEY))
    if (stored >= 200 && stored <= 700) return stored
  } catch {
    // storage unavailable
  }
  return 320
}

function startResize(event: PointerEvent) {
  const move = (e: PointerEvent) => {
    sidebarWidth.value = Math.min(700, Math.max(200, e.clientX))
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    try {
      localStorage.setItem(SIDEBAR_KEY, String(sidebarWidth.value))
    } catch {
      // best effort
    }
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
  event.preventDefault()
}

async function remove(connection: Connection) {
  if (!window.confirm(`Delete connection "${connection.name}"? Its saved password is deleted too.`)) return
  const done = await ui.guard(() => connections.remove(connection.id))
  if (done !== undefined) workspace.closeForConnection(connection.id)
}

function tabTitle(tab: { title: string }) {
  return tab.title
}

/** Connections already tried by "connect on editor activation": a failure is not retried on every tab switch. */
const autoConnected = new Set<string>()

/** DBeaver's "Connect on editor activation": a restored tab connects its database when it is shown. */
async function connectActive() {
  const tab = workspace.active
  if (!tab?.connectionId || !connections.loaded) return
  const id = connections.baseId(tab.connectionId)
  const connection = connections.connectionOf(id)
  if (!connection || connection.connected || autoConnected.has(id)) return
  autoConnected.add(id)
  await ui.guard(() => connections.connect(id))
}

watch(() => [workspace.activeId, connections.loaded], () => void connectActive())

const flushWorkspace = () => workspace.flush()

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKey)
  window.removeEventListener('pagehide', flushWorkspace)
})

onMounted(async () => {
  window.addEventListener('keydown', onGlobalKey)
  window.addEventListener('pagehide', flushWorkspace)
  try {
    await connections.load()
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  }
  await workspace.load()
  void connectActive()
  void app.start()
  void google.start()
})
</script>

<template>
  <div class="app" :style="{ gridTemplateColumns: sidebarHidden ? '0px 0px 1fr' : `${sidebarWidth}px 5px 1fr` }">
    <div class="menurow"><MenuBar /></div>
    <header class="topbar">
      <span class="brand" title="JustinBeaver"><img src="/logo.png" alt="" class="logo" width="22" height="22" /> <span class="brand-name">JustinBeaver</span></span>
      <MainToolbar @new-connection="dialog?.show()" @find="quickSearch?.open()" @compare="compare?.open()" @tool="openTool" />
      <AccountMenu @open="googleSync?.open()" />
    </header>

    <aside class="sidebar" aria-label="Database navigator">
      <div v-if="loadError" class="notice">
        Cannot reach the server: {{ loadError }}
      </div>
      <div v-else-if="connections.loaded && connections.items.length === 0" class="notice">
        No connections yet.
        <button class="primary" @click="dialog?.show()"><AppIcon name="plus" /> Create one</button>
        <button @click="importDbeaver?.open()"><AppIcon name="upload" /> Import from DBeaver</button>
      </div>
      <div class="filter">
        <input v-if="connections.items.length" v-model="navFilter" type="search" placeholder="Filter tables, views, routines…" aria-label="Filter navigator" />
        <span v-else class="muted grow">{{ tr('Connections', 'Conexões') }}</span>
        <DriveMenu scope="connections" align-right />
      </div>
      <div class="tree" role="tree">
        <ConnectionTree :connections="connections.items" @edit="dialog?.show($event)" @remove="remove" />
      </div>
      <BookmarksPanel v-if="!loadError" />
      <ScriptsPanel v-if="!loadError" />
    </aside>

    <div class="resizer" role="separator" aria-orientation="vertical" @pointerdown="startResize" />

    <main class="main">
      <div v-if="workspace.tabs.length" class="tabs" role="tablist">
        <div
          v-for="tab in workspace.tabs"
          :key="tab.id"
          class="tab"
          :class="{ on: tab.id === workspace.activeId, prod: connections.isProduction(tab.connectionId) }"
          role="tab"
          :aria-selected="tab.id === workspace.activeId"
          tabindex="0"
          @click="workspace.activeId = tab.id"
          @keydown.enter="workspace.activeId = tab.id"
          @auxclick.middle.prevent="workspace.close(tab.id)"
          @contextmenu="openContextMenu($event, tabMenuItems(tab))"
        >
          <AppIcon :name="tab.kind === 'sql' ? 'sql' : tab.kind === 'table' ? 'table' : tab.kind === 'er' ? 'diagram' : 'activity'" :size="13" />
          <span class="title">{{ tabTitle(tab) }}</span>
          <button class="ghost close" :aria-label="`Close ${tab.title}`" @click.stop="workspace.close(tab.id)">
            <AppIcon name="close" :size="11" />
          </button>
        </div>
      </div>

      <div class="content">
        <template v-for="tab in workspace.tabs" :key="tab.id">
          <div v-show="tab.id === workspace.activeId" class="pane">
            <SqlEditor v-if="tab.kind === 'sql'" :tab="tab" :active="tab.id === workspace.activeId" />
            <TableViewer v-else-if="tab.kind === 'table'" :tab="tab" :active="tab.id === workspace.activeId" />
            <SchemaDiagram v-else-if="tab.kind === 'er'" :tab="tab" :active="tab.id === workspace.activeId" />
            <AdminPanel v-else :tab="tab" :active="tab.id === workspace.activeId" />
          </div>
        </template>
        <div v-if="workspace.tabs.length === 0" class="welcome">
          <img src="/logo.png" alt="JustinBeaver logo" class="welcome-logo" width="120" height="120" />
          <h1>JustinBeaver</h1>
          <p class="muted">
            Double-click a table to browse its data, or open a SQL script on a connected database.
          </p>
        </div>
      </div>
    </main>

    <footer class="statusbar muted">
      <span>{{ connections.items.length }} connection(s) · {{ connectedCount }} connected</span>
    </footer>

    <ConnectionDialog ref="dialog" />
    <SchemaObjectDialog ref="schemaDialog" />
    <EditPreviewDialog ref="ddlPreview" />
    <QuickSearch ref="quickSearch" />
    <ValueViewer ref="valueViewer" />
    <CopyDataDialog ref="copyData" />
    <CompareDialog ref="compare" />
    <DataCompareDialog ref="dataCompare" />
    <MigrateDialog ref="migrate" />
    <QueryManagerDialog ref="queryManager" />
    <DriverManagerDialog ref="driverManager" />
    <ImportDbeaverDialog ref="importDbeaver" />
    <PreferencesDialog ref="preferences" />
    <TasksDialog ref="tasks" />
    <GoogleSyncDialog ref="googleSync" />
    <CommandPalette ref="palette" />
    <HelpDialog ref="help" />
    <PromptDialog />
    <ContextMenuHost />
    <ToastHost />
  </div>
</template>

<style scoped>
.app {
  display: grid;
  grid-template-rows: auto auto 1fr 24px;
  grid-template-areas:
    'menu menu menu'
    'top top top'
    'side resize main'
    'foot foot foot';
  height: 100%;
}

.menurow {
  grid-area: menu;
  padding: 2px 8px 0;
  background: var(--panel);
  font-size: 13px;
}

.topbar {
  grid-area: top;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 700;
  color: var(--accent);
  margin-right: 12px;
}

.sidebar {
  grid-area: side;
  overflow: auto;
  background: var(--panel);
  min-width: 0;
}

.filter {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 6px 8px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
}

.filter {
  display: flex;
  align-items: center;
  gap: 4px;
}

.filter input,
.filter .grow {
  flex: 1;
  min-width: 0;
}

.tree {
  padding: 4px 0;
}

.notice {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 14px;
  color: var(--muted);
}

.resizer {
  grid-area: resize;
  cursor: col-resize;
  background: var(--border);
}

.resizer:hover {
  background: var(--accent);
}

.main {
  grid-area: main;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.tabs {
  display: flex;
  overflow-x: auto;
  background: var(--panel-2);
  border-bottom: 1px solid var(--border);
}

.tab {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 260px;
  padding: 6px 4px 6px 10px;
  border-right: 1px solid var(--border);
  cursor: default;
  white-space: nowrap;
}

.tab.on {
  background: var(--panel);
  box-shadow: inset 0 -2px 0 var(--accent);
}

.tab.prod {
  box-shadow: inset 0 2px 0 var(--danger);
}

.tab.prod.on {
  box-shadow: inset 0 2px 0 var(--danger), inset 0 -2px 0 var(--accent);
}

.tab .title {
  overflow: hidden;
  text-overflow: ellipsis;
}

.close {
  padding: 2px 3px;
}

.content {
  position: relative;
  flex: 1;
  min-height: 0;
}

.pane {
  position: absolute;
  inset: 0;
}

.welcome {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: var(--accent);
}

.logo {
  display: block;
}

/* narrow windows: the logo alone, so the toolbar keeps to one row */
@media (max-width: 1180px) {
  .brand-name {
    display: none;
  }

  .brand {
    margin-right: 2px;
  }
}

.welcome-logo {
  margin-bottom: 6px;
}

.welcome h1 {
  margin: 4px 0 0;
  font-size: 22px;
}

.welcome p {
  margin: 0;
  color: var(--muted);
}

.statusbar {
  grid-area: foot;
  display: flex;
  align-items: center;
  padding: 0 10px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
}
</style>
