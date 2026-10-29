<script setup lang="ts">
import { computed, ref } from 'vue'
import { useConnections } from '@/stores/connections'
import { useQueryLog, type QueryLogEntry } from '@/stores/querylog'
import { useWorkspace } from '@/stores/workspace'

/** "Query Manager": every statement run in this browser, with connection, time, duration and outcome. */
const log = useQueryLog()
const connections = useConnections()
const workspace = useWorkspace()

const dialog = ref<HTMLDialogElement | null>(null)
const filter = ref('')
const connection = ref('')
const onlyErrors = ref(false)

const shown = computed(() => {
  const needle = filter.value.toLowerCase()
  return log.entries
    .filter((e) => (!connection.value || connections.baseId(e.connectionId) === connection.value) && (!onlyErrors.value || !e.ok))
    .filter((e) => !needle || e.sql.toLowerCase().includes(needle) || e.summary.toLowerCase().includes(needle))
    .slice(0, 500)
})

function open() {
  dialog.value?.showModal()
}

function reopen(entry: QueryLogEntry) {
  const id = connections.connectionOf(entry.connectionId) ? entry.connectionId : (workspace.focusedConnectionId ?? '')
  workspace.openSql(id, id ? connections.labelOf(id) : 'N/A', entry.sql.endsWith(';') ? entry.sql : `${entry.sql};`)
  dialog.value?.close()
}

function clearAll() {
  if (window.confirm('Clear the whole query log?')) log.clear()
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="qm-title">
    <div class="form">
      <div class="title-row">
        <h2 id="qm-title">Query manager</h2>
        <span class="muted">{{ log.entries.length.toLocaleString() }} statement(s)</span>
      </div>
      <div class="filters">
        <input v-model="filter" type="search" placeholder="Filter by text…" aria-label="Filter statements" />
        <select v-model="connection" aria-label="Connection">
          <option value="">All connections</option>
          <option v-for="c in connections.items" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <label class="check"><input v-model="onlyErrors" type="checkbox" /> Errors only</label>
      </div>
      <div class="list" role="table" aria-label="Executed statements">
        <div class="row head" role="row">
          <span role="columnheader">Time</span>
          <span role="columnheader">Connection</span>
          <span role="columnheader">Statement</span>
          <span role="columnheader" class="num">Duration</span>
          <span role="columnheader">Result</span>
        </div>
        <button v-for="e in shown" :key="e.id" class="row" role="row" title="Open in a new script" @click="reopen(e)">
          <span class="muted">{{ new Date(e.at).toLocaleString() }}</span>
          <span class="ellipsis">{{ connections.connectionOf(e.connectionId) ? connections.labelOf(e.connectionId) : '?' }}</span>
          <code class="mono ellipsis">{{ e.sql }}</code>
          <span class="num mono">{{ e.elapsedMs === null ? '' : `${e.elapsedMs.toLocaleString()} ms` }}</span>
          <span class="ellipsis" :class="{ bad: !e.ok }">{{ e.summary }}</span>
        </button>
        <p v-if="!shown.length" class="muted empty">Nothing run yet{{ filter || connection || onlyErrors ? ' matching the filter' : '' }}.</p>
      </div>
      <div class="buttons">
        <button :disabled="!log.entries.length" @click="clearAll">Clear log</button>
        <span class="spacer" />
        <button class="primary" @click="dialog?.close()">Close</button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(1000px, calc(100vw - 32px));
  max-height: calc(100vh - 32px);
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
  color: var(--text);
  box-shadow: 0 20px 60px rgb(0 0 0 / 0.35);
}

.dialog::backdrop {
  background: rgb(0 0 0 / 0.45);
}

.form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 20px;
  max-height: calc(100vh - 34px);
  box-sizing: border-box;
}

.title-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

h2 {
  margin: 0;
  font-size: 16px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.filters input {
  flex: 1;
  min-width: 200px;
}

.check {
  display: flex;
  align-items: center;
  gap: 6px;
}

.list {
  flex: 1;
  min-height: 200px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 12px;
}

.row {
  display: grid;
  grid-template-columns: 150px 140px 1fr 80px 200px;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 4px 8px;
  border: 0;
  border-bottom: 1px solid var(--border);
  border-radius: 0;
  background: transparent;
  text-align: left;
}

button.row:hover {
  background: var(--panel-2);
}

.head {
  position: sticky;
  top: 0;
  background: var(--panel-2);
  font-weight: 600;
}

.ellipsis {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.num {
  text-align: right;
}

.bad {
  color: var(--danger);
}

.empty {
  padding: 12px;
}

.buttons {
  display: flex;
  gap: 8px;
}

.spacer {
  flex: 1;
}
</style>
