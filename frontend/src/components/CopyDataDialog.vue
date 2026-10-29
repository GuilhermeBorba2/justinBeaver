<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { Container, SearchHit, TableDetails } from '@/api/types'
import { useSchemaService } from '@/composables/schema'
import { createTable, type TableRef } from '@/lib/ddl'
import { columnSpecs, copyRows } from '@/lib/transfer'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import { useWorkspace } from '@/stores/workspace'

/**
 * "Export data → Database table" (DBeaver data transfer Table → Table): copy rows to a table in any
 * open connection, optionally creating it with the source's columns. Rows are read page by page
 * and inserted in batches; columns are matched by name, case-insensitively.
 */
interface Source {
  connectionId: string
  catalog: string | null
  schema: string | null
  table: string
  where: string
}

const connections = useConnections()
const workspace = useWorkspace()
const schema = useSchemaService()
const ui = useUi()

const dialog = ref<HTMLDialogElement | null>(null)
const source = ref<Source | null>(null)
const targetId = ref('')
const mode = ref<'existing' | 'new'>('existing')
const searchText = ref('')
const hits = ref<SearchHit[]>([])
const target = ref<SearchHit | null>(null)
const containers = ref<Container[]>([])
const containerKey = ref('')
const newName = ref('')
const running = ref(false)
const cancelled = ref(false)
const progress = ref('')

const connected = computed(() => connections.items.filter((c) => c.connected))
const containerOf = (c: Container) => `${c.catalog ?? ''}.${c.schema ?? ''}`

function open(from: Source) {
  source.value = from
  targetId.value = connected.value.find((c) => c.id !== from.connectionId)?.id ?? from.connectionId
  mode.value = 'existing'
  searchText.value = ''
  hits.value = []
  target.value = null
  newName.value = from.table
  progress.value = ''
  dialog.value?.showModal()
}

watch(targetId, async (id) => {
  if (!id) return
  target.value = null
  hits.value = []
  containers.value = (await ui.guard(() => api.containers(id))) ?? []
  containerKey.value = containers.value[0] ? containerOf(containers.value[0]) : ''
})

let timer: ReturnType<typeof setTimeout> | undefined
watch(searchText, (value) => {
  clearTimeout(timer)
  timer = setTimeout(async () => {
    hits.value = value.trim() ? ((await ui.guard(() => api.search(targetId.value, value, 20))) ?? []) : []
  }, 200)
})

/** Create the target table with the source columns (types translated when the databases differ). */
async function createTarget(details: TableDetails): Promise<TableRef | null> {
  const container = containers.value.find((c) => containerOf(c) === containerKey.value)
  const ref: TableRef = { catalog: container?.catalog ?? null, schema: container?.schema ?? null, name: newName.value.trim() }
  const sourceDialect = (await schema.context(source.value!.connectionId)).dialect
  const ok = await schema.run(targetId.value, `Create ${ref.name}`, (ctx) =>
    createTable(ctx, ref, columnSpecs(details, ctx.dialect, ctx.dialect === sourceDialect)),
  )
  return ok ? ref : null
}

async function start() {
  const src = source.value
  if (!src) return
  running.value = true
  cancelled.value = false
  progress.value = 'Reading table structure…'
  try {
    const srcScope = { catalog: src.catalog, schema: src.schema }
    const srcDetails = await api.tableDetails(src.connectionId, srcScope, src.table)
    let dest: TableRef | null
    if (mode.value === 'new') {
      dest = await createTarget(srcDetails)
      if (!dest) return
    } else {
      if (!target.value) throw new Error('Pick the target table')
      dest = { catalog: target.value.catalog, schema: target.value.schema, name: target.value.name }
    }
    const destScope = { catalog: dest.catalog, schema: dest.schema }
    const { copied, skippedBinary, stopped } = await copyRows(
      { connectionId: src.connectionId, scope: srcScope, table: src.table },
      { connectionId: targetId.value, scope: destScope, table: dest.name },
      src.where,
      {
        onProgress: (n) => (progress.value = `${n.toLocaleString()} row(s) copied…`),
        isCancelled: () => cancelled.value,
        markDirty: () => connections.markDirty(targetId.value),
      },
    )
    progress.value = `${stopped ? 'Stopped after' : 'Done:'} ${copied.toLocaleString()} row(s) copied to ${dest.name}` +
      (skippedBinary.length ? `. Binary columns not copied: ${skippedBinary.join(', ')}` : '')
    ui.info(progress.value)
    connections.schemaChanged(targetId.value)
  } catch (e) {
    progress.value = `Failed: ${e instanceof Error ? e.message : String(e)} (rows already inserted stay inserted)`
  } finally {
    running.value = false
  }
}

function openTarget() {
  if (target.value) workspace.openTable(targetId.value, target.value.catalog, target.value.schema, target.value.name)
  dialog.value?.close()
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="copy-title" @cancel.prevent="!running && dialog?.close()">
    <div class="form">
      <h2 id="copy-title">Copy data from {{ source?.table }}</h2>
      <p v-if="source?.where" class="muted note">Only rows matching the current filter: <code>{{ source.where }}</code></p>

      <label class="field">
        <span>Target connection</span>
        <select v-model="targetId" :disabled="running">
          <option v-for="c in connected" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
      </label>

      <div class="modes" role="radiogroup">
        <label class="check"><input v-model="mode" type="radio" value="existing" :disabled="running" /> Existing table</label>
        <label class="check"><input v-model="mode" type="radio" value="new" :disabled="running" /> New table with the same columns</label>
      </div>

      <template v-if="mode === 'existing'">
        <label class="field">
          <span>Target table</span>
          <input v-model="searchText" placeholder="Search by name…" :disabled="running" />
        </label>
        <ul v-if="hits.length" class="hits">
          <li v-for="h in hits" :key="`${h.catalog}.${h.schema}.${h.name}`">
            <button class="ghost" :class="{ on: target === h }" @click="target = h">
              <span class="mono">{{ h.name }}</span> <span class="muted">{{ h.container }}</span>
            </button>
          </li>
        </ul>
        <p v-if="target" class="muted note">Target: <strong class="mono">{{ target.container }}.{{ target.name }}</strong></p>
      </template>
      <template v-else>
        <div class="row">
          <label class="field grow">
            <span>In</span>
            <select v-model="containerKey" :disabled="running">
              <option v-for="c in containers" :key="containerOf(c)" :value="containerOf(c)">{{ c.label }}</option>
            </select>
          </label>
          <label class="field grow">
            <span>New table name</span>
            <input v-model="newName" class="mono" :disabled="running" />
          </label>
        </div>
        <p class="muted note">Column types are copied by name; across different databases you may need to adjust them in the preview.</p>
      </template>

      <p v-if="progress" class="status">{{ progress }}</p>
      <div class="buttons">
        <button v-if="running" @click="cancelled = true">Stop</button>
        <button v-else-if="mode === 'existing' && target && progress.startsWith('Done')" @click="openTarget">Open target</button>
        <span class="spacer" />
        <button :disabled="running" @click="dialog?.close()">Close</button>
        <button class="primary" :disabled="running || (mode === 'existing' ? !target : !newName.trim())" @click="start">Copy</button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(560px, calc(100vw - 32px));
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

.modes {
  display: flex;
  gap: 16px;
}

.check {
  display: flex;
  align-items: center;
  gap: 6px;
}

.hits {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 180px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 6px;
}

.hits button {
  width: 100%;
  justify-content: flex-start;
  border-radius: 0;
}

.hits .on {
  background: var(--accent-soft);
}

.note {
  margin: 0;
  font-size: 12px;
}

.status {
  margin: 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--panel-2);
}

.buttons {
  display: flex;
  gap: 8px;
}

.spacer {
  flex: 1;
}

code {
  font-family: var(--font-mono);
}
</style>
