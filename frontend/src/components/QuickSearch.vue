<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { SearchHit } from '@/api/types'
import { useConnections } from '@/stores/connections'
import { useWorkspace } from '@/stores/workspace'
import AppIcon from './AppIcon.vue'

/** "Open Database Object" (DBeaver Ctrl+Shift+D): find a table or view by name in every open connection. */
interface Hit extends SearchHit {
  connectionId: string
  connectionName: string
}

const connections = useConnections()
const workspace = useWorkspace()

const dialog = ref<HTMLDialogElement | null>(null)
const input = ref<HTMLInputElement | null>(null)
const text = ref('')
const hits = ref<Hit[]>([])
const active = ref(0)
const searching = ref(false)
const failures = ref<string[]>([])
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0

const connected = computed(() => connections.items.filter((c) => c.connected))

function open() {
  text.value = ''
  hits.value = []
  failures.value = []
  dialog.value?.showModal()
  void nextTick(() => input.value?.focus())
}

async function search(value: string) {
  const mine = ++generation
  if (!value.trim()) {
    hits.value = []
    return
  }
  searching.value = true
  const settled = await Promise.allSettled(
    connected.value.map(async (c) => (await api.search(c.id, value, 50)).map((h) => ({ ...h, connectionId: c.id, connectionName: c.name }))),
  )
  if (mine !== generation) return // a newer search started meanwhile
  hits.value = settled.flatMap((r) => (r.status === 'fulfilled' ? r.value : []))
  failures.value = settled.flatMap((r, i) => (r.status === 'rejected' ? [connected.value[i]!.name] : []))
  active.value = 0
  searching.value = false
}

watch(text, (value) => {
  clearTimeout(timer)
  timer = setTimeout(() => void search(value), 200)
})

function choose(hit: Hit | undefined) {
  if (!hit) return
  workspace.openTable(hit.connectionId, hit.catalog, hit.schema, hit.name)
  dialog.value?.close()
}

function onKey(event: KeyboardEvent) {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    active.value = Math.min(hits.value.length - 1, active.value + 1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    active.value = Math.max(0, active.value - 1)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    choose(hits.value[active.value])
  }
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-label="Open database object">
    <div class="body">
      <input
        ref="input"
        v-model="text"
        class="search"
        placeholder="Table or view name…"
        aria-label="Object name"
        role="combobox"
        :aria-expanded="hits.length > 0"
        aria-controls="quick-search-results"
        @keydown="onKey"
      />
      <p v-if="connected.length === 0" class="muted note">Connect to a database first.</p>
      <p v-else-if="text && !searching && hits.length === 0" class="muted note">No match in {{ connected.length }} connection(s).</p>
      <p v-if="failures.length" class="note err">Search failed on: {{ failures.join(', ') }}</p>
      <ul id="quick-search-results" class="results" role="listbox">
        <li
          v-for="(hit, i) in hits"
          :key="`${hit.connectionId}/${hit.catalog}/${hit.schema}/${hit.name}`"
          role="option"
          :aria-selected="i === active"
          :class="{ on: i === active }"
          @mousemove="active = i"
          @click="choose(hit)"
        >
          <AppIcon :name="hit.type.includes('VIEW') ? 'view' : 'table'" />
          <span class="name mono">{{ hit.name }}</span>
          <span class="muted where">{{ hit.container }} · {{ hit.connectionName }}</span>
        </li>
      </ul>
      <p class="muted hint">↑↓ to move · Enter to open · Esc to close</p>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(620px, calc(100vw - 32px));
  margin-top: 12vh;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
  color: var(--text);
  box-shadow: 0 20px 60px rgb(0 0 0 / 0.35);
}

.dialog::backdrop {
  background: rgb(0 0 0 / 0.35);
}

.body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
}

.search {
  font-size: 15px;
  padding: 8px 10px;
}

.results {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 50vh;
  overflow: auto;
}

.results li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 5px;
  cursor: pointer;
}

.results li.on {
  background: var(--accent-soft);
}

.where {
  margin-left: auto;
  font-size: 11px;
}

.note,
.hint {
  margin: 2px 4px;
  font-size: 12px;
}

.err {
  color: var(--danger);
}
</style>
