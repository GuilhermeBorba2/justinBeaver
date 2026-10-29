<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { JobView, QueryResult, RoleInfo } from '@/api/types'
import { useSchemaService } from '@/composables/schema'
import { changePassword, createUser, dropUser, grantRole } from '@/lib/ddl'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import type { AdminTab } from '@/stores/workspace'
import AppIcon from './AppIcon.vue'
import ResultGrid from './ResultGrid.vue'

/** Session manager, lock viewer, dashboard and native backup/restore of one connection. */
const props = defineProps<{ tab: AdminTab; active: boolean }>()

type Section = 'sessions' | 'locks' | 'dashboard' | 'users' | 'backup'

const connections = useConnections()
const ui = useUi()

const section = ref<Section>(props.tab.section ?? 'sessions')
watch(
  () => props.tab.section,
  (value) => {
    if (value) section.value = value
  },
)
const result = ref<QueryResult | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)
const autoRefresh = ref(false)
const selectedRow = ref<number | null>(null)

const connection = computed(() => connections.connectionOf(props.tab.connectionId))
const connected = computed(() => connections.isConnected(props.tab.connectionId))
const sections: { id: Section; label: string }[] = [
  { id: 'sessions', label: 'Sessions' },
  { id: 'locks', label: 'Locks' },
  { id: 'dashboard', label: 'Dashboard' },
  { id: 'users', label: 'Users & roles' },
  { id: 'backup', label: 'Backup & restore' },
]

async function loadGrid() {
  if (!connected.value || (section.value !== 'sessions' && section.value !== 'locks')) return
  loading.value = true
  error.value = null
  try {
    result.value = section.value === 'sessions' ? await api.serverSessions(props.tab.connectionId) : await api.locks(props.tab.connectionId)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    result.value = null
  } finally {
    loading.value = false
  }
}

const selectedSessionId = computed(() => {
  if (section.value !== 'sessions' || selectedRow.value === null) return null
  const value = result.value?.rows[selectedRow.value]?.[0]
  return value === null || value === undefined ? null : String(value)
})

async function act(action: 'CANCEL' | 'TERMINATE') {
  const id = selectedSessionId.value
  if (!id) return
  const verb = action === 'CANCEL' ? 'Cancel the running statement of' : 'Terminate'
  if (!window.confirm(`${verb} session ${id}?`)) return
  const ok = await ui.guard(() => api.sessionAction(props.tab.connectionId, id, action))
  if (ok) {
    ui.info(action === 'CANCEL' ? `Cancel sent to session ${id}` : `Session ${id} terminated`)
    await loadGrid()
  }
}

// ---- users and roles -------------------------------------------------------
const schema = useSchemaService()
const roles = ref<RoleInfo[] | null>(null)
const selectedRole = computed(() => (section.value === 'users' && selectedRow.value !== null ? roles.value?.[selectedRow.value] : undefined))
const roleGrid = computed(() =>
  roles.value
    ? {
        columns: ['Name', 'Attributes', 'Member of'].map((name) => ({ name, typeName: 'text', kind: 'text' as const })),
        rows: roles.value.map((r) => [r.name, r.attributes.join(', '), r.memberOf.join(', ')]),
      }
    : null,
)

async function loadRoles() {
  if (!connected.value) return
  loading.value = true
  error.value = null
  try {
    roles.value = await api.roles(props.tab.connectionId)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    roles.value = null
  } finally {
    loading.value = false
  }
}

async function onCreateUser() {
  const name = window.prompt('New user (login) name:')?.trim()
  if (!name) return
  const password = window.prompt(`Password for ${name} (shown in the preview before it runs):`) ?? ''
  if (await schema.run(props.tab.connectionId, `Create user ${name}`, (ctx) => createUser(ctx, name, password))) await loadRoles()
}

async function onDropUser() {
  const role = selectedRole.value
  if (role && (await schema.run(props.tab.connectionId, `Drop ${role.name}`, (ctx) => dropUser(ctx, role.name), true))) await loadRoles()
}

async function onChangePassword() {
  const role = selectedRole.value
  if (!role) return
  const password = window.prompt(`New password for ${role.name}:`)
  if (!password) return
  await schema.run(props.tab.connectionId, `Change password of ${role.name}`, (ctx) => changePassword(ctx, role.name, password))
}

async function onGrantRole() {
  const member = selectedRole.value
  if (!member) return
  const role = window.prompt(`Grant which role to ${member.name}? (${roles.value?.map((r) => r.name).join(', ')})`)?.trim()
  if (role && (await schema.run(props.tab.connectionId, `Grant ${role} to ${member.name}`, (ctx) => grantRole(ctx, role, member.name)))) {
    await loadRoles()
  }
}

// ---- dashboard -------------------------------------------------------------
const HISTORY = 60
interface Series {
  unit: string
  kind: 'GAUGE' | 'COUNTER'
  points: number[]
  last: { value: number; at: number } | null
}
const series = reactive<Record<string, Series>>({})
const dashboardError = ref<string | null>(null)

async function sample() {
  try {
    const metrics = await api.metrics(props.tab.connectionId)
    dashboardError.value = null
    const now = Date.now()
    for (const m of metrics) {
      const s = (series[m.name] ??= { unit: m.unit, kind: m.kind, points: [], last: null })
      if (m.kind === 'COUNTER') {
        // cumulative counter -> rate per second since the previous sample
        if (s.last && now > s.last.at && m.value >= s.last.value) {
          s.points.push((m.value - s.last.value) / ((now - s.last.at) / 1000))
        }
        s.last = { value: m.value, at: now }
      } else {
        s.points.push(m.value)
      }
      if (s.points.length > HISTORY) s.points.shift()
    }
  } catch (e) {
    dashboardError.value = e instanceof Error ? e.message : String(e)
  }
}

function sparkline(points: number[]): string {
  if (points.length < 2) return ''
  const max = Math.max(...points, 1e-9)
  const min = Math.min(...points, 0)
  const span = max - min || 1
  return points
    .map((v, i) => `${(i / (HISTORY - 1)) * 200},${48 - ((v - min) / span) * 44}`)
    .join(' ')
}

function format(value: number | undefined): string {
  if (value === undefined) return '…'
  return value >= 100 ? Math.round(value).toLocaleString() : value.toFixed(value % 1 === 0 ? 0 : 2)
}

// ---- backup ----------------------------------------------------------------
const backupForm = reactive({ file: '', format: 'CUSTOM' as 'CUSTOM' | 'PLAIN', restoreFile: '' })
const job = ref<JobView | null>(null)

async function startBackup() {
  const started = await ui.guard(() => api.backup(props.tab.connectionId, backupForm.file, backupForm.format))
  if (started) job.value = started
}

async function startRestore() {
  if (!window.confirm(`Restore ${backupForm.restoreFile} into ${connection.value?.database ?? 'the database'}? Existing objects may be overwritten.`)) {
    return
  }
  const started = await ui.guard(() => api.restore(props.tab.connectionId, backupForm.restoreFile))
  if (started) job.value = started
}

async function cancelJob() {
  if (job.value) job.value = (await ui.guard(() => api.cancelJob(job.value!.id))) ?? job.value
}

async function pollJob() {
  if (job.value?.status === 'RUNNING') {
    job.value = (await api.job(job.value.id).catch(() => null)) ?? job.value
  }
}

// ---- timers ----------------------------------------------------------------
let timer: ReturnType<typeof setInterval> | undefined

function schedule() {
  clearInterval(timer)
  timer = undefined
  if (!props.active || !connected.value) return
  if (section.value === 'dashboard') {
    void sample()
    timer = setInterval(() => void sample(), 2000)
  } else if (section.value === 'backup') {
    timer = setInterval(() => void pollJob(), 1000)
  } else if (autoRefresh.value) {
    timer = setInterval(() => void loadGrid(), 5000)
  }
}

watch([section, () => props.active, connected, autoRefresh], () => schedule(), { immediate: true })
watch([section, connected], () => {
  selectedRow.value = null
  result.value = null
  void loadGrid()
  if (section.value === 'users') void loadRoles()
}, { immediate: true })
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <section class="admin">
    <div class="head">
      <AppIcon name="activity" :size="15" class="icon" />
      <strong>{{ connection?.name }}</strong>
      <span class="muted">administration</span>
    </div>
    <div class="sections" role="tablist">
      <button
        v-for="s in sections"
        :key="s.id"
        class="ghost"
        role="tab"
        :aria-selected="section === s.id"
        :class="{ on: section === s.id }"
        @click="section = s.id"
      >
        {{ s.label }}
      </button>
    </div>
    <div v-if="!connected" class="banner">Not connected — connect from the navigator.</div>

    <template v-if="section === 'sessions' || section === 'locks'">
      <div class="bar">
        <button :disabled="!connected || loading" @click="loadGrid"><AppIcon name="refresh" /> Refresh</button>
        <label class="check"><input v-model="autoRefresh" type="checkbox" /> Every 5 s</label>
        <template v-if="section === 'sessions'">
          <span class="sep" />
          <button :disabled="!selectedSessionId" @click="act('CANCEL')"><AppIcon name="stop" /> Cancel query</button>
          <button class="danger" :disabled="!selectedSessionId" @click="act('TERMINATE')"><AppIcon name="close" /> Terminate</button>
          <span class="muted hint">Select a row; the first column is the session id</span>
        </template>
      </div>
      <div v-if="error" class="error mono">{{ error }}</div>
      <div v-else class="grid">
        <ResultGrid v-if="result" :columns="result.columns" :rows="result.rows" @select="(row) => (selectedRow = row)" />
        <div v-else class="muted center">{{ loading ? 'Loading…' : '' }}</div>
      </div>
    </template>

    <template v-else-if="section === 'users'">
      <div class="bar">
        <button :disabled="!connected || loading" @click="loadRoles"><AppIcon name="refresh" /> Refresh</button>
        <span class="sep" />
        <button :disabled="!connected" @click="onCreateUser"><AppIcon name="plus" /> Create user</button>
        <button :disabled="!selectedRole" @click="onChangePassword"><AppIcon name="lock" /> Change password</button>
        <button :disabled="!selectedRole" @click="onGrantRole"><AppIcon name="key" /> Grant role</button>
        <button class="danger" :disabled="!selectedRole" @click="onDropUser"><AppIcon name="trash" /> Drop</button>
      </div>
      <div v-if="error" class="error mono">{{ error }}</div>
      <div v-else class="grid">
        <ResultGrid v-if="roleGrid" :columns="roleGrid.columns" :rows="roleGrid.rows" @select="(row) => (selectedRow = row)" />
        <div v-else class="muted center">{{ loading ? 'Loading…' : 'No user list for this database' }}</div>
      </div>
    </template>

    <div v-else-if="section === 'dashboard'" class="dashboard">
      <div v-if="dashboardError" class="error mono">{{ dashboardError }}</div>
      <div v-else class="cards">
        <div v-for="(s, name) in series" :key="name" class="card">
          <div class="card-head">
            <span class="name">{{ String(name).replaceAll('_', ' ') }}</span>
            <span class="value">{{ format(s.points[s.points.length - 1]) }}<small>{{ s.unit }}</small></span>
          </div>
          <svg viewBox="0 0 200 50" preserveAspectRatio="none" class="spark" role="img" :aria-label="`${name} over the last 2 minutes`">
            <polyline :points="sparkline(s.points)" />
          </svg>
        </div>
        <p v-if="Object.keys(series).length === 0" class="muted">Collecting…</p>
      </div>
      <p class="muted note">Sampled every 2 s while this tab is visible. Counters are shown as rates per second.</p>
    </div>

    <div v-else class="backup">
      <fieldset>
        <legend>Backup</legend>
        <label class="field">
          <span>File on this computer</span>
          <input v-model="backupForm.file" class="mono" placeholder="/home/me/backups/shop.dump" />
        </label>
        <label class="field">
          <span>Format (PostgreSQL)</span>
          <select v-model="backupForm.format">
            <option value="CUSTOM">Custom archive (pg_restore)</option>
            <option value="PLAIN">Plain SQL script</option>
          </select>
        </label>
        <button class="primary" :disabled="!connected || !backupForm.file.trim() || job?.status === 'RUNNING'" @click="startBackup">
          Start backup
        </button>
      </fieldset>
      <fieldset>
        <legend>Restore</legend>
        <label class="field">
          <span>Backup file</span>
          <input v-model="backupForm.restoreFile" class="mono" placeholder="/home/me/backups/shop.dump" />
        </label>
        <button class="danger" :disabled="!connected || !backupForm.restoreFile.trim() || job?.status === 'RUNNING'" @click="startRestore">
          Start restore
        </button>
      </fieldset>
      <p class="muted note">
        Runs pg_dump / pg_restore / psql or mysqldump / mysql installed on this computer (set
        <code>justinbeaver.tools.dir</code> if they are not on the PATH). Connections through SSH are tunneled.
      </p>
      <div v-if="job" class="job">
        <div class="job-head">
          <strong>{{ job.label }}</strong>
          <span class="status" :class="job.status.toLowerCase()">{{ job.status }}</span>
          <span class="spacer" />
          <button v-if="job.status === 'RUNNING'" @click="cancelJob">Stop</button>
        </div>
        <pre class="mono log">{{ job.log.join('\n') }}</pre>
      </div>
    </div>
  </section>
</template>

<style scoped>
.admin {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.icon {
  color: var(--accent);
}

.sections {
  display: flex;
  gap: 2px;
  padding: 4px 8px 0;
  background: var(--panel-2);
}

.sections .on {
  background: var(--panel);
  border-bottom: 2px solid var(--accent);
  border-radius: 4px 4px 0 0;
}

.banner {
  padding: 6px 10px;
  background: var(--accent-soft);
}

.bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.sep {
  width: 1px;
  height: 18px;
  background: var(--border);
}

.check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.hint {
  font-size: 11px;
}

.danger {
  color: var(--danger);
}

.grid {
  flex: 1;
  min-height: 0;
}

.center {
  display: grid;
  place-items: center;
  height: 100%;
}

.error {
  padding: 10px 12px;
  color: var(--danger);
  white-space: pre-wrap;
}

.dashboard,
.backup {
  flex: 1;
  overflow: auto;
  padding: 12px;
  background: var(--panel);
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 10px;
}

.card {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.name {
  color: var(--muted);
  text-transform: capitalize;
}

.value {
  font-size: 20px;
  font-weight: 600;
}

.value small {
  font-size: 11px;
  color: var(--muted);
  margin-left: 2px;
}

.spark {
  width: 100%;
  height: 48px;
}

.spark polyline {
  fill: none;
  stroke: var(--accent);
  stroke-width: 1.5;
  vector-effect: non-scaling-stroke;
}

.note {
  font-size: 12px;
}

fieldset {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0 0 12px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  max-width: 620px;
}

fieldset button {
  align-self: flex-start;
}

legend {
  color: var(--muted);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field > span {
  font-size: 12px;
  color: var(--muted);
}

.job {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}

.job-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  background: var(--panel-2);
}

.spacer {
  flex: 1;
}

.status.succeeded {
  color: var(--ok);
}

.status.failed,
.status.cancelled {
  color: var(--danger);
}

.status.running {
  color: var(--warn);
}

.log {
  margin: 0;
  padding: 8px 10px;
  max-height: 320px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
}

code {
  font-family: var(--font-mono);
}
</style>
