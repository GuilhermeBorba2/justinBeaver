<script setup lang="ts">
import { MySQL, PostgreSQL, SQLite, StandardSQL, sql, type SQLDialect } from '@codemirror/lang-sql'
import { snippet, snippetCompletion, type CompletionContext } from '@codemirror/autocomplete'
import { lintGutter, setDiagnostics, type Diagnostic } from '@codemirror/lint'
import { Compartment, EditorState, Prec } from '@codemirror/state'
import { oneDark } from '@codemirror/theme-one-dark'
import { EditorView, keymap } from '@codemirror/view'
import { redo, selectAll as selectAllText, toggleComment as toggleLineComment, undo } from '@codemirror/commands'
import { gotoLine, openSearchPanel } from '@codemirror/search'
import { basicSetup } from 'codemirror'
import type { SqlLanguage } from 'sql-formatter'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { CellValue, Completion, QueryResult, ResultColumn, TableDetails } from '@/api/types'
import { addHistory, clearHistory, readHistory, type HistoryEntry } from '@/lib/history'
import { addRow, buildChanges, changeCount, displayRows, emptyEdits, isChanged, setCell, toggleDelete, type PendingEdits } from '@/lib/edits'
import { writingStatements } from '@/lib/guard'
import { findParams, substituteParams } from '@/lib/params'
import { parsePlan } from '@/lib/plan'
import { readSnippets, snippetBefore } from '@/lib/snippets'
import { errorRange, resultTitle, splitStatements, statementAt } from '@/lib/sql'
import { usePrefs } from '@/stores/prefs'
import { useQueryLog } from '@/stores/querylog'
import { calcOpen } from '@/composables/calc'
import { useSchemaService } from '@/composables/schema'
import { groupKeys, linkFilter } from '@/lib/filters'
import { upperKeywords } from '@/lib/keywordCase'
import { useValueViewer } from '@/composables/viewer'
import { editors, type EditorCommands } from '@/composables/editorBus'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import { useWorkspace, type SqlTab } from '@/stores/workspace'
import AppIcon from './AppIcon.vue'
import DriveMenu from './DriveMenu.vue'
import ExportMenu from './ExportMenu.vue'
import EditPreviewDialog from './EditPreviewDialog.vue'
import ParamsDialog from './ParamsDialog.vue'
import PlanTree from './PlanTree.vue'
import ResultGrid from './ResultGrid.vue'

const props = defineProps<{ tab: SqlTab; active: boolean }>()

interface ResultEntry {
  title: string
  sql: string
  result: QueryResult | null
  error: string | null
  /** Pinned tabs survive the next run (DBeaver's "Pin tab"). */
  pinned?: boolean
  /** PostgreSQL JSON plan of an Explain, shown as a tree */
  plan?: ReturnType<typeof parsePlan>
}

const connections = useConnections()
const workspace = useWorkspace()
const ui = useUi()
const queryLog = useQueryLog()
const prefs = usePrefs()

const host = ref<HTMLElement | null>(null)
let view: EditorView | undefined
const language = new Compartment()
const wrapping = new Compartment()
const themeSlot = new Compartment()
const fontSlot = new Compartment()

const maxRows = ref(prefs.state.defaultMaxRows)
const running = ref(false)
const results = ref<ResultEntry[]>([])
const activeResult = ref(0)
const resultsRatio = ref(0.45)
const wrap = ref(false)
const showHistory = ref(false)
const showOutput = ref(false)
const paramsDialog = ref<InstanceType<typeof ParamsDialog> | null>(null)
const history = ref<HistoryEntry[]>(readHistory(props.tab.connectionId))
const completionNote = ref('')
const selectedCell = ref<{ row: number; col: number } | null>(null)

// ---- editing query results in place ------------------------------------------
const edits = ref<PendingEdits>(emptyEdits())
const savingEdits = ref(false)
const editPreview = ref<InstanceType<typeof EditPreviewDialog> | null>(null)
/** The table behind the shown result, when the grid may edit it. */
const editSource = computed(() => (showHistory.value || showOutput.value ? null : (current.value?.result?.source ?? null)))
/** Result columns renamed to the table columns they show (read-only ones get a name no table has). */
const baseColumns = computed<ResultColumn[]>(() => {
  const r = current.value?.result
  if (!r) return []
  return r.columns.map((c, i) => ({ ...c, name: editSource.value?.columns[i] ?? `\u0000ro${i}` }))
})
const readOnlyColumns = computed(() => new Set((editSource.value?.columns ?? []).flatMap((c, i) => (c === null ? [i] : []))))
const pendingEdits = computed(() => changeCount(edits.value))
const gridRows = computed(() => {
  const r = current.value?.result
  if (!r) return []
  return editSource.value ? displayRows(edits.value, r.rows, baseColumns.value) : r.rows
})

function cellChanged(row: number, col: number): boolean {
  const r = current.value?.result
  return r ? isChanged(edits.value, r.rows.length, baseColumns.value, row, col) : false
}

function onEditCell(row: number, col: number, value: CellValue) {
  const r = current.value?.result
  if (r) setCell(edits.value, r.rows, baseColumns.value, row, col, value)
}

function onAddResultRow() {
  addRow(edits.value)
}

function onDeleteResultRow() {
  const r = current.value?.result
  if (r && selectedCell.value) toggleDelete(edits.value, r.rows.length, selectedCell.value.row)
}

function discardEdits() {
  edits.value = emptyEdits()
}

/** Save grid edits to the source table (preview first), then re-run the query to show the result. */
async function saveEdits() {
  const entry = current.value
  const r = entry?.result
  const source = editSource.value
  if (!entry || !r || !source || pendingEdits.value === 0) return
  const changes = buildChanges(edits.value, r.rows, baseColumns.value, source.primaryKey)
  if (changes.length === 0) return
  const scope = { catalog: source.catalog, schema: source.schema }
  if (connections.isProduction(props.tab.connectionId) &&
    !window.confirm(`PRODUCTION connection: save ${changes.length} row change(s) to ${source.table}?`)) return
  savingEdits.value = true
  try {
    const plan = await ui.guard(() => api.previewEdits(props.tab.connectionId, scope, source.table, changes))
    if (!plan || !(await editPreview.value?.confirm(plan.statements, autoCommit.value === false))) return
    const saved = await ui.guard(() => api.applyEdits(props.tab.connectionId, scope, source.table, changes))
    if (!saved) return
    let committed = saved.committed
    if (!committed && editPreview.value?.commitRequested()) {
      committed = (await ui.guard(async () => {
        await connections.endTransaction(props.tab.connectionId, 'commit')
        return true
      })) === true
    }
    if (!committed) connections.markDirty(props.tab.connectionId)
    ui.info(`${saved.rowsAffected} row(s) changed in ${source.table}${committed ? ', committed' : ' — NOT committed yet: Commit to save'}`)
    edits.value = emptyEdits()
    connections.dataChanged(props.tab.connectionId)
    const refreshed = await ui.guard(() => api.runQuery(props.tab.connectionId, entry.sql, maxRows.value))
    if (refreshed) entry.result = refreshed
  } finally {
    savingEdits.value = false
  }
}
const viewValue = useValueViewer()
const schemaService = useSchemaService()


const connection = computed(() => connections.connectionOf(props.tab.connectionId))
const connected = computed(() => connections.isConnected(props.tab.connectionId))
const current = computed(() => results.value[activeResult.value])
const autoCommit = computed(() => connections.autoCommit[props.tab.connectionId] ?? null)
const dirty = computed(() => connections.txDirty[props.tab.connectionId] === true)
const dialectName = computed(
  () => connections.drivers.find((d) => d.id === connection.value?.driverId)?.dialect?.toLowerCase() ?? '',
)

// ---- foreign keys of the table behind the result (links to the referenced rows) ----------
const sourceDetails = ref<TableDetails | null>(null)
const sourceKey = computed(() => {
  const s = editSource.value
  return s ? [props.tab.connectionId, s.catalog ?? '', s.schema ?? '', s.table].join('\u0000') : ''
})
watch(sourceKey, async (key) => {
  sourceDetails.value = null
  const s = editSource.value
  if (!key || !s) return
  try {
    const details = await api.tableDetails(props.tab.connectionId, { catalog: s.catalog, schema: s.schema }, s.table)
    if (sourceKey.value === key) sourceDetails.value = details
  } catch {
    // no key metadata: the grid simply has no links
  }
})
const outgoingKeys = computed(() => groupKeys(sourceDetails.value?.foreignKeys ?? []))
const linkColumns = computed(() => {
  const keyed = new Set(outgoingKeys.value.flatMap((k) => k.pairs.map((p) => p.column)))
  return new Set(baseColumns.value.flatMap((c, i) => (keyed.has(c.name) ? [i] : [])))
})

/** Open the table a key cell references, filtered to that row. */
async function followKey(row: number, col: number) {
  const s = editSource.value
  const name = baseColumns.value[col]?.name
  const link = outgoingKeys.value.find((k) => k.pairs.some((p) => p.column === name))
  if (!s || !link) return
  const ctx = await ui.guard(() => schemaService.context(props.tab.connectionId))
  if (!ctx) return
  const filter = linkFilter(ctx, link, baseColumns.value, gridRows.value[row] ?? [])
  if (filter === undefined) {
    ui.info(`The row has no value for ${link.pairs.map((p) => p.column).join(', ')}: it references nothing`)
    return
  }
  const sameContainer = (link.schema ?? null) === s.schema && (!link.catalog || !s.catalog || link.catalog === s.catalog)
  workspace.openTable(props.tab.connectionId, sameContainer ? s.catalog : link.catalog, sameContainer ? s.schema : link.schema, link.table, filter)
}

/** Server output of everything the last run produced. */
const output = computed(() =>
  results.value.flatMap((entry, i) => (entry.result?.messages ?? []).map((m) => (results.value.length > 1 ? `[${i + 1}] ${m}` : m))),
)

/** Production connections ask before anything that is not a query. */
function confirmProduction(statements: string[]): boolean {
  if (!connections.isProduction(props.tab.connectionId) || !prefs.state.confirmProductionWrites) return true
  const writes = writingStatements(statements)
  if (writes.length === 0) return true
  const preview = writes.slice(0, 5).map((s) => `• ${s.length > 120 ? s.slice(0, 120) + '…' : s}`).join('\n')
  return window.confirm(
    `PRODUCTION connection "${connection.value?.name}": run ${writes.length} statement(s) that may change data?\n\n${preview}`,
  )
}

/** Fill SQL variables (`:name`, `${name}`) from a dialog; undefined when cancelled. */
async function resolveParams(sql: string): Promise<string | undefined> {
  const names = findParams(sql)
  if (names.length === 0) return sql
  const values = await paramsDialog.value?.ask(names)
  return values ? substituteParams(sql, values) : undefined
}

const summary = computed(() => {
  const r = current.value?.result
  if (!r) return ''
  if (r.columns.length === 0) return `${r.updateCount ?? 0} row(s) affected · ${r.elapsedMs} ms`
  const cut = maxRows.value === 0 ? 'the 1,000,000-row safety limit' : `${maxRows.value.toLocaleString()}; raise "Max rows" for more`
  const rows = `${r.rows.length.toLocaleString()} row(s)${r.truncated ? ` (truncated at ${cut})` : ''}`
  return `${rows} · ${r.elapsedMs} ms`
})

function codemirrorDialect(): SQLDialect {
  switch (dialectName.value) {
    case 'postgresql':
      return PostgreSQL
    case 'mysql':
    case 'mariadb':
      return MySQL
    case 'sqlite':
      return SQLite
    default:
      return StandardSQL
  }
}

function formatterLanguage(): SqlLanguage {
  switch (dialectName.value) {
    case 'postgresql':
      return 'postgresql'
    case 'mysql':
      return 'mysql'
    case 'mariadb':
      return 'mariadb'
    case 'sqlite':
      return 'sqlite'
    default:
      return 'sql'
  }
}

/** Autocomplete tables/columns from the database metadata. */
async function loadCompletion() {
  if (!view || !connected.value) return
  let names: Completion
  try {
    names = await api.completion(props.tab.connectionId)
  } catch {
    completionNote.value = 'Autocomplete: keywords only'
    return
  }
  const tables = Object.values(names.schemas).reduce((n, t) => n + Object.keys(t).length, 0)
  completionNote.value = `Autocomplete: ${tables} table(s)${names.truncated ? ' (truncated)' : ''}`
  view.dispatch({
    effects: language.reconfigure(
      sql({
        dialect: codemirrorDialect(),
        schema: names.schemas,
        defaultSchema: names.defaultSchema ?? undefined,
      }),
    ),
  })
}

function selectedOrAll(): string {
  if (!view) return ''
  const { from, to, empty } = view.state.selection.main
  return empty ? view.state.doc.toString() : view.state.sliceDoc(from, to)
}

/** The selection, or the statement under the cursor. */
function statementText(): string | undefined {
  if (!view) return undefined
  const { from, to, empty, head } = view.state.selection.main
  return empty ? statementAt(view.state.doc.toString(), head)?.sql : view.state.sliceDoc(from, to)
}

function remember(sql: string, ok: boolean, result: QueryResult | null, error: string | null) {
  const summaryText = error
    ? error.slice(0, 160)
    : result && result.columns.length
      ? `${result.rows.length}${result.truncated ? '+' : ''} row(s)`
      : `${result?.updateCount ?? 0} row(s) affected`
  queryLog.add({ connectionId: props.tab.connectionId, sql, ok, elapsedMs: result?.elapsedMs ?? null, summary: summaryText })
  history.value = addHistory(props.tab.connectionId, {
    sql,
    at: Date.now(),
    ok,
    elapsedMs: result?.elapsedMs ?? null,
    summary: summaryText,
  })
}

/** Export "All rows": the shown statement again without the row limit (the server's safety cap still applies). */
async function fetchAllRows() {
  const entry = current.value
  if (!entry) throw new Error('No result')
  const result = await api.runQuery(props.tab.connectionId, entry.sql, 0)
  return { columns: result.columns, rows: result.rows }
}

/** Query results have no row address, so the viewer shows the value as fetched (LOBs may be previews). */
function onViewValue() {
  const r = current.value?.result
  const cell = selectedCell.value
  if (!r || !cell) return
  const column = r.columns[cell.col]
  if (!column) return
  viewValue({ column: column.name, typeName: column.typeName, value: r.rows[cell.row]?.[cell.col] ?? null })
}

/** "orders", "orders (2)"…: a title unique among the tabs kept. */
function uniqueTitle(title: string, taken: Set<string>): string {
  let name = title
  for (let n = 2; taken.has(name); n++) name = `${title} (${n})`
  taken.add(name)
  return name
}

/**
 * Show the results of a run. They replace the unpinned tabs, or with `append` ("Execute in new tab", Ctrl+\)
 * are added after every open tab.
 */
function show(entries: ResultEntry[], focus = 0, append = false) {
  selectedCell.value = null
  edits.value = emptyEdits()
  const kept = append ? results.value : results.value.filter((e) => e.pinned)
  const taken = new Set(kept.map((e) => e.title))
  results.value = [...kept, ...entries.map((e) => ({ ...e, title: uniqueTitle(e.title, taken) }))]
  activeResult.value = kept.length + focus
  showHistory.value = false
  showOutput.value = entries.some((e) => (e.result?.messages.length ?? 0) > 0) && entries.every((e) => !e.result?.columns.length)
}

async function track(work: () => Promise<void>) {
  running.value = true
  try {
    await work()
  } finally {
    running.value = false
  }
}

const BEGIN = /^\s*(?:--[^\n]*\n\s*|\/\*[\s\S]*?\*\/\s*)*(begin|start\s+transaction)\b/i
const END = /^\s*(commit|rollback|end)\b/i

/** BEGIN typed by hand opens a server transaction the Auto-commit switch does not know about. */
function warnOpenTransaction(statements: string[]) {
  const lastBegin = statements.map((s) => BEGIN.test(s)).lastIndexOf(true)
  if (lastBegin < 0 || statements.slice(lastBegin + 1).some((s) => END.test(s))) return
  ui.error('BEGIN opened a transaction: your changes stay invisible to other sessions until you run COMMIT. ' +
    'Prefer the Auto-commit switch with Commit / Rollback.')
}

const DDL = /^\s*(?:--[^\n]*\n\s*|\/\*[\s\S]*?\*\/\s*)*(create|alter|drop|rename|truncate)\b/i

const CONTEXT_CHANGE = /^\s*(?:--[^\n]*\n\s*|\/\*[\s\S]*?\*\/\s*)*(set\s+(search_path|schema)\b|use\s)/i

function noteSchemaChanges(statements: string[]) {
  if (statements.some((s) => CONTEXT_CHANGE.test(s))) void ui.guard(() => connections.refreshContext(props.tab.connectionId))
  if (statements.some((s) => DDL.test(s))) connections.schemaChanged(props.tab.connectionId)
}

// ---- errors underlined in the editor -----------------------------------------------
function markErrors(failures: { sql: string; error: string }[]) {
  if (!view) return
  const doc = view.state.doc.toString()
  const diagnostics: Diagnostic[] = []
  let from = 0
  for (const f of failures) {
    const range = errorRange(doc, f.sql, f.error, from)
    if (!range) continue
    from = range.to
    diagnostics.push({ from: range.from, to: Math.min(doc.length, range.to), severity: 'error', message: f.error })
  }
  view.dispatch(setDiagnostics(view.state, diagnostics))
}

// ---- outline: the statements of the script ------------------------------------------
const showOutline = ref(false)
const outline = computed(() =>
  showOutline.value
    ? splitStatements(props.tab.sql).map((s) => ({
        from: s.from,
        text: s.sql.replace(/--[^\n]*\n?|\/\*[\s\S]*?\*\//g, ' ').replace(/\s+/g, ' ').trim().slice(0, 90) || '(comment)',
        line: props.tab.sql.slice(0, s.from).split('\n').length,
      }))
    : [],
)

function goTo(pos: number) {
  if (!view) return
  view.dispatch({ selection: { anchor: pos }, scrollIntoView: true })
  view.focus()
}

// ---- SQL templates (sf + Tab → select * from …) ------------------------------------------
const snippets = readSnippets()

function snippetSource(context: CompletionContext) {
  const word = context.matchBefore(/\w+/)
  if (!word || (word.from === word.to && !context.explicit)) return null
  return {
    from: word.from,
    options: snippets.map((s) => snippetCompletion(s.template, { label: s.name, detail: s.description, type: 'text', boost: -1 })),
  }
}

function expandSnippet(target: EditorView): boolean {
  const { head, empty } = target.state.selection.main
  if (!empty) return false
  const line = target.state.doc.lineAt(head)
  const found = snippetBefore(line.text.slice(0, head - line.from), snippets)
  if (!found) return false
  snippet(found.template)(target, null, head - found.name.length, head)
  return true
}

function closeResult(index: number) {
  if (index === activeResult.value && pendingEdits.value > 0 && !window.confirm(`Discard ${pendingEdits.value} unsaved change(s)?`)) return
  results.value.splice(index, 1)
  if (activeResult.value > index || activeResult.value >= results.value.length) {
    activeResult.value = Math.max(0, activeResult.value - 1)
  }
}

function togglePin(index: number) {
  const entry = results.value[index]
  if (entry) entry.pinned = !entry.pinned
}

/** Ctrl+Enter: "Execute SQL query"; with `newTab` (Ctrl+\), "Execute SQL query in new tab". */
async function runStatement(newTab = false) {
  if (running.value || !connected.value) return
  const raw = statementText()
  if (!raw?.trim()) {
    ui.info('Nothing to run at the cursor')
    return
  }
  const text = await resolveParams(raw)
  if (text === undefined || !confirmProduction([text])) return
  await track(async () => {
    try {
      const result = await api.runQuery(props.tab.connectionId, text, maxRows.value)
      show([{ title: resultTitle(text), sql: text, result, error: null }], 0, newTab)
      remember(text, true, result, null)
      markErrors([])
      if (result.columns.length === 0) {
        connections.markDirty(props.tab.connectionId)
        connections.dataChanged(props.tab.connectionId)
      }
      noteSchemaChanges([text])
      warnOpenTransaction([text])
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      show([{ title: 'Error', sql: text, result: null, error: message }], 0, newTab)
      remember(text, false, null, message)
      markErrors([{ sql: text, error: message }])
    }
  })
}

/** Alt+X: "Execute SQL script"; with `fromCursor`, "Execute SQL script from the position". */
async function runScript(fromCursor = false) {
  if (!view || running.value || !connected.value) return
  let text = selectedOrAll()
  if (fromCursor && view.state.selection.main.empty) {
    const doc = view.state.doc.toString()
    const head = view.state.selection.main.head
    const start = splitStatements(doc).find((s) => s.to >= head)?.from
    text = start === undefined ? '' : doc.slice(start)
  }
  if (!text.trim()) return
  const resolved = await resolveParams(text)
  if (resolved === undefined || !confirmProduction(splitStatements(resolved).map((s) => s.sql))) return
  text = resolved
  await track(async () => {
    try {
      const statements = await api.runScript(props.tab.connectionId, text, maxRows.value)
      show(
        statements.map((s) => ({ title: s.error ? 'Error' : resultTitle(s.sql), sql: s.sql, result: s.result, error: s.error })),
        Math.max(0, statements.findIndex((s) => s.error !== null)),
      )
      for (const s of statements) remember(s.sql, s.error === null, s.result, s.error)
      markErrors(statements.flatMap((s) => (s.error === null ? [] : [{ sql: s.sql, error: s.error }])))
      connections.markDirty(props.tab.connectionId, statements.filter((s) => s.error === null && !s.result?.columns.length).length)
      connections.dataChanged(props.tab.connectionId)
      noteSchemaChanges(statements.filter((s) => s.error === null).map((s) => s.sql))
      warnOpenTransaction(statements.map((s) => s.sql))
    } catch (e) {
      show([{ title: 'Error', sql: text, result: null, error: e instanceof Error ? e.message : String(e) }])
    }
  })
}

/** PostgreSQL: the plan as JSON for the tree view; undefined elsewhere or when it cannot be read. */
async function jsonPlan(sql: string, analyze: boolean): Promise<ReturnType<typeof parsePlan> | undefined> {
  if (dialectName.value !== 'postgresql') return undefined
  try {
    const r = await api.runQuery(props.tab.connectionId, `EXPLAIN (FORMAT JSON${analyze ? ', ANALYZE, BUFFERS' : ''}) ${sql}`, 10)
    return parsePlan(r.rows[0]?.[0])
  } catch {
    return undefined
  }
}

const planAsText = ref(false)

/** "Explain analyze": runs the query to measure it, so only offered for reads. */
async function explainAnalyze() {
  const entry = current.value
  if (!entry?.plan || running.value) return
  if (!/^\s*(select|with|table|values)\b/i.test(entry.sql)) {
    ui.error('Explain analyze runs the statement: only offered for queries')
    return
  }
  await track(async () => {
    const plan = await jsonPlan(entry.sql, true)
    if (plan) entry.plan = plan
    else ui.error('Explain analyze failed')
  })
}

/** Ctrl+Shift+E: "Explain Execution Plan". */
async function explain() {
  if (running.value || !connected.value) return
  const raw = statementText()
  if (!raw?.trim()) return
  const text = await resolveParams(raw)
  if (text === undefined) return
  await track(async () => {
    try {
      const result = await api.explain(props.tab.connectionId, text, 1000)
      show([{ title: 'Plan', sql: text, result, error: null, plan: await jsonPlan(text, false) }])
    } catch (e) {
      show([{ title: 'Plan', sql: text, result: null, error: e instanceof Error ? e.message : String(e) }])
    }
  })
}

/**
 * Object source editors (a routine or trigger opened from the navigator): "Save" runs the whole definition,
 * previewed first, like DBeaver's object editor.
 */
async function saveObject() {
  const text = view?.state.doc.toString().trim()
  if (!text || !connected.value) return
  await schemaService.run(props.tab.connectionId, `Save ${props.tab.title}`, () => [text])
}

/** Ctrl+Shift+F: "Content Format" of the selection or whole script (formatter loaded on first use). */
async function formatScript() {
  if (!view) return
  const { format: formatSql } = await import('sql-formatter')
  const { from, to, empty } = view.state.selection.main
  const start = empty ? 0 : from
  const end = empty ? view.state.doc.length : to
  const text = view.state.sliceDoc(start, end)
  try {
    const formatted = formatSql(text, { language: formatterLanguage(), keywordCase: 'upper', tabWidth: 2 })
    view.dispatch({ changes: { from: start, to: end, insert: formatted } })
  } catch (e) {
    ui.error(`Cannot format: ${e instanceof Error ? e.message : String(e)}`)
  }
}

function toggleWrap() {
  wrap.value = !wrap.value
  view?.dispatch({ effects: wrapping.reconfigure(wrap.value ? EditorView.lineWrapping : []) })
}

function insertFromHistory(entry: HistoryEntry) {
  if (!view) return
  const { from, to } = view.state.selection.main
  const insert = entry.sql.endsWith(';') ? entry.sql : `${entry.sql};`
  view.dispatch({ changes: { from, to, insert }, selection: { anchor: from + insert.length } })
  view.focus()
}

function wipeHistory() {
  clearHistory(props.tab.connectionId)
  history.value = []
}

async function cancel() {
  await ui.guard(() => api.cancel(props.tab.connectionId))
}

async function refreshAutoCommit() {
  if (connected.value) await ui.guard(() => connections.refreshAutoCommit(props.tab.connectionId))
}

async function toggleAutoCommit() {
  await ui.guard(() => connections.setAutoCommit(props.tab.connectionId, !(autoCommit.value ?? true)))
}

async function endTransaction(kind: 'commit' | 'rollback') {
  const ok = await ui.guard(async () => {
    await connections.endTransaction(props.tab.connectionId, kind)
    return true
  })
  if (ok) ui.info(kind === 'commit' ? 'Committed' : 'Rolled back')
}

async function connect() {
  await ui.guard(() => connections.connect(connections.baseId(props.tab.connectionId)))
}

function startResize(event: PointerEvent) {
  const container = (event.currentTarget as HTMLElement).parentElement!
  const rect = container.getBoundingClientRect()
  const move = (e: PointerEvent) => {
    resultsRatio.value = Math.min(0.85, Math.max(0.15, 1 - (e.clientY - rect.top) / rect.height))
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

function timeOf(at: number): string {
  return new Date(at).toLocaleString()
}

onMounted(() => {
  view = new EditorView({
    parent: host.value!,
    doc: props.tab.sql,
    extensions: [
      basicSetup,
      language.of(sql({ dialect: codemirrorDialect() })),
      wrapping.of([]),
      lintGutter(),
      upperKeywords(() => prefs.state.uppercaseKeywords),
      EditorState.languageData.of(() => [{ autocomplete: snippetSource }]),
      themeSlot.of(prefs.dark ? oneDark : []),
      fontSlot.of(fontTheme()),
      Prec.highest(
        keymap.of([
          { key: 'Mod-Enter', run: () => (void runStatement(), true) },
          { key: 'Mod-\\', run: () => (void runStatement(true), true) },
          { key: 'Alt-x', run: () => (void runScript(), true) },
          { key: 'Shift-Mod-e', run: () => (void explain(), true) },
          { key: 'Shift-Mod-f', run: () => (void formatScript(), true) },
          { key: 'Tab', run: expandSnippet },
        ]),
      ),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) workspace.setSql(props.tab.id, update.state.doc.toString())
      }),
      EditorView.theme({
        '&': { height: '100%', backgroundColor: 'var(--panel)' },
        '.cm-scroller': { fontFamily: 'var(--font-mono)' },
        '.cm-gutters': { backgroundColor: 'var(--panel-2)', color: 'var(--muted)', border: 'none' },
      }),
    ],
  })
  void refreshAutoCommit()
  void loadCompletion()
  editors.register(props.tab.id, editorCommands)
})

onBeforeUnmount(() => {
  editors.unregister(props.tab.id)
  view?.destroy()
})

/** Replaces the selected text (or, with no selection, the whole script) by `change(text)`. */
function transform(change: (text: string) => string) {
  if (!view) return
  const { from, to, empty } = view.state.selection.main
  const start = empty ? 0 : from
  const end = empty ? view.state.doc.length : to
  view.dispatch({ changes: { from: start, to: end, insert: change(view.state.sliceDoc(start, end)) } })
}

function download(name: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** File ▸ Import SQL script: the chosen file's text goes in at the cursor. */
function importFromFile() {
  const picker = document.createElement('input')
  picker.type = 'file'
  picker.accept = '.sql,.txt,text/plain'
  picker.onchange = async () => {
    const file = picker.files?.[0]
    if (!file || !view) return
    const text = await file.text()
    const { from, to } = view.state.selection.main
    view.dispatch({ changes: { from, to, insert: text }, selection: { anchor: from + text.length } })
    view.focus()
  }
  picker.click()
}

async function copySelection(cut: boolean) {
  if (!view) return
  const text = view.state.sliceDoc(view.state.selection.main.from, view.state.selection.main.to)
  if (!text) return
  await navigator.clipboard.writeText(text)
  if (cut) view.dispatch(view.state.replaceSelection(''))
}

async function pasteClipboard() {
  if (!view) return
  const text = await navigator.clipboard.readText().catch(() => '')
  if (text) view.dispatch(view.state.replaceSelection(text))
  view.focus()
}

function showPanel(panel: 'results' | 'output' | 'history') {
  showOutput.value = panel === 'output'
  showHistory.value = panel === 'history'
}

const editorCommands: EditorCommands = {
  runStatement: (newTab) => void runStatement(newTab),
  runScript: (fromCursor) => void runScript(fromCursor),
  cancel: () => void cancel(),
  explain: () => void explain(),
  explainAnalyze: () => void explainAnalyze(),
  format: () => void formatScript(),
  undo: () => view && undo(view),
  redo: () => view && redo(view),
  cut: () => void copySelection(true),
  copy: () => void copySelection(false),
  paste: () => void pasteClipboard(),
  selectAll: () => view && selectAllText(view),
  find: () => view && (openSearchPanel(view), view.focus()),
  goToLine: () => view && (gotoLine(view), view.focus()),
  toggleComment: () => view && toggleLineComment(view),
  changeCase: (to) => transform((t) => (to === 'upper' ? t.toUpperCase() : t.toLowerCase())),
  trimSpaces: () => transform((t) => t.replace(/[ \t]+$/gm, '')),
  toggleWrap,
  isWrapping: () => wrap.value,
  showPanel,
  panel: () => (showOutput.value ? 'output' : showHistory.value ? 'history' : 'results'),
  importFile: importFromFile,
  exportFile: () => download(`${props.tab.scriptName?.replace(/\.sql$/i, '') ?? 'script'}.sql`, view?.state.doc.toString() ?? ''),
  focus: () => view?.focus(),
}

function fontTheme() {
  return EditorView.theme({ '&': { fontSize: `${prefs.state.editorFontSize}px` } })
}

watch(
  () => prefs.dark,
  (dark) => view?.dispatch({ effects: themeSlot.reconfigure(dark ? oneDark : []) }),
)
watch(
  () => prefs.state.editorFontSize,
  () => view?.dispatch({ effects: fontSlot.reconfigure(fontTheme()) }),
)

watch(connected, () => {
  void refreshAutoCommit()
  void loadCompletion()
})
watch(
  () => [connections.schemaEpoch[props.tab.connectionId], connections.contexts[props.tab.connectionId]],
  () => void loadCompletion(),
)
// the toolbar's active datasource selector moved this script to another connection or database
watch(
  () => props.tab.connectionId,
  (id) => {
    history.value = readHistory(id)
    results.value = []
    void refreshAutoCommit()
    void loadCompletion()
  },
)
// the text changed from outside (a script pulled by the cloud sync): show it
watch(
  () => props.tab.sql,
  (text) => {
    if (view && view.state.doc.toString() !== text) {
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } })
    }
  },
)
watch(activeResult, () => {
  edits.value = emptyEdits()
  selectedCell.value = null
})
watch(
  () => props.active,
  (active) => {
    if (active) {
      view?.requestMeasure()
      view?.focus()
    }
  },
)
</script>

<template>
  <section class="editor-tab">
    <div class="toolbar">
      <button class="primary" :disabled="running || !connected" title="Execute statement (Ctrl+Enter)" @click="runStatement()">
        <AppIcon name="play" /> Run
      </button>
      <button :disabled="running || !connected" title="Execute statement in a new result tab (Ctrl+\)" @click="runStatement(true)">
        <AppIcon name="plus" /> New tab
      </button>
      <button :disabled="running || !connected" title="Execute script (Alt+X)" @click="runScript()">
        <AppIcon name="script" /> Script
      </button>
      <button :disabled="running || !connected" title="Execute script from the cursor position" @click="runScript(true)">
        From cursor
      </button>
      <button :disabled="running || !connected" title="Explain execution plan (Ctrl+Shift+E)" @click="explain">Explain</button>
      <button
        v-if="tab.customTitle"
        class="primary"
        :disabled="running || !connected"
        title="Save the object: run its whole definition (CREATE OR REPLACE …), previewed first"
        @click="saveObject"
      >
        <AppIcon name="commit" /> Save object
      </button>
      <button :disabled="!running" title="Cancel running statement" @click="cancel"><AppIcon name="stop" /> Stop</button>
      <span class="sep" />
      <button title="Format SQL (Ctrl+Shift+F)" @click="formatScript">Format</button>
      <button :class="{ on: wrap }" :aria-pressed="wrap" title="Toggle word wrap" @click="toggleWrap">Wrap</button>
      <DriveMenu scope="scripts" />
      <button :class="{ on: showOutline }" :aria-pressed="showOutline" title="Outline: the statements of this script" @click="showOutline = !showOutline">
        Outline
      </button>
      <span class="sep" />
      <label class="check" :class="{ manual: autoCommit === false }" title="When off, changes stay pending until you commit or roll back">
        <input type="checkbox" :checked="autoCommit !== false" :disabled="!connected" @change="toggleAutoCommit" />
        {{ autoCommit === false ? 'Auto-commit OFF' : 'Auto-commit' }}
      </label>
      <button :disabled="!connected || autoCommit !== false" :class="{ pending: dirty }" @click="endTransaction('commit')">
        <AppIcon name="commit" /> Commit
      </button>
      <button :disabled="!connected || autoCommit !== false" @click="endTransaction('rollback')">
        <AppIcon name="rollback" /> Rollback
      </button>
      <span class="sep" />
      <label class="check">
        Max rows
        <select v-model.number="maxRows">
          <option :value="100">100</option>
          <option :value="200">200</option>
          <option :value="1000">1,000</option>
          <option :value="10000">10,000</option>
          <option :value="100000">100,000</option>
          <option :value="0">All rows</option>
        </select>
      </label>
      <span class="conn muted">
        <span v-if="connection?.connectionType === 'PROD'" class="prod-badge">PRODUCTION</span>
        <span v-if="connection?.readOnly" class="ro-badge">read-only</span>
        {{ connection ? connections.labelOf(tab.connectionId) : 'Unknown connection' }}
      </span>
    </div>

    <div v-if="!connected" class="banner">
      Not connected.
      <button class="primary" @click="connect"><AppIcon name="plug" /> Connect</button>
    </div>

    <div class="split">
      <div class="editor-row" :style="{ flex: `${1 - resultsRatio} 1 0` }">
        <div ref="host" class="cm-host" />
        <nav v-if="showOutline" class="outline" aria-label="Outline">
          <div class="outline-head">Outline <span class="muted">{{ outline.length }}</span></div>
          <button v-for="item in outline" :key="item.from" class="outline-item" :title="item.text" @click="goTo(item.from)">
            <span class="muted ln">{{ item.line }}</span><span class="mono">{{ item.text }}</span>
          </button>
          <p v-if="!outline.length" class="muted hint-small">No statements</p>
        </nav>
      </div>
      <div class="handle" role="separator" aria-orientation="horizontal" @pointerdown.prevent="startResize" />
      <div class="results" :style="{ flex: `${resultsRatio} 1 0` }">
        <div class="result-tabs" role="tablist">
          <div
            v-for="(entry, i) in results"
            :key="i"
            class="result-tab"
            role="tab"
            tabindex="0"
            :aria-selected="!showHistory && !showOutput && i === activeResult"
            :class="{ on: !showHistory && !showOutput && i === activeResult, bad: entry.error, pinned: entry.pinned }"
            :title="entry.sql"
            @click="(activeResult = i), (showHistory = false), (showOutput = false)"
            @keydown.enter="(activeResult = i), (showHistory = false), (showOutput = false)"
            @auxclick.middle.prevent="closeResult(i)"
          >
            <button
              class="ghost mini pin"
              :class="{ active: entry.pinned }"
              :title="entry.pinned ? 'Unpin: the next run replaces this tab' : 'Pin: keep this tab when running again'"
              :aria-label="entry.pinned ? `Unpin ${entry.title}` : `Pin ${entry.title}`"
              :aria-pressed="!!entry.pinned"
              @click.stop="togglePin(i)"
            >
              <AppIcon name="pin" :size="11" />
            </button>
            <span>{{ entry.title }}</span>
            <button class="ghost mini" :aria-label="`Close ${entry.title}`" title="Close" @click.stop="closeResult(i)">
              <AppIcon name="close" :size="10" />
            </button>
          </div>
          <span class="spacer" />
          <button
            v-if="output.length"
            class="ghost"
            role="tab"
            :aria-selected="showOutput"
            :class="{ on: showOutput }"
            @click="(showOutput = !showOutput), (showHistory = false)"
          >
            Output ({{ output.length }})
          </button>
          <button class="ghost" role="tab" :aria-selected="showHistory" :class="{ on: showHistory }" @click="(showHistory = !showHistory), (showOutput = false)">
            History ({{ history.length }})
          </button>
        </div>

        <pre v-if="showOutput" class="output mono">{{ output.join('\n') }}</pre>
        <div v-else-if="showHistory" class="history">
          <div v-if="history.length === 0" class="hint muted">No statements run on this connection yet</div>
          <button
            v-for="entry in history"
            :key="entry.at + entry.sql"
            class="entry"
            :title="'Insert into the editor'"
            @click="insertFromHistory(entry)"
          >
            <span class="dot" :class="{ bad: !entry.ok }" />
            <span class="when muted">{{ timeOf(entry.at) }}</span>
            <code class="mono text">{{ entry.sql }}</code>
            <span class="muted what">{{ entry.summary }}<template v-if="entry.elapsedMs !== null"> · {{ entry.elapsedMs }} ms</template></span>
          </button>
        </div>
        <div v-else-if="current?.error" class="error mono">{{ current.error }}</div>
        <div v-else-if="current?.plan && !planAsText" class="plan">
          <div class="plan-bar">
            <span class="muted">
              PostgreSQL plan<template v-if="current.plan.planningMs !== null"> · planning {{ current.plan.planningMs }} ms</template>
              <template v-if="current.plan.executionMs !== null"> · execution {{ current.plan.executionMs }} ms</template>
            </span>
            <span class="spacer" />
            <button class="ghost" :disabled="running" title="Run the query and show actual times and rows" @click="explainAnalyze">Explain analyze</button>
            <button class="ghost" @click="planAsText = true">Text</button>
          </div>
          <div class="plan-tree" role="tree"><PlanTree :node="current.plan.root" /></div>
        </div>
        <div v-else-if="current?.result && current.result.columns.length === 0" class="hint">
          <span class="ok">Statement executed · {{ current.result.updateCount ?? 0 }} row(s) affected</span>
        </div>
        <div v-else-if="current?.result" class="grid">
          <button v-if="current.plan" class="ghost tree-toggle" @click="planAsText = false">Tree view</button>
          <ResultGrid
            :columns="current.result.columns"
            :rows="gridRows"
            :editable="!!editSource"
            :read-only-columns="readOnlyColumns"
            :is-changed="cellChanged"
            :deleted-rows="edits.deletes"
            :inserted-from="current.result.rows.length"
            :calc-panel="calcOpen"
            :export-name="editSource?.table ?? 'table'"
            :links="linkColumns"
            @follow="followKey"
            @select="(row, col) => (selectedCell = { row, col })"
            @edit="onEditCell"
          />
        </div>
        <div v-else class="hint muted">
          Ctrl+Enter runs the statement under the cursor · Ctrl+\ runs it in a new tab · Alt+X runs the script · Ctrl+Shift+E explains · Ctrl+Shift+F
          formats · Ctrl+Space completes
        </div>

        <div class="statusbar">
          <span class="muted">{{ running ? 'Running…' : showHistory ? '' : summary }}</span>
          <span class="spacer" />
          <span v-if="dirty" class="uncommitted" title="Changes made in this transaction are not saved until you commit">
            Uncommitted changes
            <button class="ghost" @click="endTransaction('commit')">Commit</button>
            <button class="ghost" @click="endTransaction('rollback')">Rollback</button>
          </span>
          <template v-if="editSource">
            <span class="muted small" :title="`Rows come from ${editSource.table} with its primary key: double-click a cell to edit`">
              Editing {{ editSource.table }}
            </span>
            <button class="ghost" title="Add a row" @click="onAddResultRow"><AppIcon name="plus" /></button>
            <button class="ghost" title="Delete (or restore) the selected row" :disabled="!selectedCell" @click="onDeleteResultRow">
              <AppIcon name="trash" />
            </button>
            <button class="primary" :disabled="pendingEdits === 0 || savingEdits" @click="saveEdits">
              <AppIcon name="commit" /> Save<template v-if="pendingEdits"> ({{ pendingEdits }})</template>
            </button>
            <button class="ghost" :disabled="pendingEdits === 0" @click="discardEdits">Discard</button>
          </template>
          <span
            v-else-if="!showHistory && !showOutput && current?.result?.columns.length && current.result.readOnlyReason"
            class="muted small readonly"
            :title="`Not editable: ${current.result.readOnlyReason}`"
          >
            Read-only: {{ current.result.readOnlyReason }}
          </span>
          <span v-if="!editSource" class="muted small">{{ completionNote }}</span>
          <button v-if="showHistory && history.length" class="ghost" @click="wipeHistory">Clear history</button>
          <button v-if="!showHistory && current?.result?.columns.length" class="ghost" :disabled="!selectedCell" @click="onViewValue">
            View value
          </button>
          <button
            v-if="!showHistory && !showOutput && current?.result?.columns.length"
            class="ghost"
            :class="{ on: calcOpen }"
            :aria-pressed="calcOpen"
            title="Calc panel: count, sum, average, min and max of the selected cells"
            @click="calcOpen = !calcOpen"
          >
            <AppIcon name="sigma" /> Calc
          </button>
          <ExportMenu
            v-if="!showHistory && current?.result?.columns.length"
            :columns="current.result.columns"
            :rows="current.result.rows"
            :name="current.title"
            :fetch-all="current.result.truncated ? fetchAllRows : undefined"
          />
        </div>
      </div>
    </div>
    <ParamsDialog ref="paramsDialog" />
    <EditPreviewDialog ref="editPreview" />
  </section>
</template>

<style scoped>
.editor-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.toolbar .on {
  background: var(--accent-soft);
  border-color: var(--accent);
}

.sep {
  width: 1px;
  height: 18px;
  background: var(--border);
  margin: 0 4px;
}

.check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.conn {
  margin-left: auto;
}

.prod-badge {
  margin-right: 6px;
  padding: 1px 6px;
  border-radius: 3px;
  background: var(--danger);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
}

.ro-badge {
  margin-right: 6px;
  font-size: 11px;
}

.pending {
  border-color: var(--warn);
  color: var(--warn);
}

.banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  background: var(--accent-soft);
  border-bottom: 1px solid var(--border);
}

.split {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.editor-row {
  display: flex;
  min-height: 80px;
}

.cm-host {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.outline {
  flex: none;
  width: 240px;
  overflow: auto;
  border-left: 1px solid var(--border);
  background: var(--panel);
  font-size: 12px;
}

.outline-head {
  position: sticky;
  top: 0;
  display: flex;
  justify-content: space-between;
  padding: 5px 8px;
  background: var(--panel-2);
  border-bottom: 1px solid var(--border);
  font-weight: 600;
}

.outline-item {
  display: flex;
  gap: 6px;
  width: 100%;
  padding: 3px 8px;
  border: 0;
  border-radius: 0;
  background: transparent;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.outline-item:hover {
  background: var(--accent-soft);
}

.outline-item .mono {
  overflow: hidden;
  text-overflow: ellipsis;
}

.ln {
  min-width: 24px;
  text-align: right;
}

.hint-small {
  padding: 6px 8px;
  margin: 0;
}

.plan {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--panel);
}

.plan-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
}

.plan-tree {
  flex: 1;
  overflow: auto;
  font-size: 12px;
}

.grid {
  position: relative;
}

.tree-toggle {
  position: absolute;
  right: 8px;
  top: 2px;
  z-index: 3;
}

.cm-host :deep(.cm-editor) {
  height: 100%;
}

.handle {
  height: 5px;
  flex: none;
  cursor: row-resize;
  background: var(--border);
}

.handle:hover {
  background: var(--accent);
}

.results {
  display: flex;
  flex-direction: column;
  min-height: 80px;
  min-width: 0;
}

.grid {
  flex: 1;
  min-height: 0;
}

.result-tabs {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  min-height: 28px;
  padding: 3px 6px 0;
  background: var(--panel-2);
  overflow-x: auto;
}

.result-tab {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  height: 25px;
  padding: 0 2px 0 2px;
  border-radius: 4px 4px 0 0;
  white-space: nowrap;
  cursor: default;
}

.result-tab:hover {
  background: color-mix(in srgb, var(--panel) 60%, transparent);
}

.result-tabs .on {
  background: var(--panel);
  border-bottom: 2px solid var(--accent);
  border-radius: 4px 4px 0 0;
}

.mini {
  display: inline-flex;
  padding: 2px;
  border: 0;
}

.pin {
  opacity: 0;
  color: var(--muted);
}

.result-tab:hover .pin,
.pin.active {
  opacity: 1;
}

.pin.active {
  color: var(--accent);
}

.statusbar .on {
  background: var(--accent-soft);
  border-color: var(--accent);
}

.result-tabs .bad {
  color: var(--danger);
}

.spacer {
  flex: 1;
}

.error {
  flex: 1;
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  color: var(--danger);
  white-space: pre-wrap;
  background: var(--panel);
}

.hint {
  flex: 1;
  display: grid;
  place-items: center;
  background: var(--panel);
  text-align: center;
  padding: 12px;
}

.hint .ok {
  color: var(--ok);
}

.output {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  background: var(--panel);
  font-size: 12px;
  white-space: pre-wrap;
}

.history {
  flex: 1;
  min-height: 0;
  overflow: auto;
  background: var(--panel);
}

.entry {
  display: grid;
  grid-template-columns: 10px 150px 1fr auto;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 4px 10px;
  border: 0;
  border-bottom: 1px solid var(--border);
  border-radius: 0;
  background: transparent;
  text-align: left;
}

.entry:hover {
  background: var(--panel-2);
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ok);
}

.dot.bad {
  background: var(--danger);
}

.when,
.what,
.small {
  font-size: 11px;
}

.text {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 12px;
}

.manual {
  color: var(--warn);
  font-weight: 600;
}

.uncommitted {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 0 4px 0 8px;
  border: 1px solid var(--warn);
  border-radius: 4px;
  color: var(--warn);
  font-size: 12px;
  font-weight: 600;
}

.readonly {
  max-width: 45%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.statusbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 8px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
  min-height: 28px;
}
</style>
