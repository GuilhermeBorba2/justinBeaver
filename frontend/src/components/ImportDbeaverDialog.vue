<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { DbeaverPreview } from '@/api/types'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'

/** File → Import → DBeaver connections: reads data-sources.json (and its saved passwords) of a DBeaver workspace. */
const connections = useConnections()
const ui = useUi()

const dialog = ref<HTMLDialogElement | null>(null)
const dir = ref('')
const preview = ref<DbeaverPreview | null>(null)
const picked = ref<Set<string>>(new Set())
const loading = ref(false)
const error = ref<string | null>(null)
const outcome = ref<string | null>(null)

const importable = computed(() => preview.value?.connections.filter((c) => c.driverKnown) ?? [])

async function scan() {
  loading.value = true
  error.value = null
  outcome.value = null
  try {
    preview.value = await api.dbeaverPreview(dir.value.trim() || undefined)
    dir.value = preview.value.dir
    picked.value = new Set(preview.value.connections.filter((c) => c.driverKnown && !c.exists).map((c) => c.id))
  } catch (e) {
    preview.value = null
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function open() {
  preview.value = null
  outcome.value = null
  dialog.value?.showModal()
  void scan()
}

function toggle(id: string) {
  const next = new Set(picked.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  picked.value = next
}

async function run() {
  loading.value = true
  try {
    const result = await api.dbeaverImport(dir.value, [...picked.value])
    await connections.load()
    outcome.value =
      `Imported ${result.created.length} connection(s)` +
      (result.skipped.length ? `; skipped: ${result.skipped.map((s) => `${s.name} (${s.reason})`).join(', ')}` : '')
    ui.info(outcome.value)
    picked.value = new Set()
    await scan()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

const where = (c: DbeaverPreview['connections'][number]) =>
  c.url ?? [c.host, c.port].filter(Boolean).join(':') + (c.database ? `/${c.database}` : '')

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="import-title">
    <div class="form">
      <h2 id="import-title">Import connections from DBeaver</h2>
      <form class="dir" @submit.prevent="scan">
        <input v-model="dir" class="mono" placeholder="DBeaver workspace (.dbeaver folder)" aria-label="DBeaver workspace folder" />
        <button type="submit" :disabled="loading">Scan</button>
      </form>
      <p class="muted note">
        Default: <code>~/.local/share/DBeaverData/workspace6/General/.dbeaver</code> (Linux),
        <code>%APPDATA%\DBeaverData\…</code> (Windows), <code>~/Library/DBeaverData/…</code> (macOS). Saved passwords are
        read from <code>credentials-config.json</code> and stored encrypted here.
      </p>
      <p v-if="error" class="err">{{ error }}</p>
      <p v-for="w in preview?.warnings ?? []" :key="w" class="warn">{{ w }}</p>
      <div v-if="preview" class="list">
        <label v-for="c in preview.connections" :key="c.id" class="item" :class="{ off: !c.driverKnown }">
          <input type="checkbox" :checked="picked.has(c.id)" :disabled="!c.driverKnown || loading" @change="toggle(c.id)" />
          <span class="name">{{ c.name }}</span>
          <span v-if="c.type === 'PROD'" class="badge prod">PROD</span>
          <span v-else-if="c.type === 'TEST'" class="badge test">TEST</span>
          <span v-if="c.ssh" class="badge">SSH</span>
          <span v-if="c.folder" class="muted small">📁 {{ c.folder }}</span>
          <span class="muted small mono where">{{ where(c) }}</span>
          <span class="muted small">{{ c.user ?? '' }}{{ c.hasPassword ? ' · password' : '' }}</span>
          <span v-if="!c.driverKnown" class="small err">driver {{ c.driverId }} not available</span>
          <span v-else-if="c.exists" class="small warn">name exists: imported as "{{ c.name }} (DBeaver)"</span>
        </label>
        <p v-if="!preview.connections.length" class="muted">No connections in this workspace.</p>
      </div>
      <p v-if="outcome" class="ok">{{ outcome }}</p>
      <div class="buttons">
        <span class="muted small">{{ picked.size }} of {{ importable.length }} selected</span>
        <span class="spacer" />
        <button @click="dialog?.close()">Close</button>
        <button class="primary" :disabled="loading || picked.size === 0" @click="run">Import</button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(760px, calc(100vw - 32px));
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

.dir {
  display: flex;
  gap: 8px;
}

.dir input {
  flex: 1;
}

.note {
  margin: 0;
  font-size: 12px;
}

code {
  font-family: var(--font-mono);
}

.list {
  display: flex;
  flex-direction: column;
  max-height: 360px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 6px;
}

.item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
}

.item.off {
  opacity: 0.6;
}

.name {
  font-weight: 600;
}

.where {
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 280px;
  white-space: nowrap;
}

.small {
  font-size: 11px;
}

.badge {
  padding: 0 4px;
  border: 1px solid var(--border);
  border-radius: 3px;
  font-size: 9px;
  font-weight: 700;
}

.badge.prod {
  border-color: var(--danger);
  color: var(--danger);
}

.badge.test {
  border-color: var(--warn);
  color: var(--warn);
}

.err {
  color: var(--danger);
  margin: 0;
}

.warn {
  color: var(--warn);
  margin: 0;
}

.ok {
  color: var(--ok);
  margin: 0;
}

.buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.spacer {
  flex: 1;
}
</style>
