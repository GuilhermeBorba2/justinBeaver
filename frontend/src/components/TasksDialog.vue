<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { api } from '@/api/client'
import type { Task, TaskType, TaskView } from '@/api/types'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import AppIcon from './AppIcon.vue'

/**
 * Task manager (DBeaver's Database Tasks): a SQL script, a query exported to a file, or a backup, run now or on
 * a schedule by the server, each on a connection of its own. The last runs are kept with their outcome.
 */
const connections = useConnections()
const ui = useUi()

const dialog = ref<HTMLDialogElement | null>(null)
const tasks = ref<TaskView[]>([])
const selectedId = ref<string | null>(null)
const saving = ref(false)
let timer: ReturnType<typeof setInterval> | undefined

const blank = (): Task => ({
  id: null,
  name: '',
  type: 'SQL',
  connectionId: connections.items[0]?.id ?? '',
  sql: '',
  file: '',
  format: 'CSV',
  schedule: { kind: 'MANUAL', everyMinutes: 60, at: '02:00', cron: '0 0 3 * * MON-FRI' },
  enabled: true,
  runs: [],
})
const form = reactive<Task>(blank())

const selected = computed(() => tasks.value.find((t) => t.task.id === selectedId.value))
const typeLabel: Record<TaskType, string> = { SQL: 'Run SQL script', EXPORT: 'Export query to file', BACKUP: 'Backup database' }

function scheduleText(t: Task): string {
  const s = t.schedule
  switch (s.kind) {
    case 'MANUAL':
      return 'manual'
    case 'INTERVAL':
      return `every ${s.everyMinutes} min`
    case 'DAILY':
      return `daily at ${s.at}`
    case 'CRON':
      return `cron ${s.cron}`
  }
}

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '—')

async function refresh() {
  const list = await api.tasks().catch(() => null)
  if (list) tasks.value = list
}

async function open() {
  await refresh()
  pick(tasks.value[0]?.task.id ?? null)
  dialog.value?.showModal()
  clearInterval(timer)
  timer = setInterval(() => void refresh(), 3000)
}

function close() {
  clearInterval(timer)
  dialog.value?.close()
}
onBeforeUnmount(() => clearInterval(timer))

function pick(id: string | null) {
  selectedId.value = id
  const t = tasks.value.find((x) => x.task.id === id)?.task
  Object.assign(form, t ? JSON.parse(JSON.stringify(t)) : blank())
  if (!form.schedule.everyMinutes) form.schedule.everyMinutes = 60
  if (!form.schedule.at) form.schedule.at = '02:00'
  if (!form.schedule.cron) form.schedule.cron = '0 0 3 * * MON-FRI'
}

function newTask() {
  pick(null)
  selectedId.value = null
}

function onTypeChange() {
  if (form.type === 'EXPORT' && !['CSV', 'TSV', 'JSON'].includes(form.format ?? '')) form.format = 'CSV'
  if (form.type === 'BACKUP' && !['CUSTOM', 'PLAIN'].includes(form.format ?? '')) form.format = 'CUSTOM'
  if (form.type !== 'SQL' && !form.file) {
    form.file = form.type === 'EXPORT' ? '~/justinbeaver-exports/{name}-{date}.csv' : '~/justinbeaver-backups/{name}-{date}.dump'
  }
}

async function save() {
  saving.value = true
  try {
    const saved = await ui.guard(() => api.saveTask({ ...form, runs: [] }))
    if (!saved) return
    ui.info(`Task ${saved.task.name} saved${saved.nextRun ? `; next run ${when(saved.nextRun)}` : ''}`)
    await refresh()
    pick(saved.task.id)
  } finally {
    saving.value = false
  }
}

async function remove() {
  const t = selected.value?.task
  if (!t?.id || !window.confirm(`Delete task ${t.name}?`)) return
  if ((await ui.guard(() => api.deleteTask(t.id!))) === undefined) return
  await refresh()
  newTask()
}

async function runNow() {
  const id = selected.value?.task.id
  if (!id) return
  if ((await ui.guard(() => api.runTask(id))) === undefined) return
  ui.info('Task started')
  setTimeout(() => void refresh(), 500)
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="tasks-title" @cancel.prevent="close">
    <div class="layout">
      <aside class="list" aria-label="Tasks">
        <div class="list-head">
          <h2 id="tasks-title">Tasks</h2>
          <button class="ghost" title="New task" aria-label="New task" @click="newTask"><AppIcon name="plus" /></button>
        </div>
        <button
          v-for="t in tasks"
          :key="t.task.id!"
          class="task"
          :class="{ on: t.task.id === selectedId, off: !t.task.enabled }"
          @click="pick(t.task.id)"
        >
          <span class="name">
            <span class="dot" :class="t.running ? 'run' : t.task.runs[0] ? (t.task.runs[0].ok ? 'ok' : 'bad') : ''" />
            {{ t.task.name }}
          </span>
          <span class="muted small">{{ typeLabel[t.task.type] }} · {{ scheduleText(t.task) }}</span>
          <span v-if="t.nextRun" class="muted small">next {{ when(t.nextRun) }}</span>
        </button>
        <p v-if="!tasks.length" class="muted small empty">No tasks yet: fill the form to create one.</p>
      </aside>
      <div class="page">
        <div class="grid">
          <label class="field"><span>Name</span><input v-model="form.name" placeholder="Nightly cleanup" /></label>
          <label class="field">
            <span>Type</span>
            <select v-model="form.type" @change="onTypeChange">
              <option v-for="(label, id) in typeLabel" :key="id" :value="id">{{ label }}</option>
            </select>
          </label>
          <label class="field wide">
            <span>Connection</span>
            <select v-model="form.connectionId">
              <option v-for="c in connections.items" :key="c.id" :value="c.id">{{ c.name }}{{ c.connectionType === 'PROD' ? ' (PROD)' : '' }}</option>
            </select>
          </label>
          <label v-if="form.type !== 'BACKUP'" class="field wide">
            <span>{{ form.type === 'SQL' ? 'SQL script (statements separated by ;)' : 'Query to export' }}</span>
            <textarea v-model="form.sql" class="mono" rows="6" spellcheck="false" />
          </label>
          <template v-if="form.type !== 'SQL'">
            <label class="field wide">
              <span>File on the server ({date}, {time}, {name} are replaced)</span>
              <input v-model="form.file" class="mono" />
            </label>
            <label class="field">
              <span>Format</span>
              <select v-model="form.format">
                <template v-if="form.type === 'EXPORT'">
                  <option value="CSV">CSV</option>
                  <option value="TSV">TSV</option>
                  <option value="JSON">JSON</option>
                </template>
                <template v-else>
                  <option value="CUSTOM">Custom archive (pg_restore)</option>
                  <option value="PLAIN">Plain SQL</option>
                </template>
              </select>
            </label>
          </template>
          <label class="field">
            <span>Schedule</span>
            <select v-model="form.schedule.kind">
              <option value="MANUAL">Manual only</option>
              <option value="INTERVAL">Every N minutes</option>
              <option value="DAILY">Daily at</option>
              <option value="CRON">Cron expression</option>
            </select>
          </label>
          <label v-if="form.schedule.kind === 'INTERVAL'" class="field">
            <span>Minutes</span><input v-model.number="form.schedule.everyMinutes" type="number" min="1" />
          </label>
          <label v-else-if="form.schedule.kind === 'DAILY'" class="field">
            <span>Time (server clock)</span><input v-model="form.schedule.at" type="time" />
          </label>
          <label v-else-if="form.schedule.kind === 'CRON'" class="field">
            <span>sec min hour day month weekday</span><input v-model="form.schedule.cron" class="mono" />
          </label>
          <label class="check wide"><input v-model="form.enabled" type="checkbox" /> Enabled (scheduled runs happen while JustinBeaver is running)</label>
        </div>
        <div class="buttons">
          <button v-if="selected" class="danger" @click="remove"><AppIcon name="trash" /> Delete</button>
          <span class="spacer" />
          <button v-if="selected" :disabled="selected.running" @click="runNow"><AppIcon name="play" /> {{ selected.running ? 'Running…' : 'Run now' }}</button>
          <button class="primary" :disabled="saving" @click="save"><AppIcon name="commit" /> {{ selected ? 'Save' : 'Create' }}</button>
        </div>
        <div v-if="selected" class="runs">
          <div class="heading">Last runs</div>
          <div v-for="(r, i) in selected.task.runs" :key="i" class="run" :class="{ bad: !r.ok }">
            <span class="dot" :class="r.ok ? 'ok' : 'bad'" />
            <span class="muted">{{ when(r.startedAt) }}</span>
            <span class="muted">{{ Math.max(0, Math.round((Date.parse(r.endedAt) - Date.parse(r.startedAt)) / 100) / 10) }} s</span>
            <span class="msg mono">{{ r.message }}</span>
          </div>
          <p v-if="!selected.task.runs.length" class="muted small">Not run yet.</p>
        </div>
        <div class="buttons end">
          <span class="spacer" />
          <button @click="close">Close</button>
        </div>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(960px, calc(100vw - 32px));
  max-height: calc(100vh - 32px);
  overflow: auto;
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

.layout {
  display: grid;
  grid-template-columns: 260px 1fr;
  min-height: 520px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 8px;
  border-right: 1px solid var(--border);
  background: var(--panel-2);
  overflow: auto;
}

.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px 8px;
}

h2 {
  margin: 0;
  font-size: 16px;
}

.task {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 6px 8px;
  border: 1px solid transparent;
  text-align: left;
}

.task.on {
  background: var(--accent-soft);
  border-color: var(--accent);
}

.task.off .name {
  opacity: 0.6;
}

.name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--border);
  flex: none;
}

.dot.ok {
  background: var(--ok);
}

.dot.bad {
  background: var(--danger);
}

.dot.run {
  background: var(--warn);
}

.small {
  font-size: 11px;
}

.empty {
  padding: 8px;
}

.page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 20px;
  min-width: 0;
}

.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.field > span {
  font-size: 12px;
  color: var(--muted);
}

.wide {
  grid-column: 1 / -1;
}

.check {
  display: flex;
  align-items: center;
  gap: 8px;
}

textarea {
  resize: vertical;
  font-size: 12px;
}

.buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.buttons.end {
  margin-top: auto;
}

.spacer {
  flex: 1;
}

.danger {
  color: var(--danger);
}

.runs {
  border: 1px solid var(--border);
  border-radius: 6px;
  max-height: 180px;
  overflow: auto;
  font-size: 12px;
}

.heading {
  padding: 4px 8px;
  background: var(--panel-2);
  font-weight: 600;
  position: sticky;
  top: 0;
}

.run {
  display: grid;
  grid-template-columns: 10px 150px 50px 1fr;
  gap: 8px;
  align-items: start;
  padding: 3px 8px;
  border-top: 1px solid var(--border);
}

.run .dot {
  margin-top: 4px;
}

.run.bad .msg {
  color: var(--danger);
}

.msg {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 11px;
}

.runs .small {
  padding: 6px 8px;
  margin: 0;
}
</style>
