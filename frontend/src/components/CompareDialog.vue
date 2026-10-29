<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { Container } from '@/api/types'
import { compareSchemas, reportText, type ColumnTypes, type CompareReport } from '@/lib/compare'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'

/** Schema compare between two containers, possibly on different connections or databases. */
interface Side {
  connectionId: string
  containers: Container[]
  label: string
}

const TYPE_TABLE_LIMIT = 150

const connections = useConnections()
const ui = useUi()
const dialog = ref<HTMLDialogElement | null>(null)
const left = reactive<Side>({ connectionId: '', containers: [], label: '' })
const right = reactive<Side>({ connectionId: '', containers: [], label: '' })
const ignoreCase = ref(true)
const withTypes = ref(true)
const running = ref(false)
const report = ref<CompareReport | null>(null)
const note = ref('')

const connected = computed(() => connections.items.filter((c) => c.connected))
const name = (side: Side) => `${connections.labelOf(side.connectionId)}.${side.label}`
const text = computed(() => (report.value ? reportText(report.value, name(left), name(right)) : ''))

function open() {
  report.value = null
  note.value = ''
  left.connectionId = connected.value[0]?.id ?? ''
  right.connectionId = connected.value[1]?.id ?? connected.value[0]?.id ?? ''
  dialog.value?.showModal()
}

async function loadContainers(side: Side) {
  side.containers = side.connectionId ? ((await ui.guard(() => api.containers(side.connectionId))) ?? []) : []
  side.label = side.containers[0]?.label ?? ''
}

watch(() => left.connectionId, () => void loadContainers(left))
watch(() => right.connectionId, () => void loadContainers(right))

async function types(side: Side, tables: string[]): Promise<ColumnTypes> {
  const container = side.containers.find((c) => c.label === side.label)
  const out: ColumnTypes = {}
  for (const table of tables) {
    const details = await api.tableDetails(side.connectionId, { catalog: container?.catalog, schema: container?.schema }, table)
    out[table] = Object.fromEntries(details.columns.map((c) => [c.name, c.typeName]))
  }
  return out
}

async function run() {
  running.value = true
  report.value = null
  note.value = ''
  try {
    const [l, r] = await Promise.all([api.completion(left.connectionId), api.completion(right.connectionId)])
    const leftTables = l.schemas[left.label] ?? {}
    const rightTables = r.schemas[right.label] ?? {}
    let leftTypes: ColumnTypes = {}
    let rightTypes: ColumnTypes = {}
    if (withTypes.value) {
      const first = compareSchemas(leftTables, rightTables, ignoreCase.value)
      const rightLower = new Map(Object.keys(rightTables).map((t) => [ignoreCase.value ? t.toLowerCase() : t, t]))
      const common = Object.keys(leftTables)
        .filter((t) => !first.onlyLeft.includes(t))
        .slice(0, TYPE_TABLE_LIMIT)
      const commonRight = common.map((t) => rightLower.get(ignoreCase.value ? t.toLowerCase() : t)!).filter(Boolean)
      ;[leftTypes, rightTypes] = await Promise.all([types(left, common), types(right, commonRight)])
      if (Object.keys(leftTables).length > TYPE_TABLE_LIMIT) note.value = `Types compared for the first ${TYPE_TABLE_LIMIT} common tables.`
    }
    report.value = compareSchemas(leftTables, rightTables, ignoreCase.value, leftTypes, rightTypes)
    if (l.truncated || r.truncated) note.value += ' Large schema: the table list was truncated at 3000 tables.'
  } catch (e) {
    ui.error(e instanceof Error ? e.message : String(e))
  } finally {
    running.value = false
  }
}

async function copy() {
  try {
    await navigator.clipboard.writeText(text.value)
    ui.info('Report copied')
  } catch {
    ui.error('Clipboard is not available in this context')
  }
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="compare-title">
    <div class="form">
      <h2 id="compare-title">Compare schemas</h2>
      <div class="sides">
        <fieldset v-for="(side, i) in [left, right]" :key="i">
          <legend>{{ i === 0 ? 'Left (source)' : 'Right (target)' }}</legend>
          <select v-model="side.connectionId" :aria-label="`${i === 0 ? 'Left' : 'Right'} connection`">
            <option v-for="c in connected" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <select v-model="side.label" :aria-label="`${i === 0 ? 'Left' : 'Right'} schema`">
            <option v-for="c in side.containers" :key="c.label" :value="c.label">{{ c.label }}</option>
          </select>
        </fieldset>
      </div>
      <div class="opts">
        <label class="check"><input v-model="ignoreCase" type="checkbox" /> Ignore name case</label>
        <label class="check"><input v-model="withTypes" type="checkbox" /> Compare column types</label>
      </div>
      <p v-if="note" class="muted note">{{ note }}</p>
      <pre v-if="report" class="report mono">{{ text }}</pre>
      <div class="buttons">
        <button :disabled="!report" @click="copy">Copy report</button>
        <span class="spacer" />
        <button @click="dialog?.close()">Close</button>
        <button class="primary" :disabled="running || !left.label || !right.label" @click="run">
          {{ running ? 'Comparing…' : 'Compare' }}
        </button>
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

legend {
  font-size: 12px;
  color: var(--muted);
}

.opts {
  display: flex;
  gap: 16px;
}

.check {
  display: flex;
  align-items: center;
  gap: 6px;
}

.note {
  margin: 0;
  font-size: 12px;
}

.report {
  margin: 0;
  max-height: 45vh;
  overflow: auto;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--panel-2);
  font-size: 12px;
}

.buttons {
  display: flex;
  gap: 8px;
}

.spacer {
  flex: 1;
}
</style>
