<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ScriptInfo } from '@/api/types'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import { useWorkspace } from '@/stores/workspace'
import { askText } from '@/composables/prompt'
import { tr } from '@/lib/menu'
import AppIcon from './AppIcon.vue'
import DriveMenu from './DriveMenu.vue'

/** DBeaver's "Scripts" folder: every saved `.sql` file, grouped by the connection it runs on. */
const connections = useConnections()
const workspace = useWorkspace()
const ui = useUi()

const OPEN_KEY = 'justinbeaver.scripts.open'
const open = ref(readOpen())

function readOpen(): boolean {
  try {
    return localStorage.getItem(OPEN_KEY) !== 'false'
  } catch {
    return true
  }
}

function toggle() {
  open.value = !open.value
  try {
    localStorage.setItem(OPEN_KEY, String(open.value))
  } catch {
    // best effort
  }
  if (open.value) void workspace.refreshScripts()
}

const groups = computed(() => {
  const byConnection = new Map<string, { label: string; scripts: ScriptInfo[] }>()
  for (const script of workspace.scripts) {
    const known = script.connectionId ? connections.connectionOf(script.connectionId) : undefined
    const key = known ? connections.baseId(script.connectionId!) : ''
    const label = known ? known.name : 'No connection'
    if (!byConnection.has(key)) byConnection.set(key, { label, scripts: [] })
    byConnection.get(key)!.scripts.push(script)
  }
  return [...byConnection.entries()]
    .sort(([a, x], [b, y]) => (a === '' ? 1 : b === '' ? -1 : x.label.localeCompare(y.label)))
    .map(([key, group]) => ({ key, ...group }))
})

const openNames = computed(() => new Set(workspace.tabs.flatMap((t) => (t.kind === 'sql' && t.scriptName ? [t.scriptName] : []))))

function when(modified: number) {
  return new Date(modified).toLocaleString()
}

async function openScript(script: ScriptInfo) {
  await ui.guard(() => workspace.openScript(script.name, workspace.focusedConnectionId))
}

async function rename(script: ScriptInfo) {
  const name = (await askText(tr('Rename script', 'Renomear script'), script.name, tr('New name', 'Novo nome'), tr('Rename', 'Renomear')))?.trim()
  if (!name || name === script.name) return
  await ui.guard(() => workspace.renameScript(script.name, name))
}

async function remove(script: ScriptInfo) {
  if (!window.confirm(`Delete script "${script.name}"? The .sql file is deleted.`)) return
  await ui.guard(() => workspace.deleteScript(script.name))
}
</script>

<template>
  <section class="scripts" aria-label="Scripts">
    <div class="head">
      <button class="ghost header" :aria-expanded="open" @click="toggle">
        <span class="caret" :class="{ open }"><AppIcon name="chevron" :size="10" /></span>
        <AppIcon name="folder" :size="14" />
        Scripts
        <span class="muted count">{{ workspace.scripts.length }}</span>
      </button>
      <DriveMenu scope="scripts" align-right />
    </div>
    <div v-if="open" role="group">
      <div v-if="workspace.scripts.length === 0" class="empty muted">No saved scripts. New SQL editors are saved here.</div>
      <template v-for="group in groups" :key="group.key">
        <div class="group muted">{{ group.label }}</div>
        <div
          v-for="script in group.scripts"
          :key="script.name"
          class="row"
          :class="{ opened: openNames.has(script.name) }"
          tabindex="0"
          :title="`${script.name}.sql · ${script.size.toLocaleString()} chars · ${when(script.modified)}`"
          @click="openScript(script)"
          @keydown.enter.prevent="openScript(script)"
        >
          <AppIcon name="sql" :size="13" class="icon" />
          <span class="name">{{ script.name }}</span>
          <span class="actions" @click.stop>
            <button class="ghost" title="Rename script" :aria-label="`Rename ${script.name}`" @click="rename(script)"><AppIcon name="edit" /></button>
            <button class="ghost danger" title="Delete script" :aria-label="`Delete ${script.name}`" @click="remove(script)">
              <AppIcon name="trash" />
            </button>
          </span>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.scripts {
  border-top: 1px solid var(--border);
  padding-bottom: 6px;
}

.head {
  display: flex;
  align-items: center;
  padding-right: 6px;
}

.header {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  padding: 6px 8px;
  border: 0;
  border-radius: 0;
  font-weight: 600;
  text-align: left;
}

.caret {
  display: inline-flex;
  color: var(--muted);
  transition: transform 0.1s;
}

.caret.open {
  transform: rotate(90deg);
}

.count {
  margin-left: auto;
  font-weight: 400;
  font-size: 11px;
}

.group {
  padding: 4px 12px 2px 28px;
  font-size: 11px;
  font-weight: 600;
}

.row {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 4px 0 34px;
  cursor: default;
  white-space: nowrap;
}

.row:hover {
  background: var(--panel-2);
}

.row.opened .name {
  font-weight: 600;
}

.icon {
  flex: none;
  color: var(--accent);
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
}

.actions {
  display: none;
  margin-left: auto;
}

.row:hover .actions,
.row:focus-within .actions {
  display: inline-flex;
}

.danger {
  color: var(--danger);
}

.empty {
  padding: 4px 12px 4px 28px;
  font-size: 12px;
}
</style>
