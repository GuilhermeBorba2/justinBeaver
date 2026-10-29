<script setup lang="ts">
import { computed, ref } from 'vue'
import { isExpandable, loadChildren, type NavDescriptor } from '@/lib/navigator'
import { openContextMenu } from '@/composables/contextMenu'
import { useMenus } from '@/composables/menus'
import { useNavFilter } from '@/composables/navFilter'
import { useSchemaService } from '@/composables/schema'
import { api } from '@/api/client'
import { useConnections } from '@/stores/connections'
import { usePrefs } from '@/stores/prefs'
import { useUi } from '@/stores/ui'
import { useWorkspace } from '@/stores/workspace'
import AppIcon, { type IconName } from './AppIcon.vue'

const props = withDefaults(defineProps<{ node: NavDescriptor; depth?: number; initiallyExpanded?: boolean }>(), {
  depth: 0,
  initiallyExpanded: false,
})

const ui = useUi()
const workspace = useWorkspace()
const schema = useSchemaService()

/** Where "New table" creates the table: schema/catalog nodes and their Tables folder. */
const isTablesGroup = computed(() => props.node.kind === 'group' && props.node.label === 'Tables')
const canCreateTable = computed(() => props.node.kind === 'container' || isTablesGroup.value)

function newScript() {
  workspace.openSql(props.node.connectionId, connections.labelOf(props.node.connectionId))
}

async function newTable() {
  await schema.createTable(props.node.connectionId, props.node.catalog ?? null, props.node.schema ?? null)
}

const prefs = usePrefs()
const bookmark = computed(() =>
  props.node.kind === 'table' && props.node.table
    ? { connectionId: props.node.connectionId, catalog: props.node.catalog ?? null, schema: props.node.schema ?? null, table: props.node.table }
    : null,
)
const starred = computed(() => (bookmark.value ? prefs.isBookmarked(bookmark.value) : false))

const filter = useNavFilter()
const expanded = ref(false)
const loading = ref(false)
const children = ref<NavDescriptor[] | null>(null)

const icon = computed<IconName>(() => {
  switch (props.node.kind) {
    case 'connection':
    case 'database':
      return 'database'
    case 'container':
      return 'schema'
    case 'group':
      return 'schema'
    case 'table':
      return props.node.isView ? 'view' : 'table'
    case 'column':
      return props.node.primaryKey ? 'key' : 'column'
    case 'object':
      return 'script'
  }
})

/** The filter narrows the leaves of folders (tables, views, routines), not the folders themselves. */
const visibleChildren = computed(() => {
  const needle = filter.value.trim().toLowerCase()
  if (!children.value || !needle || props.node.kind !== 'group') return children.value ?? []
  return children.value.filter((c) => c.label.toLowerCase().includes(needle))
})

async function load(force = false) {
  if (children.value && !force) return
  loading.value = true
  try {
    children.value = await loadChildren(props.node)
  } catch (e) {
    ui.error(e instanceof Error ? e.message : String(e))
    children.value = []
    expanded.value = false
  } finally {
    loading.value = false
  }
}

async function toggle() {
  if (!isExpandable(props.node)) return
  expanded.value = !expanded.value
  if (expanded.value) await load()
}

async function refresh() {
  children.value = null
  if (expanded.value) await load(true)
}

const connections = useConnections()

/** Procedures, functions, sequences, triggers: open their source in a SQL editor. */
async function openSource() {
  const node = props.node
  const result = await ui.guard(() =>
    api.source(node.connectionId, { catalog: node.catalog, schema: node.schema }, node.objectKind!, node.label),
  )
  if (result) {
    const name = connections.labelOf(node.connectionId)
    workspace.openSql(node.connectionId, `${name} · ${node.label}`, result.source)
  }
}

const menus = useMenus()

async function onMenu(event: MouseEvent) {
  event.preventDefault()
  const at = { x: event.clientX, y: event.clientY }
  // a table's maintenance tasks depend on the database: ask the server first (cached after the first time)
  const dialect = props.node.kind === 'table' ? await schema.context(props.node.connectionId).then((c) => c.dialect, () => undefined) : undefined
  openContextMenu(at, menus.nodeMenu(props.node, { refresh: () => void refresh(), open, expandable: isExpandable(props.node), dialect }))
}

/** F5 refreshes this level; Enter (below) opens it. */
function onKeys(event: KeyboardEvent) {
  if (event.key === 'F5') {
    event.preventDefault()
    void refresh()
  }
}

function open() {
  if (props.node.kind === 'object') {
    void openSource()
  } else if (props.node.kind === 'table' && props.node.table) {
    workspace.openTable(props.node.connectionId, props.node.catalog ?? null, props.node.schema ?? null, props.node.table)
  } else {
    void toggle()
  }
}

if (props.initiallyExpanded) {
  expanded.value = true
  void load()
}

defineExpose({ refresh })
</script>

<template>
  <div class="node" role="treeitem" :aria-expanded="isExpandable(node) ? expanded : undefined">
    <div
      class="row"
      :class="[node.kind, { system: node.system }]"
      :style="{ paddingLeft: 8 + depth * 14 + 'px' }"
      tabindex="0"
      @click="toggle"
      @dblclick.stop="open"
      @contextmenu.stop="onMenu"
      @keydown="onKeys"
      @keydown.enter.prevent="open"
      @keydown.right.prevent="!expanded && toggle()"
      @keydown.left.prevent="expanded && toggle()"
    >
      <span class="caret" :class="{ open: expanded, hidden: !isExpandable(node) }">
        <AppIcon name="chevron" :size="10" />
      </span>
      <AppIcon :name="icon" :size="14" class="kind-icon" />
      <span class="label" :class="{ current: node.current }" :title="node.label">{{ node.label }}</span>
      <span v-if="loading" class="muted spinner">…</span>
      <span v-else-if="node.detail" class="detail muted">{{ node.detail }}</span>
      <button v-if="node.kind === 'database'" class="ghost new" title="New SQL script on this database" aria-label="New SQL script" @click.stop="newScript">
        <AppIcon name="sql" :size="12" />
      </button>
      <button
        v-if="bookmark"
        class="ghost new star"
        :class="{ on: starred }"
        :title="starred ? 'Remove bookmark' : 'Bookmark this table'"
        :aria-label="starred ? 'Remove bookmark' : 'Bookmark this table'"
        :aria-pressed="starred"
        @click.stop="prefs.toggleBookmark(bookmark)"
      >
        <AppIcon name="star" :size="12" />
      </button>
      <button
        v-if="node.kind === 'container' || isTablesGroup"
        class="ghost new"
        title="ER diagram of this schema"
        aria-label="ER diagram"
        @click.stop="workspace.openDiagram(node.connectionId, node.catalog ?? null, node.schema ?? null, node.kind === 'group' ? connections.labelOf(node.connectionId) : node.label)"
      >
        <AppIcon name="diagram" :size="12" />
      </button>
      <button v-if="canCreateTable" class="ghost new" title="New table" aria-label="New table" @click.stop="newTable">
        <AppIcon name="plus" :size="12" />
      </button>
    </div>
    <div v-if="expanded && children" class="children" role="group">
      <div v-if="children.length === 0 && !loading" class="empty muted" :style="{ paddingLeft: 30 + depth * 14 + 'px' }">
        (empty)
      </div>
      <NavNode v-for="child in visibleChildren" :key="child.key" :node="child" :depth="depth + 1" />
      <div v-if="children.length && visibleChildren.length === 0" class="empty muted" :style="{ paddingLeft: 30 + depth * 14 + 'px' }">
        (no match for "{{ filter }}")
      </div>
    </div>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 24px;
  padding-right: 8px;
  cursor: default;
  user-select: none;
  white-space: nowrap;
}

.row:hover {
  background: var(--panel-2);
}

.row:focus-visible {
  outline-offset: -2px;
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

.kind-icon {
  flex: none;
  color: var(--muted);
}

.row.connection .kind-icon {
  color: var(--accent);
}

.row.column .kind-icon {
  opacity: 0.7;
}

.label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.label.current {
  font-weight: 700;
}

.row.system .label {
  color: var(--muted);
}

.detail {
  margin-left: auto;
  font-size: 11px;
  padding-left: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.new {
  display: none;
  padding: 1px 3px;
  margin-left: 4px;
}

.row:hover .new,
.row:focus-within .new {
  display: inline-flex;
}

.empty {
  height: 22px;
  font-style: italic;
}
.star.on {
  display: inline-flex;
  color: var(--warn);
}
</style>
