<script setup lang="ts">
import { loadFilters, pushFilter, saveFilters } from '@/lib/recentFilters'
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { CellValue, ConstraintInfo, DataPage, DbObject, Privilege, ResultColumn, TableDetails } from '@/api/types'
import {
  addRow,
  buildChanges,
  changeCount,
  displayRows,
  duplicateRow,
  emptyEdits,
  isChanged,
  setCell,
  toggleDelete,
  type PendingEdits,
} from '@/lib/edits'
import { parseImport } from '@/lib/importers'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import { useWorkspace, type TableTab } from '@/stores/workspace'
import { useSchemaService } from '@/composables/schema'
import { useTools } from '@/composables/tools'
import { useValueViewer } from '@/composables/viewer'
import { calcOpen } from '@/composables/calc'
import {
  dropColumn,
  dropConstraint,
  dropForeignKey,
  dropIndex,
  dropTable,
  maintenance,
  maintenanceTasks,
  renameTable,
  truncateTable,
  type Dialect,
  type Maintenance,
  type TableRef,
} from '@/lib/ddl'
import { equalsCondition, groupKeys, linkFilter, type KeyLink } from '@/lib/filters'
import AppIcon from './AppIcon.vue'
import EditPreviewDialog from './EditPreviewDialog.vue'
import ErDiagram from './ErDiagram.vue'
import ExportMenu from './ExportMenu.vue'
import ResultGrid from './ResultGrid.vue'
import ToolMenu from './ToolMenu.vue'

const props = defineProps<{ tab: TableTab; active: boolean }>()

type Section = 'data' | 'columns' | 'constraints' | 'foreignKeys' | 'indexes' | 'triggers' | 'permissions' | 'ddl' | 'er'

const connections = useConnections()
const ui = useUi()
const workspace = useWorkspace()
const connected = computed(() => connections.isConnected(props.tab.connectionId))
const scope = computed(() => ({ catalog: props.tab.catalog, schema: props.tab.schema }))

const section = ref<Section>('data')
const page = ref<DataPage | null>(null)
const details = ref<TableDetails | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const edits = ref<PendingEdits>(emptyEdits())
const selectedRow = ref<number | null>(null)
const selectedCol = ref<number | null>(null)
/** selected row of the Columns / Foreign keys / Indexes grids */
const detailSelected = ref<number | null>(null)
const schema = useSchemaService()
const viewValue = useValueViewer()
const tools = useTools()
const tableRef = computed<TableRef>(() => ({ catalog: props.tab.catalog, schema: props.tab.schema, name: props.tab.table }))
const preview = ref<InstanceType<typeof EditPreviewDialog> | null>(null)
const ddlText = ref<string | null>(null)
const privileges = ref<Privilege[] | null>(null)
const constraints = ref<ConstraintInfo[] | null>(null)
const triggers = ref<DbObject[] | null>(null)
const dialect = ref<Dialect | null>(null)
/** The view's DDL being edited in the DDL tab ("Edit" → CREATE OR REPLACE). */
const ddlDraft = ref<string | null>(null)
const view = ref<boolean | null>(null)
const total = ref<number | null>(null)
const counting = ref(false)
const recordView = ref(false)
const importing = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const limit = ref(200)
const offset = ref(0)
const orderBy = ref<string | null>(null)
const desc = ref(false)
const whereDraft = ref(props.tab.where ?? '')
const where = ref(props.tab.where ?? '')

const sections: { id: Section; label: string }[] = [
  { id: 'data', label: 'Data' },
  { id: 'columns', label: 'Columns' },
  { id: 'constraints', label: 'Constraints' },
  { id: 'foreignKeys', label: 'Foreign keys' },
  { id: 'indexes', label: 'Indexes' },
  { id: 'triggers', label: 'Triggers' },
  { id: 'permissions', label: 'Permissions' },
  { id: 'ddl', label: 'DDL' },
  { id: 'er', label: 'ER diagram' },
]

const qualifiedName = computed(() => [props.tab.schema ?? props.tab.catalog, props.tab.table].filter(Boolean).join('.'))
const rangeText = computed(() => {
  if (!page.value || page.value.rows.length === 0) return 'no rows'
  return `rows ${offset.value + 1}–${offset.value + page.value.rows.length}${page.value.hasMore ? '+' : ''}`
})

const autoCommit = computed(() => connections.autoCommit[props.tab.connectionId] ?? null)
const txDirty = computed(() => connections.txDirty[props.tab.connectionId] === true)

/** Columns that address a row: the primary key, else a unique index, else every comparable column (DBeaver's virtual key). */
const primaryKey = computed(() => details.value?.rowKey ?? [])
const readOnlyReason = computed(() => {
  if (!details.value || !page.value) return null
  if (connections.connectionOf(props.tab.connectionId)?.readOnly) return 'Read-only connection.'
  if (primaryKey.value.length === 0) return 'Read-only: rows of this table cannot be identified (no key and no comparable column).'
  const names = new Set(page.value.columns.map((c) => c.name))
  if (!primaryKey.value.every((k) => names.has(k))) return 'Read-only: the row key is not in the result.'
  return null
})
const keyNote = computed(() => {
  switch (details.value?.rowKeyKind) {
    case 'UNIQUE_INDEX':
      return `No primary key: rows are matched by the unique index (${primaryKey.value.join(', ')}).`
    case 'ALL_COLUMNS':
      return 'No primary key: rows are matched by all their values; a change that would hit several identical rows is refused.'
    default:
      return null
  }
})
const editable = computed(() => connected.value && !!page.value && !!details.value && readOnlyReason.value === null)
const pending = computed(() => changeCount(edits.value))
const shownRows = computed(() => (page.value ? displayRows(edits.value, page.value.rows, page.value.columns) : []))

function cellChanged(row: number, col: number): boolean {
  return page.value ? isChanged(edits.value, page.value.rows.length, page.value.columns, row, col) : false
}

/** Ask before an action that would throw away unsaved edits. */
function canDiscard(): boolean {
  if (pending.value === 0) return true
  if (!window.confirm(`Discard ${pending.value} unsaved change(s)?`)) return false
  discard()
  return true
}

function discard() {
  edits.value = emptyEdits()
  selectedRow.value = null
}

async function loadData() {
  if (!connected.value) return
  loading.value = true
  error.value = null
  try {
    const [data, info] = await Promise.all([
      api.tableData(props.tab.connectionId, scope.value, props.tab.table, {
        limit: limit.value,
        offset: offset.value,
        orderBy: orderBy.value,
        desc: desc.value,
        where: where.value,
      }),
      details.value ? Promise.resolve(details.value) : api.tableDetails(props.tab.connectionId, scope.value, props.tab.table),
    ])
    page.value = data
    details.value = info
    discard()
    if (connections.autoCommit[props.tab.connectionId] === undefined) {
      await connections.refreshAutoCommit(props.tab.connectionId)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    page.value = null
  } finally {
    loading.value = false
  }
}

async function loadDetails() {
  if (!connected.value) return
  loading.value = true
  error.value = null
  try {
    details.value = await api.tableDetails(props.tab.connectionId, scope.value, props.tab.table)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function reload() {
  if (section.value === 'data') {
    if (canDiscard()) void loadData()
  } else if (section.value === 'ddl') {
    void loadDdl()
  } else if (section.value === 'permissions') {
    void loadPrivileges()
  } else if (section.value === 'constraints') {
    void loadConstraints()
  } else if (section.value === 'triggers') {
    void loadTriggers()
  } else {
    void loadDetails()
  }
}

async function loadPrivileges() {
  if (!connected.value) return
  loading.value = true
  error.value = null
  try {
    privileges.value = await api.privileges(props.tab.connectionId, scope.value, props.tab.table)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function loadConstraints() {
  if (!connected.value) return
  loading.value = true
  error.value = null
  try {
    constraints.value = await api.constraints(props.tab.connectionId, scope.value, props.tab.table)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

/** Triggers of the schema whose table is this one. */
async function loadTriggers() {
  if (!connected.value) return
  loading.value = true
  error.value = null
  try {
    const objects = await api.objects(props.tab.connectionId, scope.value)
    triggers.value = objects.triggers.filter((t) => t.detail === `on ${props.tab.table}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

const selectedConstraint = computed(() =>
  section.value === 'constraints' && detailSelected.value !== null ? constraints.value?.[detailSelected.value] : undefined,
)
const selectedTrigger = computed(() =>
  section.value === 'triggers' && detailSelected.value !== null ? triggers.value?.[detailSelected.value] : undefined,
)

// ---- structure changes: queued and saved together (DBeaver's table editor "Save") ------------
interface PendingChange {
  label: string
  statements: string[]
  danger: boolean
}
const pendingDdl = ref<PendingChange[]>([])
const pendingStatements = computed(() => pendingDdl.value.flatMap((c) => c.statements))

function queue(label: string, statements: string[] | null, danger = false) {
  if (statements?.length) pendingDdl.value.push({ label, statements, danger })
}

function unqueue(index: number) {
  pendingDdl.value.splice(index, 1)
}

/** Preview every queued statement as one script and run it; then reload the structure. */
async function saveStructure() {
  if (pendingDdl.value.length === 0) return
  const ok = await schema.execute(
    props.tab.connectionId,
    `Save ${pendingDdl.value.length} change(s) to ${qualifiedName.value}`,
    pendingStatements.value,
    pendingDdl.value.some((c) => c.danger),
  )
  if (ok) pendingDdl.value = []
  // the schema-change watcher reloads the open section; refresh the structure the forms use too
  await loadDetails()
}

function discardStructure() {
  if (pendingDdl.value.length && !window.confirm(`Discard ${pendingDdl.value.length} unsaved structure change(s)?`)) return
  pendingDdl.value = []
}

async function onAddCheck() {
  if (!details.value) return
  queue('Add check constraint', await schema.form(props.tab.connectionId, 'check', tableRef.value, details.value.columns))
}

async function onAddUnique() {
  if (!details.value) return
  queue('Add unique constraint', await schema.form(props.tab.connectionId, 'unique', tableRef.value, details.value.columns))
}

async function onDropConstraint() {
  const c = selectedConstraint.value
  if (!c) return
  queue(`Drop constraint ${c.name}`, await schema.build(props.tab.connectionId, (ctx) => dropConstraint(ctx, tableRef.value, c.name, c.type)), true)
}

async function openTriggerSource(trigger: DbObject | undefined) {
  if (!trigger) return
  const result = await ui.guard(() => api.source(props.tab.connectionId, scope.value, 'TRIGGER', trigger.name))
  if (result) workspace.openSql(props.tab.connectionId, `${connections.labelOf(props.tab.connectionId)} · ${trigger.name}`, result.source)
}

/** A template in a new script: triggers need a body, written by hand. */
function newTrigger() {
  const table = qualifiedName.value
  const name = `${props.tab.table}_trg`
  const templates: Partial<Record<Dialect, string>> = {
    postgresql: `CREATE OR REPLACE FUNCTION ${name}_fn() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  -- NEW / OLD hold the row
  RETURN NEW;
END;
$$;

CREATE TRIGGER ${name}
  BEFORE INSERT OR UPDATE ON ${table}
  FOR EACH ROW EXECUTE FUNCTION ${name}_fn();
`,
    mysql: `CREATE TRIGGER ${name}
  BEFORE INSERT ON ${table}
  FOR EACH ROW
  SET NEW.updated_at = NOW();
`,
    mariadb: `CREATE TRIGGER ${name}
  BEFORE INSERT ON ${table}
  FOR EACH ROW
  SET NEW.updated_at = NOW();
`,
    sqlite: `CREATE TRIGGER ${name}
  AFTER INSERT ON ${table}
BEGIN
  SELECT 1;
END;
`,
    sqlserver: `CREATE TRIGGER ${name} ON ${table}
AFTER INSERT, UPDATE AS
BEGIN
  SET NOCOUNT ON;
END;
`,
    oracle: `CREATE OR REPLACE TRIGGER ${name}
  BEFORE INSERT ON ${table}
  FOR EACH ROW
BEGIN
  NULL;
END;
`,
  }
  const sql = templates[dialect.value ?? 'generic'] ?? `CREATE TRIGGER ${name} ...
`
  workspace.openSql(props.tab.connectionId, connections.labelOf(props.tab.connectionId), sql)
}

const tasks = computed(() => (dialect.value ? maintenanceTasks(dialect.value) : []))

async function runMaintenance(task: Maintenance, label: string) {
  if (connections.autoCommit[props.tab.connectionId] === false && (dialect.value === 'postgresql' || dialect.value === 'sqlite')) {
    ui.error(`${label} cannot run inside a transaction: turn Auto-commit on first`)
    return
  }
  await schema.run(props.tab.connectionId, `${label} · ${qualifiedName.value}`, (ctx) => maintenance(ctx, tableRef.value, task))
}

/** Views: the DDL tab turns into an editor; saving runs CREATE OR REPLACE VIEW. */
function editView() {
  if (!ddlText.value) return
  let text = ddlText.value.trim()
  const replace = dialect.value === 'sqlserver' ? 'CREATE OR ALTER VIEW' : dialect.value === 'sqlite' ? null : 'CREATE OR REPLACE VIEW'
  if (replace) text = text.replace(/^CREATE\s+(?:OR\s+REPLACE\s+)?(?:ALGORITHM\s*=\s*\w+\s+)?(?:DEFINER\s*=\s*\S+\s+)?(?:SQL\s+SECURITY\s+\w+\s+)?VIEW/i, replace)
  ddlDraft.value = text
}

async function saveView() {
  const text = ddlDraft.value?.trim()
  if (!text) return
  const statements =
    dialect.value === 'sqlite' ? [`DROP VIEW ${qualifiedName.value}`, text.replace(/;\s*$/, '')] : [text.replace(/;\s*$/, '')]
  if (await schema.run(props.tab.connectionId, `Save view ${qualifiedName.value}`, () => statements)) {
    ddlDraft.value = null
    await loadDdl()
  }
}

/** "Generate DDL" / Source tab. */
async function loadDdl() {
  if (!connected.value) return
  loading.value = true
  error.value = null
  try {
    ddlText.value = (await api.ddl(props.tab.connectionId, scope.value, props.tab.table)).ddl
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function copyDdl() {
  if (!ddlText.value) return
  try {
    await navigator.clipboard.writeText(ddlText.value)
    ui.info('DDL copied')
  } catch {
    ui.error('Clipboard is not available in this context')
  }
}

function openDdlInEditor() {
  const name = connections.labelOf(props.tab.connectionId)
  if (ddlText.value) workspace.openSql(props.tab.connectionId, name, ddlText.value)
}

/** "Row Count": total rows matching the current filter. */
async function countRows() {
  counting.value = true
  try {
    const result = await ui.guard(() => api.count(props.tab.connectionId, scope.value, props.tab.table, where.value))
    if (result) total.value = result.count
  } finally {
    counting.value = false
  }
}

function clearFilter() {
  if (!canDiscard()) return
  whereDraft.value = ''
  where.value = ''
  workspace.setTableFilter(props.tab.id, '')
  total.value = null
  offset.value = 0
  void loadData()
}

/** The schema's editable diagram, seeded with this table and the ones its keys link (same schema). */
function openSchemaDiagram() {
  const d = details.value
  if (!d) return
  const same = (k: { refSchema: string | null; refCatalog: string | null }) => (k.refSchema ?? null) === props.tab.schema
  const related = [...d.foreignKeys, ...d.referencedBy].filter(same).map((k) => k.refTable)
  workspace.openDiagram(props.tab.connectionId, props.tab.catalog, props.tab.schema, props.tab.schema ?? props.tab.catalog ?? 'schema', [
    props.tab.table,
    ...new Set(related),
  ])
}

function openRelated(catalog: string | null, schema: string | null, table: string) {
  workspace.openTable(props.tab.connectionId, catalog, schema, table)
}

async function isView(): Promise<boolean> {
  const tables = await api.tables(props.tab.connectionId, scope.value)
  return tables.find((t) => t.name === props.tab.table)?.type.toUpperCase().includes('VIEW') ?? false
}

async function onRenameTable() {
  const name = window.prompt(`Rename ${qualifiedName.value} to:`, props.tab.table)?.trim()
  if (!name || name === props.tab.table) return
  if (await schema.run(props.tab.connectionId, 'Rename table', (ctx) => renameTable(ctx, tableRef.value, name))) {
    workspace.close(props.tab.id)
    workspace.openTable(props.tab.connectionId, props.tab.catalog, props.tab.schema, name)
  }
}

async function onTruncate() {
  if (await schema.run(props.tab.connectionId, `Truncate ${qualifiedName.value}`, (ctx) => truncateTable(ctx, tableRef.value), true)) {
    total.value = null
    await loadData()
  }
}

async function onDropTable() {
  const view = await ui.guard(isView)
  if (view === undefined) return
  const title = `Drop ${view ? 'view' : 'table'} ${qualifiedName.value}`
  if (await schema.run(props.tab.connectionId, title, (ctx) => dropTable(ctx, tableRef.value, view, false), true)) {
    workspace.close(props.tab.id)
  }
}

watch(
  connected,
  async (isConnected) => {
    if (!isConnected) return
    const ctx = await schema.context(props.tab.connectionId).catch(() => null)
    dialect.value = ctx?.dialect ?? null
    view.value = await isView().catch(() => null)
  },
  { immediate: true },
)

const selectedColumn = computed(() =>
  section.value === 'columns' && detailSelected.value !== null ? details.value?.columns[detailSelected.value] : undefined,
)
const selectedIndex = computed(() =>
  section.value === 'indexes' && detailSelected.value !== null ? details.value?.indexes[detailSelected.value]?.name : undefined,
)
const selectedForeignKey = computed(() =>
  section.value === 'foreignKeys' && detailSelected.value !== null ? details.value?.foreignKeys[detailSelected.value]?.name : undefined,
)

async function onAddColumn() {
  if (details.value) queue('Add column', await schema.form(props.tab.connectionId, 'column', tableRef.value, details.value.columns))
}

async function onEditColumn() {
  const column = selectedColumn.value
  if (details.value && column) {
    queue(`Edit column ${column.name}`, await schema.form(props.tab.connectionId, 'column', tableRef.value, details.value.columns, column))
  }
}

async function onDropColumn() {
  const column = selectedColumn.value
  if (column) queue(`Drop column ${column.name}`, await schema.build(props.tab.connectionId, (ctx) => dropColumn(ctx, tableRef.value, column.name)), true)
}

async function onCreateIndex() {
  if (details.value) queue('Create index', await schema.form(props.tab.connectionId, 'index', tableRef.value, details.value.columns))
}

async function onDropIndex() {
  const name = selectedIndex.value
  if (name) queue(`Drop index ${name}`, await schema.build(props.tab.connectionId, (ctx) => dropIndex(ctx, tableRef.value, name)), true)
}

async function onAddForeignKey() {
  if (details.value) queue('Add foreign key', await schema.form(props.tab.connectionId, 'foreignKey', tableRef.value, details.value.columns))
}

async function onDropForeignKey() {
  const name = selectedForeignKey.value
  if (name) queue(`Drop foreign key ${name}`, await schema.build(props.tab.connectionId, (ctx) => dropForeignKey(ctx, tableRef.value, name)), true)
}

/** Value viewer for the selected data cell; saved rows load the full value by primary key. */
function onViewValue() {
  if (!page.value || selectedRow.value === null || selectedCol.value === null) return
  const column = page.value.columns[selectedCol.value]
  const value = shownRows.value[selectedRow.value]?.[selectedCol.value]
  if (!column || value === undefined) return
  const row = selectedRow.value
  const saved = row < page.value.rows.length && !isChanged(edits.value, page.value.rows.length, page.value.columns, row, selectedCol.value)
  const canLoad = saved && primaryKey.value.length > 0 && readOnlyReason.value === null
  const index = new Map(page.value.columns.map((c, i) => [c.name, i]))
  const original = page.value.rows[row]!
  viewValue({
    column: column.name,
    typeName: column.typeName,
    value,
    load: canLoad
      ? () =>
          api.cell(props.tab.connectionId, scope.value, props.tab.table, column.name,
            Object.fromEntries(primaryKey.value.map((k) => [k, original[index.get(k)!]!])))
      : undefined,
  })
}

/** "Filter by value": narrow the data to rows whose column equals the selected cell. */
async function onFilterByValue() {
  if (!page.value || selectedRow.value === null || selectedCol.value === null) return
  const column = page.value.columns[selectedCol.value]
  const value = shownRows.value[selectedRow.value]?.[selectedCol.value]
  if (!column || value === undefined) return
  const ctx = await ui.guard(() => schema.context(props.tab.connectionId))
  if (!ctx) return
  const condition = equalsCondition(ctx, column.name, column.kind, value)
  whereDraft.value = where.value ? `(${where.value}) AND ${condition}` : condition
  applyFilter()
}

function onDuplicate() {
  if (page.value && selectedRow.value !== null) {
    duplicateRow(edits.value, page.value.rows, page.value.columns, selectedRow.value, primaryKey.value)
  }
}

const IMPORT_BATCH = 500

/**
 * Import (DBeaver "Data transfer: Files -> Table"): CSV/TSV, JSON or XML; field names are matched to
 * columns case-insensitively; rows are inserted in batches of 500, each batch in
 * its own save, so a failure stops the import after the batches already written.
 */
async function importCsv(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !details.value) return
  let header: string[]
  let records: (string | null)[][]
  try {
    ;({ header, records } = parseImport(file.name, await file.text()))
  } catch (e) {
    ui.error(`Cannot read ${file.name}: ${e instanceof Error ? e.message : String(e)}`)
    return
  }
  if (header.length === 0 || records.length === 0) {
    ui.error(`${file.name} has no data rows`)
    return
  }
  const byLower = new Map(details.value.columns.map((c) => [c.name.toLowerCase(), c.name]))
  const mapping = header.map((h) => byLower.get(h.trim().toLowerCase()) ?? null)
  const used = mapping.filter((m): m is string => m !== null)
  const ignored = header.filter((_, i) => mapping[i] === null)
  if (used.length === 0) {
    ui.error(`No field of ${file.name} matches a column of ${props.tab.table}`)
    return
  }
  const message =
    `Import ${records.length} row(s) from ${file.name} into ${qualifiedName.value}?\n\n` +
    `Columns: ${used.join(', ')}` +
    (ignored.length ? `\nIgnored (no matching column): ${ignored.join(', ')}` : '') +
    (file.name.toLowerCase().endsWith('.csv') || file.name.toLowerCase().endsWith('.tsv') ? `\nEmpty fields are inserted as NULL.` : '')
  if (!window.confirm(message)) return

  importing.value = true
  let imported = 0
  try {
    for (let start = 0; start < records.length; start += IMPORT_BATCH) {
      const changes = records.slice(start, start + IMPORT_BATCH).map((record) => {
        const values: Record<string, CellValue> = {}
        mapping.forEach((column, i) => {
          if (column) values[column] = record[i] ?? null
        })
        return { op: 'INSERT' as const, values }
      })
      const result = await api.applyEdits(props.tab.connectionId, scope.value, props.tab.table, changes)
      imported += result.rowsAffected
      if (!result.committed) connections.markDirty(props.tab.connectionId)
    }
    ui.info(`Imported ${imported} row(s) into ${props.tab.table}`)
  } catch (e) {
    ui.error(`Import stopped after ${imported} row(s): ${e instanceof Error ? e.message : String(e)}`)
  } finally {
    importing.value = false
    total.value = null
    await loadData()
  }
}

/** "Toggle Grid/Record view": the selected row as column/value pairs. */
const recordGrid = computed<{ columns: ResultColumn[]; rows: CellValue[][] } | null>(() => {
  if (!page.value || selectedRow.value === null) return null
  const row = shownRows.value[selectedRow.value]
  if (!row) return null
  return {
    columns: [
      { name: 'Column', typeName: 'text', kind: 'text' },
      { name: 'Type', typeName: 'text', kind: 'text' },
      { name: 'Value', typeName: 'text', kind: 'text' },
    ],
    rows: page.value.columns.map((c, i) => [c.name, c.typeName, row[i] === undefined ? 'DEFAULT' : row[i]!]),
  }
})

// ---- foreign key navigation ---------------------------------------------------
/** Keys of this table pointing elsewhere, and keys of other tables pointing here ("References"). */
const outgoing = computed(() => groupKeys(details.value?.foreignKeys ?? []))
const incoming = computed(() => groupKeys(details.value?.referencedBy ?? []))
/** Result columns that are part of an outgoing key: their cells link to the referenced row. */
const linkColumns = computed(() => {
  const keyed = new Set(outgoing.value.flatMap((k) => k.pairs.map((p) => p.column)))
  return new Set((page.value?.columns ?? []).flatMap((c, i) => (keyed.has(c.name) ? [i] : [])))
})

/** Open the other table of `link`, filtered to the rows that match `row` of this one. */
async function openLinked(link: KeyLink, row: number) {
  if (!page.value) return
  const ctx = await ui.guard(() => schema.context(props.tab.connectionId))
  if (!ctx) return
  const filter = linkFilter(ctx, link, page.value.columns, shownRows.value[row] ?? [])
  if (filter === undefined) {
    ui.info(`The row has no value for ${link.pairs.map((p) => p.column).join(', ')}: it references nothing`)
    return
  }
  // same container as this table: reuse its catalog/schema spelling so the tab is not opened twice
  const sameSchema = (link.schema ?? null) === props.tab.schema
  const sameCatalog = !link.catalog || !props.tab.catalog || link.catalog === props.tab.catalog
  const catalog = sameSchema && sameCatalog ? props.tab.catalog : link.catalog
  const schemaName = sameSchema && sameCatalog ? props.tab.schema : link.schema
  workspace.openTable(props.tab.connectionId, catalog, schemaName, link.table, filter)
}

/** Click on a key cell's link: the referenced row. */
function follow(row: number, col: number) {
  const name = page.value?.columns[col]?.name
  const link = outgoing.value.find((k) => k.pairs.some((p) => p.column === name))
  if (link) void openLinked(link, row)
}

function linkLabel(link: KeyLink) {
  return `${[link.schema ?? link.catalog, link.table].filter(Boolean).join('.')} (${link.pairs.map((p) => p.other).join(', ')})`
}

// The tab's filter was set from outside (a foreign key followed from another table): show those rows.
watch(
  () => props.tab.where ?? '',
  (next) => {
    if (next === where.value) return
    if (!canDiscard()) return
    whereDraft.value = next
    where.value = next
    total.value = null
    offset.value = 0
    section.value = 'data'
    void loadData()
  },
)

const filterKey = () => [props.tab.connectionId, props.tab.catalog, props.tab.schema, props.tab.table].join('|')
const recentFilters = ref<string[]>(loadFilters(filterKey()))

function applyFilter() {
  if (!canDiscard()) return
  total.value = null
  where.value = whereDraft.value
  recentFilters.value = pushFilter(recentFilters.value, where.value)
  saveFilters(filterKey(), recentFilters.value)
  workspace.setTableFilter(props.tab.id, where.value)
  offset.value = 0
  void loadData()
}

function sortBy(column: string) {
  if (!canDiscard()) return
  if (orderBy.value !== column) {
    orderBy.value = column
    desc.value = false
  } else if (!desc.value) {
    desc.value = true
  } else {
    orderBy.value = null
    desc.value = false
  }
  offset.value = 0
  void loadData()
}

function move(direction: -1 | 1) {
  if (!canDiscard()) return
  offset.value = Math.max(0, offset.value + direction * limit.value)
  void loadData()
}

const EXPORT_PAGE = 50_000
const EXPORT_MAX = 1_000_000

/** Export "All rows": every row matching the filter and sort, page by page. */
async function fetchAllRows() {
  const rows: CellValue[][] = []
  let columns = page.value?.columns ?? []
  for (let offset = 0; offset < EXPORT_MAX; offset += EXPORT_PAGE) {
    const chunk = await api.tableData(props.tab.connectionId, scope.value, props.tab.table, {
      limit: EXPORT_PAGE,
      offset,
      orderBy: orderBy.value,
      desc: desc.value,
      where: where.value,
    })
    columns = chunk.columns
    rows.push(...chunk.rows)
    if (!chunk.hasMore) break
  }
  return { columns, rows }
}

function changeLimit() {
  offset.value = 0
  void loadData()
}

function onEdit(row: number, col: number, value: CellValue) {
  if (page.value) setCell(edits.value, page.value.rows, page.value.columns, row, col, value)
}

function onAddRow() {
  addRow(edits.value)
}

function onDeleteRow() {
  if (page.value && selectedRow.value !== null && selectedRow.value < shownRows.value.length) {
    toggleDelete(edits.value, page.value.rows.length, selectedRow.value)
  }
}

async function save() {
  if (!page.value || pending.value === 0) return
  const changes = buildChanges(edits.value, page.value.rows, page.value.columns, primaryKey.value)
  if (changes.length === 0) {
    ui.info('Nothing to save: new rows need at least one value')
    return
  }
  if (connections.isProduction(props.tab.connectionId) &&
    !window.confirm(`PRODUCTION connection: save ${changes.length} row change(s) to ${qualifiedName.value}?`)) {
    return
  }
  saving.value = true
  try {
    const plan = await ui.guard(() => api.previewEdits(props.tab.connectionId, scope.value, props.tab.table, changes))
    if (!plan || !(await preview.value?.confirm(plan.statements, autoCommit.value === false))) return
    const result = await ui.guard(() => api.applyEdits(props.tab.connectionId, scope.value, props.tab.table, changes))
    if (!result) return
    let committed = result.committed
    if (!committed && preview.value?.commitRequested()) {
      committed = (await ui.guard(async () => {
        await connections.endTransaction(props.tab.connectionId, 'commit')
        return true
      })) === true
    }
    if (committed) {
      ui.info(`Saved: ${result.rowsAffected} row(s) changed`)
    } else {
      connections.markDirty(props.tab.connectionId)
      ui.info(`${result.rowsAffected} row(s) changed but NOT committed yet: Commit to save, Rollback to undo`)
    }
    await loadData()
  } finally {
    saving.value = false
  }
}

async function toggleAutoCommit() {
  await ui.guard(() => connections.setAutoCommit(props.tab.connectionId, !(autoCommit.value ?? true)))
}

async function endTransaction(kind: 'commit' | 'rollback') {
  const ok = await ui.guard(async () => {
    await connections.endTransaction(props.tab.connectionId, kind)
    return true
  })
  if (ok) {
    ui.info(kind === 'commit' ? 'Committed' : 'Rolled back')
    if (canDiscard()) await loadData()
  }
}


const textColumn = (name: string): ResultColumn => ({ name, typeName: 'text', kind: 'text' })
const yesNo = (v: boolean): CellValue => (v ? 'YES' : 'NO')

const detailGrid = computed<{ columns: ResultColumn[]; rows: CellValue[][] } | null>(() => {
  const d = details.value
  // constraints, triggers and permissions have their own data; the others need the table structure
  if (!d && !['constraints', 'triggers', 'permissions'].includes(section.value)) return null
  switch (section.value) {
    case 'columns':
      return {
        columns: ['#', 'Name', 'Type', 'Size', 'Nullable', 'Default', 'Primary key', 'Auto-increment', 'Comment'].map(textColumn),
        rows: d!.columns.map((c) => [
          c.position,
          c.name,
          c.typeName,
          c.size > 0 && c.size < 100000 ? c.size : null,
          yesNo(c.nullable),
          c.defaultValue,
          yesNo(c.primaryKey),
          yesNo(c.autoIncrement),
          c.remarks,
        ]),
      }
    case 'foreignKeys':
      return {
        columns: ['Name', 'Column', 'References'].map(textColumn),
        rows: d!.foreignKeys.map((k) => [
          k.name,
          k.column,
          `${[k.refSchema ?? k.refCatalog, k.refTable].filter(Boolean).join('.')}(${k.refColumn})`,
        ]),
      }
    case 'constraints':
      return constraints.value
        ? {
            columns: ['Name', 'Type', 'Columns', 'Definition'].map(textColumn),
            rows: constraints.value.map((c) => [c.name, c.type, c.columns.join(', '), c.definition]),
          }
        : null
    case 'triggers':
      return triggers.value
        ? { columns: ['Name', 'Table'].map(textColumn), rows: triggers.value.map((t) => [t.name, props.tab.table]) }
        : null
    case 'permissions':
      return privileges.value
        ? {
            columns: ['Grantee', 'Privilege', 'Grantable', 'Grantor'].map(textColumn),
            rows: privileges.value.map((p) => [p.grantee, p.privilege, p.grantable, p.grantor]),
          }
        : null
    case 'indexes':
      return {
        columns: ['Name', 'Column', 'Unique', 'Position'].map(textColumn),
        rows: d!.indexes.map((i) => [i.name, i.column, yesNo(i.unique), i.position]),
      }
    default:
      return null
  }
})

// A statement elsewhere changed data on this session: show it (unless there are unsaved edits here).
watch(
  () => connections.dataEpoch[props.tab.connectionId],
  () => {
    if (section.value === 'data' && page.value && pending.value === 0) void loadData()
  },
)

// A script changed the schema: cached structure (columns, keys, DDL, ER) may be stale.
watch(
  () => connections.schemaEpoch[props.tab.connectionId],
  () => {
    details.value = null
    ddlText.value = null
    constraints.value = null
    triggers.value = null
    if (section.value === 'ddl') void loadDdl()
    else if (section.value === 'constraints') void loadConstraints().then(loadDetails)
    else if (section.value === 'triggers') void loadTriggers()
    else if (section.value !== 'data') void loadDetails()
  },
)

watch(section, () => (detailSelected.value = null))

watch(
  [section, connected],
  ([current, isConnected], previous) => {
    // Switching sections keeps what is loaded (and any unsaved edits); (re)connecting reloads.
    const reconnected = isConnected !== previous?.[1]
    if (current === 'data') {
      if (!page.value || reconnected) void loadData()
    } else if (current === 'ddl') {
      if (ddlText.value === null || reconnected) void loadDdl()
    } else if (current === 'permissions') {
      if (privileges.value === null || reconnected) void loadPrivileges()
    } else if (current === 'constraints') {
      if (constraints.value === null || reconnected) void loadConstraints()
      if (!details.value) void loadDetails()
    } else if (current === 'triggers') {
      if (triggers.value === null || reconnected) void loadTriggers()
    } else if (!details.value || reconnected) {
      void loadDetails()
    }
  },
  { immediate: true },
)
</script>

<template>
  <section class="viewer">
    <div class="head">
      <AppIcon name="table" :size="15" class="icon" />
      <strong class="mono">{{ qualifiedName }}</strong>
      <span class="spacer" />
      <button
        class="ghost"
        title="Copy rows (matching the filter) into a table of any open connection"
        :disabled="!connected"
        @click="tools.copyData({ connectionId: tab.connectionId, catalog: tab.catalog, schema: tab.schema, table: tab.table, where })"
      >
        Copy data…
      </button>
      <ToolMenu v-if="tasks.length" title="Maintenance: vacuum, analyze, reindex…" :disabled="!connected">
        <template #button><AppIcon name="gauge" /> Tools</template>
        <template #default="{ close }">
          <div class="heading">Maintenance</div>
          <button v-for="t in tasks" :key="t.id" class="item" @click="close(), runMaintenance(t.id, t.label)">{{ t.label }}</button>
        </template>
      </ToolMenu>
      <button class="ghost" title="Rename table" :disabled="!connected" @click="onRenameTable">Rename</button>
      <button class="ghost" title="Delete all rows" :disabled="!connected" @click="onTruncate">Truncate</button>
      <button class="ghost danger" title="Drop table" :disabled="!connected" @click="onDropTable"><AppIcon name="trash" /> Drop</button>
      <button class="ghost" title="Reload" :disabled="!connected || loading" @click="reload">
        <AppIcon name="refresh" /> Reload
      </button>
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
      <span v-if="pendingDdl.length" class="pending-badge" :title="`${pendingDdl.length} unsaved structure change(s)`">● {{ pendingDdl.length }} unsaved</span>
    </div>

    <div v-if="!connected" class="banner">Not connected — connect from the navigator to load this table.</div>

    <template v-if="section === 'data'">
      <form class="filter" @submit.prevent="applyFilter">
        <input
          v-model="whereDraft"
          class="mono"
          placeholder="Filter: SQL condition, e.g. status = 'active' AND id > 10"
          aria-label="Filter condition"
          list="recent-filters"
        />
        <datalist id="recent-filters">
          <option v-for="f in recentFilters" :key="f" :value="f" />
        </datalist>
        <button type="submit" :disabled="!connected || loading">Apply</button>
        <button v-if="where" type="button" title="Remove filter" :disabled="loading" @click="clearFilter">
          <AppIcon name="close" /> Clear
        </button>
      </form>
      <div class="edit-bar">
        <template v-if="readOnlyReason">
          <span class="muted">{{ readOnlyReason }}</span>
        </template>
        <template v-else>
          <button :disabled="!editable" title="Add a row" @click="onAddRow"><AppIcon name="plus" /> Row</button>
          <button :disabled="!editable || selectedRow === null" title="Delete (or restore) the selected row" @click="onDeleteRow">
            <AppIcon name="trash" /> Delete row
          </button>
          <button :disabled="!editable || selectedRow === null" title="Duplicate the selected row" @click="onDuplicate">
            <AppIcon name="copy" /> Duplicate
          </button>
          <span class="sep" />
          <button class="primary" :disabled="pending === 0 || saving" title="Preview and save changes" @click="save">
            <AppIcon name="commit" /> Save<template v-if="pending"> ({{ pending }})</template>
          </button>
          <button :disabled="pending === 0 || saving" @click="discard"><AppIcon name="close" /> Discard</button>
          <span class="hint muted" :title="keyNote ?? undefined">{{ keyNote ? '⚠ ' + keyNote : 'Double-click or Enter edits a cell' }}</span>
        </template>
        <span class="sep" />
        <button :disabled="selectedCol === null || !page" title="Show only rows with the selected value" @click="onFilterByValue">
          Filter by value
        </button>
        <button :disabled="selectedCol === null || !page" title="Open the selected value in the value viewer" @click="onViewValue">
          View value
        </button>
        <ToolMenu title="References: rows of other tables that point to the selected row" :disabled="selectedRow === null || incoming.length === 0 && outgoing.length === 0">
          <template #button><AppIcon name="link" /> References</template>
          <template #default="{ close }">
            <template v-if="outgoing.length">
              <div class="heading">Referenced row (this row points to)</div>
              <button v-for="link in outgoing" :key="'o' + link.name" class="item" @click="close(), selectedRow !== null && openLinked(link, selectedRow)">
                {{ linkLabel(link) }}
              </button>
            </template>
            <template v-if="incoming.length">
              <hr v-if="outgoing.length" />
              <div class="heading">Referencing rows (point to this row)</div>
              <button v-for="link in incoming" :key="'i' + link.name + link.table" class="item" @click="close(), selectedRow !== null && openLinked(link, selectedRow)">
                {{ linkLabel(link) }}
              </button>
            </template>
          </template>
        </ToolMenu>
        <button :class="{ on: recordView }" :aria-pressed="recordView" :disabled="!page" title="Show the selected row as a record" @click="recordView = !recordView">
          Record
        </button>
        <button :disabled="!connected || !details || importing" title="Import rows from a CSV, TSV, JSON or XML file" @click="fileInput?.click()">
          {{ importing ? 'Importing…' : 'Import' }}
        </button>
        <input ref="fileInput" type="file" accept=".csv,.tsv,.txt,.json,.xml,text/csv,application/json,application/xml" hidden @change="importCsv" />
        <span class="spacer" />
        <label class="check" :class="{ manual: autoCommit === false }" title="When off, saves stay pending until you commit or roll back">
          <input type="checkbox" :checked="autoCommit !== false" :disabled="!connected" @change="toggleAutoCommit" />
          {{ autoCommit === false ? 'Auto-commit OFF' : 'Auto-commit' }}
        </label>
        <span v-if="txDirty" class="uncommitted">Uncommitted changes</span>
        <template v-if="autoCommit === false">
          <button :class="{ pendingtx: txDirty }" :disabled="!connected" @click="endTransaction('commit')">
            <AppIcon name="commit" /> Commit
          </button>
          <button :disabled="!connected" @click="endTransaction('rollback')"><AppIcon name="rollback" /> Rollback</button>
        </template>
      </div>
      <div v-if="error" class="error mono">{{ error }}</div>
      <div v-else-if="recordView" class="grid">
        <ResultGrid v-if="recordGrid" :columns="recordGrid.columns" :rows="recordGrid.rows" />
        <div v-else class="muted center">Select a cell in the grid, then switch to Record view</div>
      </div>
      <div v-else class="grid">
        <ResultGrid
          v-if="page"
          :columns="page.columns"
          :rows="shownRows"
          :start-row="offset + 1"
          :export-name="qualifiedName"
          sortable
          :sort-column="orderBy"
          :sort-desc="desc"
          :editable="editable"
          :is-changed="cellChanged"
          :deleted-rows="edits.deletes"
          :inserted-from="page.rows.length"
          :links="linkColumns"
          :calc-panel="calcOpen"
          @sort="sortBy"
          @select="(row, col) => ((selectedRow = row), (selectedCol = col))"
          @edit="onEdit"
          @follow="follow"
        />
      </div>
      <div class="statusbar">
        <button class="ghost" aria-label="Previous page" :disabled="offset === 0 || loading" @click="move(-1)">
          <AppIcon name="prev" />
        </button>
        <span class="muted">{{ loading ? 'Loading…' : rangeText }}</span>
        <button class="ghost" aria-label="Next page" :disabled="!page?.hasMore || loading" @click="move(1)">
          <AppIcon name="next" />
        </button>
        <select v-model.number="limit" aria-label="Rows per page" @change="changeLimit">
          <option :value="100">100</option>
          <option :value="200">200</option>
          <option :value="500">500</option>
          <option :value="1000">1,000</option>
        </select>
        <span class="spacer" />
        <button class="ghost" :disabled="!connected || counting" title="Count all rows matching the filter" @click="countRows">
          {{ counting ? 'Counting…' : total === null ? 'Count' : `Total: ${total.toLocaleString()}` }}
        </button>
        <span v-if="page" class="muted">{{ page.elapsedMs }} ms</span>
        <button class="ghost" :class="{ on: calcOpen }" :aria-pressed="calcOpen" title="Calc panel: count, sum, average, min and max of the selected cells" @click="calcOpen = !calcOpen">
          <AppIcon name="sigma" /> Calc
        </button>
        <ExportMenu v-if="page" :columns="page.columns" :rows="page.rows" :name="qualifiedName" :fetch-all="fetchAllRows" />
      </div>
    </template>

    <template v-else>
      <div v-if="error" class="error mono">{{ error }}</div>
      <div v-else-if="section === 'ddl'" class="ddl">
        <div class="ddl-bar">
          <template v-if="ddlDraft === null">
            <button :disabled="!ddlText" @click="copyDdl"><AppIcon name="copy" /> Copy</button>
            <button :disabled="!ddlText" @click="openDdlInEditor"><AppIcon name="sql" /> Open in SQL editor</button>
            <button v-if="view" :disabled="!ddlText || !connected" title="Edit the view's query and save it (CREATE OR REPLACE VIEW)" @click="editView">
              <AppIcon name="edit" /> Edit view
            </button>
          </template>
          <template v-else>
            <button class="primary" :disabled="!connected" @click="saveView"><AppIcon name="commit" /> Save view</button>
            <button @click="ddlDraft = null"><AppIcon name="close" /> Cancel</button>
            <span class="muted hint">The statement is previewed before it runs.</span>
          </template>
        </div>
        <textarea v-if="ddlDraft !== null" v-model="ddlDraft" class="mono ddl-edit" spellcheck="false" aria-label="View definition" />
        <pre v-else class="mono">{{ loading && !ddlText ? 'Loading…' : ddlText }}</pre>
      </div>
      <div v-else-if="section === 'er'" class="grid">
        <div v-if="details" class="er-bar">
          <button title="Editable diagram of the schema with this table and its neighbours (drag, save, export)" @click="openSchemaDiagram">
            <AppIcon name="diagram" /> Open in schema diagram
          </button>
        </div>
        <ErDiagram v-if="details" :details="details" :label="qualifiedName" @open="openRelated" />
        <div v-else class="muted center">{{ loading ? 'Loading…' : 'No data' }}</div>
      </div>
      <div v-else class="detail-area">
        <div v-if="section === 'columns'" class="edit-bar">
          <button :disabled="!details" @click="onAddColumn"><AppIcon name="plus" /> Add column</button>
          <button :disabled="!selectedColumn" @click="onEditColumn"><AppIcon name="edit" /> Edit</button>
          <button class="danger" :disabled="!selectedColumn" @click="onDropColumn"><AppIcon name="trash" /> Drop</button>
          <span class="hint muted">Select a row to edit or drop it</span>
        </div>
        <div v-else-if="section === 'indexes'" class="edit-bar">
          <button :disabled="!details" @click="onCreateIndex"><AppIcon name="plus" /> Create index</button>
          <button class="danger" :disabled="!selectedIndex" @click="onDropIndex"><AppIcon name="trash" /> Drop</button>
        </div>
        <div v-else-if="section === 'constraints'" class="edit-bar">
          <button :disabled="!details" @click="onAddCheck"><AppIcon name="plus" /> Add check</button>
          <button :disabled="!details" @click="onAddUnique"><AppIcon name="plus" /> Add unique</button>
          <button class="danger" :disabled="!selectedConstraint" @click="onDropConstraint"><AppIcon name="trash" /> Drop</button>
          <span class="hint muted">Primary and foreign keys are listed too; edit those in Columns / Foreign keys</span>
        </div>
        <div v-else-if="section === 'triggers'" class="edit-bar">
          <button :disabled="!connected" title="Open a CREATE TRIGGER template in a new script" @click="newTrigger"><AppIcon name="plus" /> New trigger</button>
          <button :disabled="!selectedTrigger" @click="openTriggerSource(selectedTrigger)"><AppIcon name="sql" /> Open source</button>
          <span class="hint muted">Double-click a trigger to open its source</span>
        </div>
        <div v-else-if="section === 'foreignKeys'" class="edit-bar">
          <button :disabled="!details" @click="onAddForeignKey"><AppIcon name="plus" /> Add foreign key</button>
          <button class="danger" :disabled="!selectedForeignKey" @click="onDropForeignKey"><AppIcon name="trash" /> Drop</button>
        </div>
        <div v-if="pendingDdl.length && section !== 'triggers' && section !== 'permissions'" class="pending-ddl" role="region" aria-label="Unsaved structure changes">
          <div class="pending-head">
            <strong>{{ pendingDdl.length }} unsaved change(s)</strong>
            <span class="muted hint">Saved together as one script, previewed first</span>
            <span class="spacer" />
            <button @click="discardStructure"><AppIcon name="close" /> Discard</button>
            <button class="primary" :disabled="!connected" @click="saveStructure"><AppIcon name="commit" /> Save</button>
          </div>
          <ol>
            <li v-for="(c, i) in pendingDdl" :key="i" :class="{ danger: c.danger }">
              <span>{{ c.label }}</span>
              <code class="mono muted">{{ c.statements.join('; ') }}</code>
              <button class="ghost" title="Remove this change" :aria-label="`Remove ${c.label}`" @click="unqueue(i)"><AppIcon name="close" :size="11" /></button>
            </li>
          </ol>
        </div>
        <div class="grid">
        <ResultGrid
          v-if="detailGrid"
          :columns="detailGrid.columns"
          :rows="detailGrid.rows"
          @select="(row) => (detailSelected = row)"
          @dblclick="section === 'triggers' && openTriggerSource(selectedTrigger)"
        />
        <div v-else class="muted center">{{ loading ? 'Loading…' : 'No data' }}</div>
        </div>
      </div>
    </template>
    <EditPreviewDialog ref="preview" />
  </section>
</template>

<style scoped>
.viewer {
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

.spacer {
  flex: 1;
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
  border-bottom: 1px solid var(--border);
}

.filter {
  display: flex;
  gap: 6px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.filter input {
  flex: 1;
}

.grid {
  flex: 1;
  min-height: 0;
}

.edit-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 5px 8px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.edit-bar .sep {
  width: 1px;
  height: 18px;
  background: var(--border);
  margin: 0 2px;
}

.detail-area {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.danger {
  color: var(--danger);
}

.edit-bar .on {
  background: var(--accent-soft);
  border-color: var(--accent);
}

.ddl {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--panel);
}

.ddl-bar {
  display: flex;
  gap: 6px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
}

.ddl-edit {
  flex: 1;
  margin: 0;
  padding: 10px 12px;
  border: 0;
  border-radius: 0;
  resize: none;
  font-size: 12px;
  background: var(--panel);
}

.ddl-bar .hint {
  font-size: 11px;
  align-self: center;
}

.ddl pre {
  flex: 1;
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  font-size: 12px;
  white-space: pre;
}

.edit-bar .hint {
  font-size: 11px;
  margin-left: 4px;
}

.check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.manual {
  color: var(--warn);
  font-weight: 600;
}

.uncommitted {
  padding: 1px 8px;
  border: 1px solid var(--warn);
  border-radius: 4px;
  color: var(--warn);
  font-size: 12px;
  font-weight: 600;
}

.pendingtx {
  border-color: var(--warn);
  color: var(--warn);
}

.error {
  flex: 1;
  margin: 0;
  padding: 10px 12px;
  color: var(--danger);
  white-space: pre-wrap;
  overflow: auto;
}

.center {
  display: grid;
  place-items: center;
  height: 100%;
}

.statusbar .on {
  background: var(--accent-soft);
  border-color: var(--accent);
}

.statusbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 8px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
  min-height: 30px;
}
.pending-ddl {
  border-bottom: 1px solid var(--border);
  background: color-mix(in srgb, var(--warn) 8%, var(--panel));
  font-size: 12px;
}

.pending-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
}

.pending-ddl ol {
  margin: 0;
  padding: 0 8px 6px 28px;
  max-height: 120px;
  overflow: auto;
}

.pending-ddl li {
  padding: 1px 0;
}

.pending-ddl li span {
  margin-right: 8px;
}

.pending-ddl li.danger span {
  color: var(--danger);
}

.pending-ddl code {
  font-size: 11px;
}

.pending-badge {
  align-self: center;
  margin-left: 8px;
  color: var(--warn);
  font-size: 12px;
  font-weight: 600;
}
.er-bar {
  padding: 6px 10px 0;
  background: var(--panel);
}
</style>
