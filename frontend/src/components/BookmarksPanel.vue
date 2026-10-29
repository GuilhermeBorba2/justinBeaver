<script setup lang="ts">
import { ref } from 'vue'
import { useConnections } from '@/stores/connections'
import { usePrefs, type Bookmark } from '@/stores/prefs'
import { useUi } from '@/stores/ui'
import { useWorkspace } from '@/stores/workspace'
import AppIcon from './AppIcon.vue'

/** DBeaver's "Bookmarks": tables starred in the navigator, opened with one click (connecting if needed). */
const prefs = usePrefs()
const connections = useConnections()
const workspace = useWorkspace()
const ui = useUi()
const open = ref(true)

const label = (b: Bookmark) => [b.schema ?? b.catalog, b.table].filter(Boolean).join('.')

async function go(b: Bookmark) {
  const connection = connections.connectionOf(b.connectionId)
  if (!connection) {
    ui.error('The connection of this bookmark was deleted')
    return
  }
  if (!connection.connected) {
    await ui.guard(() => connections.connect(connections.baseId(b.connectionId)))
  }
  workspace.openTable(b.connectionId, b.catalog, b.schema, b.table)
}
</script>

<template>
  <section v-if="prefs.state.bookmarks.length" class="bookmarks" aria-label="Bookmarks">
    <button class="ghost header" :aria-expanded="open" @click="open = !open">
      <span class="caret" :class="{ open }"><AppIcon name="chevron" :size="10" /></span>
      <AppIcon name="star" :size="14" />
      Bookmarks
      <span class="muted count">{{ prefs.state.bookmarks.length }}</span>
    </button>
    <div v-if="open" role="group">
      <div
        v-for="b in prefs.state.bookmarks"
        :key="`${b.connectionId}|${b.catalog}|${b.schema}|${b.table}`"
        class="row"
        tabindex="0"
        :title="`${connections.connectionOf(b.connectionId)?.name ?? '?'} · ${label(b)}`"
        @click="go(b)"
        @keydown.enter.prevent="go(b)"
      >
        <AppIcon name="table" :size="13" class="icon" />
        <span class="name">{{ label(b) }}</span>
        <span class="muted conn">{{ connections.connectionOf(b.connectionId) ? connections.labelOf(b.connectionId) : 'deleted' }}</span>
        <button class="ghost remove" title="Remove bookmark" :aria-label="`Remove bookmark ${label(b)}`" @click.stop="prefs.toggleBookmark(b)">
          <AppIcon name="close" :size="11" />
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.bookmarks {
  border-top: 1px solid var(--border);
  padding-bottom: 6px;
}

.header {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
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

.row {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 4px 0 28px;
  cursor: default;
  white-space: nowrap;
}

.row:hover {
  background: var(--panel-2);
}

.icon {
  flex: none;
  color: var(--accent);
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
}

.conn {
  margin-left: auto;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.remove {
  display: none;
}

.row:hover .remove,
.row:focus-within .remove {
  display: inline-flex;
}
</style>
