<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { CellValue, ResultColumn, SearchHit } from '@/api/types'
import { useSchemaService } from '@/composables/schema'
import { compareData, syncScript, type DataCompareResult } from '@/lib/datacompare'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import { useWorkspace } from '@/stores/workspace'

/** Compare the rows of two tables by key, possibly on different connections, and write a sync script. */
interface Side {
  connectionId: string
  search: string
  hits: SearchHit[]
  table: SearchHit | null
}

const MAX_ROWS = 200_000
const PAGE = 50_000
const SHOWN = 200

const connections = useConnections()
const workspace = useWorkspace()
const schema = useSchemaService()
const ui = useUi()

const dialog = ref<HTMLDialogElement | null>(null)
const left = reactive<Side>({ connectionId: '', search: '', hits: [], table: null })
const right = reactive<Side>({ connectionId: '', search: '', hits: [], table: null })
const keys = ref('')
const running = ref(false)
const result = ref<DataCompareResult | null>(null)
const kinds = ref<Record<string, string>>({})
const note = ref('')
const deleteExtra = ref(false)

const connected = computed(() => connections.items.filter((c) => c.connected))

function open() {
  result.value = null
  note.value = ''
  left.connectionId = connected.value[0]?.id ?? ''
  right.connectionId = connected.value[1]?.id ?? left.connectionId
  dialog.value?.showModal()
}

for (const side of [left, right]) {
  let timer: ReturnType<typeof setTimeout> | undefined
  watch(
    () => side.search,
    (text) => {
      clearTimeout(timer)
      timer = setTimeout(async () => {
        side.hits = text.trim() ? ((await ui.guard(() => api.search(side.connectionId, text, 20))) ?? []) : []
      }, 200)
    },
  )
  watch(
    () => side.connectionId,
    () => {
      side.table = null
      side.hits = []
    },
  )
}

async function pick(side: Side, hit: SearchHit) {
  side.table = hit
  side.hits = []
  side.search = ''
  if (side === left) {
    const details = await ui.guard(() => api.tableDetails(left.connectionId, { catalog: hit.catalog, schema: hit.schema }, hit.name))
    if (details) keys.value = details.rowKey.join(', ')
    if (!right.table && right.connectionId) {
      const match = (await ui.guard(() => api.search(right.connectionId, hit.name, 20)))?.find((h) => h.name.toLowerCase() === hit.name.toLowerCase())
      if (match) right.table = match
    }
  }
}

async function readAll(side: Side): Promise<{ columns: ResultColumn[]; rows: CellValue[][] }> {
  const t = side.table!
  const keyList = keys.value.split(',').map((k) => k.trim()).filter(Boolean)
  const rows: CellValue[][] = []
  let columns: ResultColumn[] = []
  for (let offset = 0; offset < MAX_ROWS; offset += PAGE) {
    const page = await api.tableData(side.connectionId, { catalog: t.catalog, schema: t.schema }, t.name, {
      limit: PAGE,
      offset,
      orderBy: keyList[0] ?? null,
    })
    columns = page.columns
    rows.push(...page.rows)
    if (!page.hasMore) return { columns, rows }
  }
  note.value = `Only the first ${MAX_ROWS.toLocaleString()} rows of each table were compared.`
  return { columns, rows }
}

async function run() {
  if (!left.table || !right.table) return
  running.value = true
  result.value = null
  note.value = ''
  try {
    const [l, r] = await Promise.all([readAll(left), readAll(right)])
    kinds.value = Object.fromEntries(l.columns.map((c) => [c.name, c.kind]))
    result.value = compareData(l, r, keys.value.split(',').map((k) => k.trim()).filter(Boolean))
  } catch (e) {
    ui.error(e instanceof Error ? e.message : String(e))
  } finally {
    running.value = false
  }
}

const fmt = (v: CellValue) => (v === null ? 'NULL' : String(v))

async function openSync() {
  const r = result.value
  const t = right.table
  if (!r || !t) return
  const ctx = await ui.guard(() => schema.context(right.connectionId))
  if (!ctx) return
  const statements = syncScript(ctx, { catalog: t.catalog, schema: t.schema, name: t.name }, r, kinds.value, deleteExtra.value)
  if (statements.length === 0) {
    ui.info('Nothing to sync: the tables hold the same rows')
    return
  }
  workspace.openSql(right.connectionId, connections.labelOf(right.connectionId), `-- Make ${t.name} equal to ${left.table?.name}\n${statements.join(';\n')};\n`)
  dialog.value?.close()
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="dc-title">
    <div class="form">
      <h2 id="dc-title">Compare data</h2>
      <div class="sides">
        <fieldset v-for="(side, i) in [left, right]" :key="i" :disabled="running">
          <legend>{{ i === 0 ? 'Left (source of truth)' : 'Right (to compare / sync)' }}</legend>
          <select v-model="side.connectionId" :aria-label="`${i === 0 ? 'Left' : 'Right'} connection`">
            <option v-for="c in connected" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <input v-model="side.search" :placeholder="side.table ? `${side.table.container}.${side.table.name}` : 'Search table…'" :aria-label="`${i === 0 ? 'Left' : 'Right'} table`" />
          <ul v-if="side.hits.length" class="hits">
            <li v-for="h in side.hits" :key="`${h.catalog}.${h.schema}.${h.name}`">
              <button class="ghost" @click="pick(side, h)"><span class="mono">{{ h.name }}</span> <span class="muted">{{ h.container }}</span></button>
            </li>
          </ul>
          <p v-if="side.table" class="muted note">Table: <strong class="mono">{{ side.table.container }}.{{ side.table.name }}</strong></p>
        </fieldset>
      </div>
      <label class="field">
        <span>Key columns (identify a row on both sides)</span>
        <input v-model="keys" class="mono" placeholder="id" :disabled="running" />
      </label>
      <p v-if="note" class="muted note">{{ note }}</p>
      <div v-if="result" class="report">
        <div class="counts">
          <span><strong>{{ result.same.toLocaleString() }}</strong> identical</span>
          <span class="diff"><strong>{{ result.changed.length.toLocaleString() }}</strong> different</span>
          <span class="add"><strong>{{ result.onlyLeft.length.toLocaleString() }}</strong> only left</span>
          <span class="del"><strong>{{ result.onlyRight.length.toLocaleString() }}</strong> only right</span>
        </div>
        <p class="muted note">Compared columns: {{ result.columns.join(', ') }}</p>
        <pre class="mono details">{{
          [
            ...result.changed.slice(0, SHOWN).map((d) => `~ ${d.key.map(fmt).join(', ')}: ` + d.diffs.map((x) => `${x.column} ${fmt(x.right)} → ${fmt(x.left)}`).join('; ')),
            ...result.onlyLeft.slice(0, SHOWN).map((r) => `+ ${r.map(fmt).join(', ')}`),
            ...result.onlyRight.slice(0, SHOWN).map((r) => `- ${r.map(fmt).join(', ')}`),
          ].join('\n') || 'The tables hold the same rows.'
        }}</pre>
        <label class="check"><input v-model="deleteExtra" type="checkbox" /> Sync script deletes rows only on the right</label>
      </div>
      <div class="buttons">
        <button v-if="result" @click="openSync">Open sync script…</button>
        <span class="spacer" />
        <button :disabled="running" @click="dialog?.close()">Close</button>
        <button class="primary" :disabled="running || !left.table || !right.table || !keys.trim()" @click="run">
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
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 6px;
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

.hits {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 160px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 6px;
}

.hits button {
  width: 100%;
  justify-content: flex-start;
  border-radius: 0;
}

.note {
  margin: 0;
  font-size: 12px;
}

.counts {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}

.diff {
  color: var(--warn);
}

.add {
  color: var(--ok);
}

.del {
  color: var(--danger);
}

.report {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.details {
  margin: 0;
  max-height: 260px;
  overflow: auto;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--panel-2);
  font-size: 12px;
  white-space: pre;
}

.check {
  display: flex;
  align-items: center;
  gap: 6px;
}

.buttons {
  display: flex;
  gap: 8px;
}

.spacer {
  flex: 1;
}
</style>
