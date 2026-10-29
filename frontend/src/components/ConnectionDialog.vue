<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import type { Connection, ConnectionInput, ConnectionTestResult, ConnectionType, SshConfig, SslConfig } from '@/api/types'
import { api } from '@/api/client'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import DriverGallery from './DriverGallery.vue'

const emit = defineEmits<{ saved: [connection: Connection] }>()

const connections = useConnections()
/** Folders already used, offered while typing. */
const folders = computed(() => [...new Set(connections.items.flatMap((c) => (c.folder ? [c.folder] : [])))].sort())
const ui = useUi()

const dialog = ref<HTMLDialogElement | null>(null)
const nameInput = ref<HTMLInputElement | null>(null)
const editing = ref<Connection | null>(null)
/** New connections start by picking the database, like DBeaver's wizard. */
const step = ref<'driver' | 'settings'>('settings')
const testing = ref(false)
const saving = ref(false)
const testResult = ref<{ ok: true; info: ConnectionTestResult } | { ok: false; message: string } | null>(null)

const form = reactive({
  name: '',
  driverId: '',
  host: '',
  port: null as number | null,
  database: '',
  user: '',
  password: '',
  url: '',
  properties: '',
  autoCommit: true,
  connectionType: 'DEV' as ConnectionType,
  readOnly: false,
  showAllDatabases: false,
  folder: '',
  ssh: {
    enabled: false,
    host: '',
    port: 22 as number | null,
    user: '',
    auth: 'PASSWORD' as SshConfig['auth'],
    password: '',
    privateKeyPath: '',
    passphrase: '',
    acceptNewHostKeys: true,
  },
  ssl: {
    enabled: false,
    mode: 'VERIFY_FULL' as SslConfig['mode'],
    caCertPath: '',
    clientCertPath: '',
    clientKeyPath: '',
  },
})

const sshPasswordPlaceholder = computed(() => (editing.value?.ssh?.hasPassword ? 'unchanged' : ''))
const sshPassphrasePlaceholder = computed(() => (editing.value?.ssh?.hasPassphrase ? 'unchanged' : 'none'))

const driver = computed(() => connections.drivers.find((d) => d.id === form.driverId))
const sample = computed(() => driver.value?.sampleUrl ?? '')
const hasHost = computed(() => sample.value.includes('{host}'))
const hasPort = computed(() => sample.value.includes('{port}'))
const hasDatabase = computed(() => /\{(database|file)}/.test(sample.value))
const databaseLabel = computed(() => (sample.value.includes('{file}') ? 'File' : 'Database'))
const passwordPlaceholder = computed(() => (editing.value?.hasPassword ? 'unchanged' : ''))

const sortedDrivers = computed(() => [...connections.drivers].sort((a, b) => a.label.localeCompare(b.label)))

watch(
  () => form.driverId,
  (id, previous) => {
    if (editing.value) return
    const next = connections.drivers.find((d) => d.id === id)
    const old = connections.drivers.find((d) => d.id === previous)
    if (!next) return
    if (form.port === null || form.port === old?.defaultPort) form.port = next.defaultPort
    if (!form.user || form.user === old?.defaultUser) form.user = next.defaultUser ?? ''
    if (!form.database || form.database === old?.defaultDatabase) form.database = next.defaultDatabase ?? ''
  },
)

function parseProperties(text: string): Record<string, string> {
  const result: Record<string, string> = {}
  for (const line of text.split('\n')) {
    const index = line.indexOf('=')
    if (index > 0) result[line.slice(0, index).trim()] = line.slice(index + 1).trim()
  }
  return result
}

function toInput(): ConnectionInput {
  const keepPassword = editing.value !== null && form.password === ''
  return {
    name: form.name.trim(),
    driverId: form.driverId,
    host: hasHost.value ? form.host.trim() || null : null,
    port: hasPort.value ? form.port : null,
    database: hasDatabase.value ? form.database.trim() || null : null,
    user: form.user.trim() || null,
    password: keepPassword ? undefined : form.password,
    url: form.url.trim() || null,
    properties: parseProperties(form.properties),
    autoCommit: form.autoCommit,
    connectionType: form.connectionType,
    readOnly: form.readOnly,
    showAllDatabases: isPostgres.value && form.showAllDatabases,
    folder: form.folder.trim() || null,
    ssh: sshInput(),
    ssl: form.ssl.enabled || editing.value?.ssl
      ? {
          enabled: form.ssl.enabled,
          mode: form.ssl.mode,
          caCertPath: form.ssl.caCertPath.trim() || null,
          clientCertPath: form.ssl.clientCertPath.trim() || null,
          clientKeyPath: form.ssl.clientKeyPath.trim() || null,
        }
      : null,
  }
}

/** Blank secret fields keep the stored value when editing (sent as undefined, i.e. omitted). */
function sshInput(): SshConfig | null {
  if (!form.ssh.enabled && !editing.value?.ssh) return null
  const keep = (value: string) => (editing.value !== null && value === '' ? undefined : value)
  return {
    enabled: form.ssh.enabled,
    host: form.ssh.host.trim() || null,
    port: form.ssh.port,
    user: form.ssh.user.trim() || null,
    auth: form.ssh.auth,
    password: keep(form.ssh.password),
    privateKeyPath: form.ssh.privateKeyPath.trim() || null,
    passphrase: keep(form.ssh.passphrase),
    acceptNewHostKeys: form.ssh.acceptNewHostKeys,
  }
}

function show(connection: Connection | null = null) {
  editing.value = connection
  testResult.value = null
  form.name = connection?.name ?? ''
  form.driverId = connection?.driverId ?? sortedDrivers.value.find((d) => d.id === 'postgres-jdbc')?.id ?? sortedDrivers.value[0]?.id ?? ''
  form.host = connection?.host ?? 'localhost'
  form.port = connection?.port ?? driver.value?.defaultPort ?? null
  form.database = connection?.database ?? driver.value?.defaultDatabase ?? ''
  form.user = connection?.user ?? driver.value?.defaultUser ?? ''
  form.password = ''
  form.url = connection?.url ?? ''
  form.properties = Object.entries(connection?.properties ?? {})
    .map(([k, v]) => `${k}=${v}`)
    .join('\n')
  form.autoCommit = connection?.autoCommit ?? true
  form.connectionType = connection?.connectionType ?? 'DEV'
  form.readOnly = connection?.readOnly ?? false
  form.showAllDatabases = connection?.showAllDatabases ?? false
  form.folder = connection?.folder ?? ''
  step.value = connection ? 'settings' : 'driver'
  const ssh = connection?.ssh
  Object.assign(form.ssh, {
    enabled: ssh?.enabled ?? false,
    host: ssh?.host ?? '',
    port: ssh?.port ?? 22,
    user: ssh?.user ?? '',
    auth: ssh?.auth ?? 'PASSWORD',
    password: '',
    privateKeyPath: ssh?.privateKeyPath ?? '',
    passphrase: '',
    acceptNewHostKeys: ssh?.acceptNewHostKeys ?? true,
  })
  const ssl = connection?.ssl
  Object.assign(form.ssl, {
    enabled: ssl?.enabled ?? false,
    mode: ssl?.mode ?? 'VERIFY_FULL',
    caCertPath: ssl?.caCertPath ?? '',
    clientCertPath: ssl?.clientCertPath ?? '',
    clientKeyPath: ssl?.clientKeyPath ?? '',
  })
  dialog.value?.showModal()
  if (connection) void nextTick(() => nameInput.value?.focus())
}

const isPostgres = computed(() => driver.value?.providerId === 'postgresql')

/** Gallery → settings: prefill the defaults of the chosen driver. */
function chooseDriver(id: string) {
  form.driverId = id
  const d = connections.drivers.find((x) => x.id === id)
  if (!form.name.trim() && d) form.name = d.label
  step.value = 'settings'
  void nextTick(() => nameInput.value?.focus())
}

async function test() {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = { ok: true, info: await api.testConnection(toInput(), editing.value?.id) }
  } catch (e) {
    testResult.value = { ok: false, message: e instanceof Error ? e.message : String(e) }
  } finally {
    testing.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const saved = await ui.guard(() => connections.save(toInput(), editing.value?.id))
    if (saved) {
      dialog.value?.close()
      emit('saved', saved)
    }
  } finally {
    saving.value = false
  }
}

defineExpose({ show })
</script>

<template>
  <dialog ref="dialog" class="dialog" :class="{ wide: step === 'driver' }" aria-labelledby="conn-dialog-title">
    <div v-if="step === 'driver'" class="form">
      <h2 id="conn-dialog-title">Connect to a database</h2>
      <p class="muted sub">Select your database. Type part of its name to find it; double-click or Next to continue.</p>
      <DriverGallery v-model="form.driverId" :drivers="connections.drivers" @choose="chooseDriver" />
      <div class="buttons">
        <span class="spacer" />
        <button type="button" @click="dialog?.close()">Cancel</button>
        <button type="button" class="primary" :disabled="!form.driverId" @click="chooseDriver(form.driverId)">Next &gt;</button>
      </div>
    </div>
    <form v-else class="form" @submit.prevent="save">
      <h2 id="conn-dialog-title">{{ editing ? 'Edit connection' : `New ${driver?.label ?? ''} connection` }}</h2>

      <label class="field">
        <span>Connection name</span>
        <input ref="nameInput" v-model="form.name" required placeholder="e.g. Local PostgreSQL" />
      </label>

      <label class="field">
        <span>Driver</span>
        <select v-model="form.driverId" required :disabled="editing !== null">
          <option v-for="d in sortedDrivers" :key="d.id" :value="d.id">{{ d.label }}</option>
        </select>
      </label>

      <div class="row">
        <label v-if="hasHost" class="field grow">
          <span>Host</span>
          <input v-model="form.host" placeholder="localhost" />
        </label>
        <label v-if="hasPort" class="field port">
          <span>Port</span>
          <input v-model.number="form.port" type="number" min="1" max="65535" />
        </label>
      </div>

      <label v-if="hasDatabase" class="field">
        <span>{{ databaseLabel }}</span>
        <input v-model="form.database" :placeholder="databaseLabel === 'File' ? '/path/to/file.db' : ''" />
      </label>

      <div class="row">
        <label class="field grow">
          <span>User</span>
          <input v-model="form.user" autocomplete="off" />
        </label>
        <label class="field grow">
          <span>Password</span>
          <input v-model="form.password" type="password" autocomplete="new-password" :placeholder="passwordPlaceholder" />
        </label>
      </div>

      <div class="row">
        <label class="field grow">
          <span>Connection type</span>
          <select v-model="form.connectionType" :class="`type-${form.connectionType.toLowerCase()}`">
            <option value="DEV">Development</option>
            <option value="TEST">Test</option>
            <option value="PROD">Production (colored, asks before changing data)</option>
          </select>
        </label>
        <label class="check ro" title="Only queries are allowed; enforced by the database where the driver supports it">
          <input v-model="form.readOnly" type="checkbox" />
          <span>Read-only</span>
        </label>
      </div>

      <details class="advanced">
        <summary>Advanced</summary>
        <label class="field">
          <span>JDBC URL (overrides host, port and database)</span>
          <input v-model="form.url" class="mono" :placeholder="sample" />
        </label>
        <label class="field">
          <span>Driver properties (one <code>key=value</code> per line)</span>
          <textarea v-model="form.properties" class="mono" rows="3" placeholder="sslmode=require" />
        </label>
        <label class="check">
          <input v-model="form.autoCommit" type="checkbox" />
          <span>Auto-commit</span>
        </label>
      </details>

      <details class="advanced" :open="form.ssh.enabled">
        <summary>SSH tunnel<template v-if="form.ssh.enabled"> · on</template></summary>
        <label class="check">
          <input v-model="form.ssh.enabled" type="checkbox" />
          <span>Connect through an SSH server (host and port above are seen from that server)</span>
        </label>
        <template v-if="form.ssh.enabled">
          <div class="row">
            <label class="field grow">
              <span>SSH host</span>
              <input v-model="form.ssh.host" required placeholder="bastion.example.com" />
            </label>
            <label class="field port">
              <span>Port</span>
              <input v-model.number="form.ssh.port" type="number" min="1" max="65535" />
            </label>
          </div>
          <div class="row">
            <label class="field grow">
              <span>SSH user</span>
              <input v-model="form.ssh.user" required autocomplete="off" />
            </label>
            <label class="field grow">
              <span>Authentication</span>
              <select v-model="form.ssh.auth">
                <option value="PASSWORD">Password</option>
                <option value="KEY">Private key</option>
              </select>
            </label>
          </div>
          <label v-if="form.ssh.auth === 'PASSWORD'" class="field">
            <span>SSH password</span>
            <input v-model="form.ssh.password" type="password" autocomplete="new-password" :placeholder="sshPasswordPlaceholder" />
          </label>
          <template v-else>
            <label class="field">
              <span>Private key file (on the machine running JustinBeaver)</span>
              <input v-model="form.ssh.privateKeyPath" class="mono" placeholder="~/.ssh/id_ed25519" />
            </label>
            <label class="field">
              <span>Key passphrase</span>
              <input v-model="form.ssh.passphrase" type="password" autocomplete="new-password" :placeholder="sshPassphrasePlaceholder" />
            </label>
          </template>
          <label class="check" title="A changed host key is always rejected">
            <input v-model="form.ssh.acceptNewHostKeys" type="checkbox" />
            <span>Trust and remember the host key on first connection</span>
          </label>
        </template>
      </details>

      <details class="advanced" :open="form.ssl.enabled">
        <summary>SSL / TLS<template v-if="form.ssl.enabled"> · on</template></summary>
        <label class="check">
          <input v-model="form.ssl.enabled" type="checkbox" />
          <span>Use SSL</span>
        </label>
        <template v-if="form.ssl.enabled">
          <label class="field">
            <span>Mode</span>
            <select v-model="form.ssl.mode">
              <option value="REQUIRE">Require (encrypt, do not verify the certificate)</option>
              <option value="VERIFY_CA">Verify CA</option>
              <option value="VERIFY_FULL">Verify CA and host name</option>
            </select>
          </label>
          <label class="field">
            <span>CA certificate (PEM; MySQL/SQL Server need a .jks/.p12 truststore)</span>
            <input v-model="form.ssl.caCertPath" class="mono" placeholder="/path/to/ca.pem" />
          </label>
          <div class="row">
            <label class="field grow">
              <span>Client certificate</span>
              <input v-model="form.ssl.clientCertPath" class="mono" placeholder="optional" />
            </label>
            <label class="field grow">
              <span>Client key</span>
              <input v-model="form.ssl.clientKeyPath" class="mono" placeholder="optional" />
            </label>
          </div>
        </template>
      </details>

      <p v-if="testing" class="status muted">Connecting… the first time this downloads the driver.</p>
      <p v-else-if="testResult?.ok" class="status ok">
        Connected: {{ testResult.info.productName }} {{ testResult.info.productVersion }} · driver
        {{ testResult.info.driverName }} {{ testResult.info.driverVersion }}
      </p>
      <p v-else-if="testResult" class="status err">{{ testResult.message }}</p>

      <label class="field">
        <span>Folder in the navigator (optional, "Clients/ACME" for nested folders)</span>
        <input v-model="form.folder" list="connection-folders" placeholder="Top level" />
        <datalist id="connection-folders">
          <option v-for="f in folders" :key="f" :value="f" />
        </datalist>
      </label>

      <label v-if="isPostgres" class="check">
        <input v-model="form.showAllDatabases" type="checkbox" />
        <span>Show all databases</span>
      </label>

      <div class="buttons">
        <button type="button" :disabled="testing || !form.driverId" @click="test">Test connection</button>
        <span class="spacer" />
        <button v-if="!editing" type="button" @click="step = 'driver'">&lt; Back</button>
        <button type="button" @click="dialog?.close()">Cancel</button>
        <button type="submit" class="primary" :disabled="saving">Save</button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.dialog.wide {
  width: min(960px, calc(100vw - 32px));
}

.sub {
  margin: -6px 0 0;
}

.dialog {
  width: min(520px, calc(100vw - 32px));
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
  gap: 12px;
  padding: 18px 20px;
}

h2 {
  margin: 0 0 4px;
  font-size: 16px;
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

.row {
  display: flex;
  gap: 10px;
}

.grow {
  flex: 1;
  min-width: 0;
}

.port {
  width: 96px;
}

.advanced {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
}

.advanced[open] {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

summary {
  cursor: pointer;
  color: var(--muted);
}

.ro {
  align-self: flex-end;
  padding-bottom: 6px;
}

select.type-prod {
  border-color: var(--danger);
  color: var(--danger);
}

select.type-test {
  border-color: var(--warn);
}

.check {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status {
  margin: 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--panel-2);
  word-break: break-word;
}

.status.ok {
  color: var(--ok);
}

.status.err {
  color: var(--danger);
}

.buttons {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.spacer {
  flex: 1;
}

code {
  font-family: var(--font-mono);
}
</style>
