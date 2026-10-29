<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Connection } from '@/api/types'
import AppIcon from './AppIcon.vue'
import ConnectionNode from './ConnectionNode.vue'

/** Connections grouped in their folders ("Clients/ACME" nests ACME in Clients), folders first, by name. */
const props = defineProps<{ connections: Connection[] }>()
const emit = defineEmits<{ edit: [connection: Connection]; remove: [connection: Connection] }>()

const KEY = 'justinbeaver.folders.closed'
const closed = ref<Set<string>>(readClosed())

function readClosed(): Set<string> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    return new Set(Array.isArray(parsed) ? parsed.filter((p): p is string => typeof p === 'string') : [])
  } catch {
    return new Set()
  }
}

function toggle(path: string) {
  const next = new Set(closed.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  closed.value = next
  try {
    localStorage.setItem(KEY, JSON.stringify([...next]))
  } catch {
    // best effort
  }
}

interface Folder {
  path: string
  name: string
  folders: Map<string, Folder>
  connections: Connection[]
}

type Row =
  | { kind: 'folder'; path: string; name: string; depth: number; count: number }
  | { kind: 'connection'; connection: Connection; depth: number }

function count(folder: Folder): number {
  return folder.connections.length + [...folder.folders.values()].reduce((n, f) => n + count(f), 0)
}

const rows = computed<Row[]>(() => {
  const root: Folder = { path: '', name: '', folders: new Map(), connections: [] }
  for (const c of props.connections) {
    let at = root
    for (const part of (c.folder ?? '').split('/').filter(Boolean)) {
      const path = at.path ? `${at.path}/${part}` : part
      if (!at.folders.has(part)) at.folders.set(part, { path, name: part, folders: new Map(), connections: [] })
      at = at.folders.get(part)!
    }
    at.connections.push(c)
  }
  const out: Row[] = []
  const walk = (folder: Folder, depth: number) => {
    for (const sub of [...folder.folders.values()].sort((a, b) => a.name.localeCompare(b.name))) {
      out.push({ kind: 'folder', path: sub.path, name: sub.name, depth, count: count(sub) })
      if (!closed.value.has(sub.path)) walk(sub, depth + 1)
    }
    for (const c of folder.connections) out.push({ kind: 'connection', connection: c, depth })
  }
  walk(root, 0)
  return out
})
</script>

<template>
  <template v-for="row in rows" :key="row.kind === 'folder' ? `f:${row.path}` : row.connection.id">
    <div
      v-if="row.kind === 'folder'"
      class="folder"
      role="treeitem"
      :aria-expanded="!closed.has(row.path)"
      tabindex="0"
      :style="{ paddingLeft: 8 + row.depth * 14 + 'px' }"
      @click="toggle(row.path)"
      @keydown.enter.prevent="toggle(row.path)"
    >
      <span class="caret" :class="{ open: !closed.has(row.path) }"><AppIcon name="chevron" :size="10" /></span>
      <AppIcon name="folder" :size="14" class="icon" />
      <span class="name">{{ row.name }}</span>
      <span class="muted count">{{ row.count }}</span>
    </div>
    <div v-else :style="{ marginLeft: row.depth * 14 + 'px' }">
      <ConnectionNode :connection="row.connection" @edit="emit('edit', $event)" @remove="emit('remove', $event)" />
    </div>
  </template>
</template>

<style scoped>
.folder {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding-right: 8px;
  cursor: default;
  user-select: none;
  font-weight: 600;
}

.folder:hover {
  background: var(--panel-2);
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

.icon {
  color: var(--warn);
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.count {
  margin-left: auto;
  font-size: 11px;
  font-weight: 400;
}
</style>
