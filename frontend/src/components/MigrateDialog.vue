<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { Container, TableInfo } from '@/api/types'
import { useSchemaService } from '@/composables/schema'
import { createTable, truncateTable, type TableRef } from '@/lib/ddl'
import { SYSTEM_CONTAINERS } from '@/lib/navigator'
import { columnSpecs, copyRows } from '@/lib/transfer'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'

/**
 * "Migrate tables" (DBeaver's data transfer Database → Database): several tables of one schema into a schema
 * of any open connection. Missing target tables are created first (one previewed script), then rows are
 * copied table by table.
 */
interface Side {
  connectionId: string
  containers: Container[]
  key: string
}

const connections = useConnections()
const schema = useSchemaService()
const ui = useUi()

const dialog = ref<HTMLDialogElement | null>(null)
const source = reactive<Side>({ connectionId: '', containers: [], key: '' })
const target = reactive<Side>({ connectionId: '', containers: [], key: '' })
const tables = ref<TableInfo[]>([])
const picked = ref<Set<string>>(new Set())
const createMissing = ref(true)
const emptyFirst = ref(false)
const running = ref(false)
const cancelled = ref(false)
const log = ref<string[]>([])

const connected = computed(() => connections.items.filter((c) => c.connected))
const keyOf = (c: Container) => `${c.catalog ?? ''}.${c.schema ?? ''}`
const containerOf = (side: Side) => side.containers.find((c) => keyOf(c) === side.key)
const scopeOf = (side: Side) => ({ catalog: containerOf(side)?.catalog ?? null, schema: containerOf(side)?.schema ?? null })

function open() {
  log.value = []
  source.connectionId = connections.items.find((c) => c.connected)?.id ?? ''
  target.connectionId = connected.value.find((c) => c.id !== source.connectionId)?.id ?? source.connectionId
  dialog.value?.showModal()
}

async function loadContainers(side: Side) {
  side.containers = side.connectionId ? ((await ui.guard(() => api.containers(side.connectionId))) ?? []) : []
  // the user's schema, not information_schema / pg_catalog
  const first = side.containers.find((c) => !SYSTEM_CONTAINERS.test(c.label)) ?? side.containers[0]
  side.key = first ? keyOf(first) : ''
}

watch(() => source.connectionId, () => void loadContainers(source))
watch(() => target.connectionId, () => void loadContainers(target))
watch(
  () => [source.connectionId, source.key],
  async () => {
    picked.value = new Set()
    tables.value = []
    if (!source.connectionId || !containerOf(source)) return
    const all = (await ui.guard(() => api.tables(source.connectionId, scopeOf(source)))) ?? []
    tables.value = all.filter((t) => !t.type.toUpperCase().includes('VIEW'))
  },
)

function toggle(name: string) {
  const next = new Set(picked.value)
  if (next.has(name)) next.delete(name)
  else next.add(name)
  picked.value = next
}

function toggleAll() {
  picked.value = picked.value.size === tables.value.length ? new Set() : new Set(tables.value.map((t) => t.name))
}

async function start() {
  const names = tables.value.map((t) => t.name).filter((n) => picked.value.has(n))
  if (names.length === 0) return
  const srcScope = scopeOf(source)
  const destScope = scopeOf(target)
  if (source.connectionId === target.connectionId && source.key === target.key) {
    ui.error('Source and target are the same schema')
    return
  }
  running.value = true
  cancelled.value = false
  log.value = []
  try {
    const existing = new Map(((await api.tables(target.connectionId, destScope)) ?? []).map((t) => [t.name.toLowerCase(), t.name]))
    const missing = names.filter((n) => !existing.has(n.toLowerCase()))
    if (missing.length && !createMissing.value) {
      log.value.push(`Skipped (no such table on the target): ${missing.join(', ')}`)
    }
    if (missing.length && createMissing.value) {
      log.value.push(`Creating ${missing.length} table(s)…`)
      const sourceDialect = (await schema.context(source.connectionId)).dialect
      const details = await Promise.all(missing.map((n) => api.tableDetails(source.connectionId, srcScope, n)))
      const ok = await schema.run(target.connectionId, `Create ${missing.length} table(s) on the target`, (ctx) =>
        details.flatMap((d, i) =>
          createTable(ctx, { ...destScope, name: missing[i]! } as TableRef, columnSpecs(d, ctx.dialect, ctx.dialect === sourceDialect)),
        ),
      )
      if (!ok) {
        log.value.push('Stopped: the tables were not created')
        return
      }
      missing.forEach((n) => existing.set(n.toLowerCase(), n))
    }
    const todo = names.filter((n) => existing.has(n.toLowerCase()))
    if (emptyFirst.value && todo.length) {
      const ok = await schema.run(
        target.connectionId,
        `Delete the rows of ${todo.length} target table(s)`,
        (ctx) => todo.flatMap((n) => truncateTable(ctx, { ...destScope, name: existing.get(n.toLowerCase())! } as TableRef)),
        true,
      )
      if (!ok) {
        log.value.push('Stopped: the target tables were not emptied')
        return
      }
    }
    let total = 0
    for (const name of todo) {
      if (cancelled.value) break
      const destName = existing.get(name.toLowerCase())!
      const line = log.value.push(`${name}: copying…`) - 1
      try {
        const outcome = await copyRows(
          { connectionId: source.connectionId, scope: srcScope, table: name },
          { connectionId: target.connectionId, scope: destScope, table: destName },
          '',
          {
            onProgress: (n) => (log.value[line] = `${name}: ${n.toLocaleString()} row(s)…`),
            isCancelled: () => cancelled.value,
            markDirty: () => connections.markDirty(target.connectionId),
          },
        )
        total += outcome.copied
        log.value[line] = `${name}: ${outcome.copied.toLocaleString()} row(s)${outcome.stopped ? ' (stopped)' : ''}` +
          (outcome.skippedBinary.length ? ` · binary columns not copied: ${outcome.skippedBinary.join(', ')}` : '')
      } catch (e) {
        log.value[line] = `${name}: FAILED · ${e instanceof Error ? e.message : String(e)}`
      }
    }
    log.value.push(`${cancelled.value ? 'Stopped' : 'Done'}: ${total.toLocaleString()} row(s) copied`)
    connections.schemaChanged(target.connectionId)
  } catch (e) {
    log.value.push(`Failed: ${e instanceof Error ? e.message : String(e)}`)
  } finally {
    running.value = false
  }
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="migrate-title" @cancel.prevent="!running && dialog?.close()">
    <div class="form">
      <h2 id="migrate-title">Migrate tables</h2>
      <div class="sides">
        <fieldset v-for="(side, i) in [source, target]" :key="i" :disabled="running">
          <legend>{{ i === 0 ? 'Source' : 'Target' }}</legend>
          <select v-model="side.connectionId" :aria-label="`${i === 0 ? 'Source' : 'Target'} connection`">
            <option v-for="c in connected" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <select v-model="side.key" :aria-label="`${i === 0 ? 'Source' : 'Target'} schema`">
            <option v-for="c in side.containers" :key="keyOf(c)" :value="keyOf(c)">{{ c.label }}</option>
          </select>
        </fieldset>
      </div>
      <div class="tables">
        <label class="check all">
          <input type="checkbox" :checked="tables.length > 0 && picked.size === tables.length" :disabled="running || !tables.length" @change="toggleAll" />
          Tables ({{ picked.size }}/{{ tables.length }})
        </label>
        <label v-for="t in tables" :key="t.name" class="check">
          <input type="checkbox" :checked="picked.has(t.name)" :disabled="running" @change="toggle(t.name)" />
          <span class="mono">{{ t.name }}</span>
        </label>
        <p v-if="!tables.length" class="muted note">No tables in this schema</p>
      </div>
      <div class="opts">
        <label class="check"><input v-model="createMissing" type="checkbox" :disabled="running" /> Create missing tables (types translated)</label>
        <label class="check"><input v-model="emptyFirst" type="checkbox" :disabled="running" /> Delete target rows first</label>
      </div>
      <pre v-if="log.length" class="log mono">{{ log.join('\n') }}</pre>
      <div class="buttons">
        <button v-if="running" @click="cancelled = true">Stop</button>
        <span class="spacer" />
        <button :disabled="running" @click="dialog?.close()">Close</button>
        <button class="primary" :disabled="running || picked.size === 0 || !target.key" @click="start">Migrate</button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(640px, calc(100vw - 32px));
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

.sides {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

fieldset {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  border: 1px solid var(--border);
  border-radius: 6px;
}

.tables {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 240px;
  overflow: auto;
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
}

.all {
  font-weight: 600;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 2px;
}

.check {
  display: flex;
  align-items: center;
  gap: 6px;
}

.opts {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.note {
  margin: 0;
  font-size: 12px;
}

.log {
  margin: 0;
  max-height: 200px;
  overflow: auto;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--panel-2);
  font-size: 12px;
  white-space: pre-wrap;
}

.buttons {
  display: flex;
  gap: 8px;
}

.spacer {
  flex: 1;
}
</style>
