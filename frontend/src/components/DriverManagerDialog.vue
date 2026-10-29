<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import type { Driver, DriverInput } from '@/api/types'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'

/**
 * Driver manager: add a JDBC driver that is not in the gallery (class name, URL template, JAR from Maven or a
 * local file). Saved as a descriptor next to the bundled ones; built-in drivers stay read-only.
 */
const connections = useConnections()
const ui = useUi()

const DIALECTS = ['generic', 'postgresql', 'mysql', 'mariadb', 'sqlite', 'h2', 'sqlserver', 'oracle']

const dialog = ref<HTMLDialogElement | null>(null)
const customIds = ref<string[]>([])
const editing = ref<string | null>(null)
const saving = ref(false)
const form = reactive({
  id: '',
  label: '',
  className: '',
  sampleUrl: '',
  defaultPort: null as number | null,
  defaultDatabase: '',
  defaultUser: '',
  dialect: 'generic',
  libraries: '',
  description: '',
})

const custom = computed(() => connections.drivers.filter((d) => customIds.value.includes(d.id)))

async function open() {
  customIds.value = (await ui.guard(() => api.customDrivers())) ?? []
  reset()
  dialog.value?.showModal()
}

function reset() {
  editing.value = null
  Object.assign(form, {
    id: '',
    label: '',
    className: '',
    sampleUrl: 'jdbc:vendor://{host}[:{port}]/[{database}]',
    defaultPort: null,
    defaultDatabase: '',
    defaultUser: '',
    dialect: 'generic',
    libraries: '',
    description: '',
  })
}

function edit(d: Driver) {
  editing.value = d.id
  Object.assign(form, {
    id: d.id,
    label: d.label,
    className: d.className,
    sampleUrl: d.sampleUrl ?? '',
    defaultPort: d.defaultPort,
    defaultDatabase: d.defaultDatabase ?? '',
    defaultUser: d.defaultUser ?? '',
    dialect: d.dialect ?? 'generic',
    libraries: (d.libraries ?? []).map((l) => l.maven ?? l.file ?? '').join('\n'),
    description: d.description ?? '',
  })
}

/** One library per line: `group:artifact:version` (Maven Central) or a path to a .jar. */
function libraries() {
  return form.libraries
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => (/^[\w.-]+:[\w.-]+:[\w.+-]+$/.test(l) && !l.endsWith('.jar') ? { maven: l, optional: false } : { file: l, optional: false }))
}

async function save() {
  const driver: DriverInput = {
    id: form.id.trim().toLowerCase(),
    label: form.label.trim() || form.id.trim(),
    className: form.className.trim(),
    sampleUrl: form.sampleUrl.trim() || null,
    defaultPort: form.defaultPort || null,
    defaultDatabase: form.defaultDatabase.trim() || null,
    defaultUser: form.defaultUser.trim() || null,
    dialect: form.dialect,
    libraries: libraries(),
    categories: ['sql'],
    description: form.description.trim() || 'Custom driver',
  }
  saving.value = true
  try {
    const saved = await ui.guard(() => api.saveCustomDriver(driver))
    if (!saved) return
    await connections.load()
    customIds.value = (await api.customDrivers().catch(() => customIds.value)) ?? customIds.value
    ui.info(`Driver ${saved.label} saved: pick it in New connection`)
    reset()
  } finally {
    saving.value = false
  }
}

async function remove(d: Driver) {
  const used = connections.items.filter((c) => c.driverId === d.id).map((c) => c.name)
  if (!window.confirm(`Delete driver ${d.label}?${used.length ? `\nUsed by: ${used.join(', ')} (they stop working)` : ''}`)) return
  if ((await ui.guard(() => api.deleteCustomDriver(d.id))) === undefined) return
  await connections.load()
  customIds.value = customIds.value.filter((id) => id !== d.id)
  if (editing.value === d.id) reset()
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="drivers-title">
    <div class="form">
      <h2 id="drivers-title">Driver manager</h2>
      <p class="muted note">{{ connections.drivers.length }} drivers available; the ones below were added here and can be edited.</p>
      <div class="list">
        <div v-for="d in custom" :key="d.id" class="item">
          <strong>{{ d.label }}</strong>
          <span class="muted mono small">{{ d.id }} · {{ d.className }}</span>
          <span class="spacer" />
          <button class="ghost" @click="edit(d)">Edit</button>
          <button class="ghost danger" @click="remove(d)">Delete</button>
        </div>
        <p v-if="!custom.length" class="muted small empty">No custom drivers yet.</p>
      </div>
      <fieldset :disabled="saving">
        <legend>{{ editing ? `Edit ${editing}` : 'New driver' }}</legend>
        <div class="grid">
          <label class="field"><span>Id</span><input v-model="form.id" class="mono" :disabled="!!editing" placeholder="acme-db" /></label>
          <label class="field"><span>Name</span><input v-model="form.label" placeholder="ACME DB" /></label>
          <label class="field wide"><span>Driver class</span><input v-model="form.className" class="mono" placeholder="com.acme.jdbc.Driver" /></label>
          <label class="field wide">
            <span>URL template ({host}, {port}, {database}; [ … ] is dropped when empty)</span>
            <input v-model="form.sampleUrl" class="mono" />
          </label>
          <label class="field"><span>Default port</span><input v-model.number="form.defaultPort" type="number" min="1" max="65535" /></label>
          <label class="field"><span>SQL dialect</span>
            <select v-model="form.dialect"><option v-for="d in DIALECTS" :key="d" :value="d">{{ d }}</option></select>
          </label>
          <label class="field"><span>Default database</span><input v-model="form.defaultDatabase" /></label>
          <label class="field"><span>Default user</span><input v-model="form.defaultUser" /></label>
          <label class="field wide">
            <span>Libraries, one per line: Maven <code>group:artifact:version</code> or a local <code>.jar</code> path</span>
            <textarea v-model="form.libraries" class="mono" rows="3" placeholder="com.acme:acme-jdbc:2.1.0" />
          </label>
        </div>
        <div class="buttons">
          <button v-if="editing" type="button" @click="reset">New instead</button>
          <span class="spacer" />
          <button class="primary" type="button" :disabled="!form.id.trim() || !form.className.trim() || !form.libraries.trim()" @click="save">
            Save driver
          </button>
        </div>
      </fieldset>
      <div class="buttons">
        <span class="spacer" />
        <button @click="dialog?.close()">Close</button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(720px, calc(100vw - 32px));
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

.form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 20px;
}

h2 {
  margin: 0;
  font-size: 16px;
}

.note,
.small {
  font-size: 12px;
  margin: 0;
}

.list {
  border: 1px solid var(--border);
  border-radius: 6px;
  max-height: 160px;
  overflow: auto;
}

.item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  border-bottom: 1px solid var(--border);
}

.empty {
  padding: 8px 10px;
}

fieldset {
  border: 1px solid var(--border);
  border-radius: 6px;
}

.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
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

code {
  font-family: var(--font-mono);
}

.danger {
  color: var(--danger);
}

.buttons {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}

.spacer {
  flex: 1;
}
</style>
