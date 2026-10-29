<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import type { ColumnInfo } from '@/api/types'
import {
  addCheck,
  addColumn,
  addForeignKey,
  addUnique,
  alterColumn,
  createIndex,
  createTable,
  typeSuggestions,
  UnsupportedDdl,
  type ColumnSpec,
  type DdlContext,
  type ReferentialAction,
  type TableRef,
} from '@/lib/ddl'
import AppIcon from './AppIcon.vue'

/**
 * Forms for "Create table", "Add/Edit column", "Create index", "Add foreign key", "Add check" and "Add unique".
 * Each resolves with the DDL statements to run (shown to the user next) or null when cancelled.
 */
export type Mode = 'table' | 'column' | 'index' | 'foreignKey' | 'check' | 'unique'

interface OpenOptions {
  ctx: DdlContext
  table: TableRef
  /** existing columns of `table` (column/index/foreign key forms) */
  columns?: ColumnInfo[]
  /** edit this column instead of adding one */
  column?: ColumnInfo
}

const dialog = ref<HTMLDialogElement | null>(null)
const firstInput = ref<HTMLInputElement | null>(null)
const mode = ref<Mode>('table')
const options = ref<OpenOptions | null>(null)
const error = ref<string | null>(null)
let resolve: ((statements: string[] | null) => void) | undefined

interface ColumnRow extends ColumnSpec {
  key: number
}
let nextKey = 1

const tableForm = reactive({ name: '', columns: [] as ColumnRow[] })
const columnForm = reactive<ColumnSpec>({ name: '', type: '', nullable: true, defaultValue: '' })
const indexForm = reactive({ name: '', unique: false, columns: [] as string[] })
const checkForm = reactive({ name: '', expression: '' })
const uniqueForm = reactive({ name: '', columns: [] as string[] })
const fkForm = reactive({
  name: '',
  columns: [] as string[],
  targetSchema: '',
  targetTable: '',
  targetColumns: '',
  onDelete: '' as ReferentialAction,
  onUpdate: '' as ReferentialAction,
})

const suggestions = computed(() => (options.value ? typeSuggestions(options.value.ctx.dialect) : []))
const existingColumns = computed(() => options.value?.columns?.map((c) => c.name) ?? [])
const actions: ReferentialAction[] = ['', 'CASCADE', 'SET NULL', 'SET DEFAULT', 'RESTRICT', 'NO ACTION']
const title = computed(() => {
  switch (mode.value) {
    case 'table':
      return 'Create table'
    case 'column':
      return options.value?.column ? `Edit column ${options.value.column.name}` : 'Add column'
    case 'index':
      return 'Create index'
    case 'foreignKey':
      return 'Add foreign key'
    case 'check':
      return 'Add check constraint'
    case 'unique':
      return 'Add unique constraint'
  }
})

function spec(c: ColumnInfo): ColumnSpec {
  const sized = c.size > 0 && c.size < 100000 && /char|binary|decimal|numeric/i.test(c.typeName) && !c.typeName.includes('(')
  const type = sized ? `${c.typeName}(${c.size}${c.decimalDigits ? `,${c.decimalDigits}` : ''})` : c.typeName
  return { name: c.name, type, nullable: c.nullable, defaultValue: c.defaultValue ?? '' }
}

function open(next: Mode, opts: OpenOptions): Promise<string[] | null> {
  mode.value = next
  options.value = opts
  error.value = null
  const defaultType = typeSuggestions(opts.ctx.dialect)[0] ?? 'INTEGER'
  if (next === 'table') {
    tableForm.name = ''
    tableForm.columns = [{ key: nextKey++, name: 'id', type: defaultType, nullable: false, defaultValue: '', primaryKey: true }]
  } else if (next === 'column') {
    Object.assign(columnForm, opts.column ? spec(opts.column) : { name: '', type: defaultType, nullable: true, defaultValue: '' })
  } else if (next === 'index') {
    Object.assign(indexForm, { name: `idx_${opts.table.name}_`, unique: false, columns: [] })
  } else if (next === 'check') {
    Object.assign(checkForm, { name: `${opts.table.name}_check`, expression: '' })
  } else if (next === 'unique') {
    Object.assign(uniqueForm, { name: `${opts.table.name}_key`, columns: [] })
  } else {
    Object.assign(fkForm, {
      name: `fk_${opts.table.name}_`,
      columns: [],
      targetSchema: opts.table.schema ?? opts.table.catalog ?? '',
      targetTable: '',
      targetColumns: '',
      onDelete: '',
      onUpdate: '',
    })
  }
  dialog.value?.showModal()
  void nextTick(() => firstInput.value?.focus())
  return new Promise((r) => {
    resolve = r
  })
}

function finish(statements: string[] | null) {
  dialog.value?.close()
  resolve?.(statements)
  resolve = undefined
}

function addRow() {
  tableForm.columns.push({ key: nextKey++, name: '', type: suggestions.value[0] ?? '', nullable: true, defaultValue: '' })
}

function removeRow(key: number) {
  tableForm.columns = tableForm.columns.filter((c) => c.key !== key)
}

function build(): string[] {
  const opts = options.value!
  switch (mode.value) {
    case 'table':
      return createTable(opts.ctx, { ...opts.table, name: tableForm.name.trim() }, tableForm.columns)
    case 'column': {
      const after = { ...columnForm, name: columnForm.name.trim(), type: columnForm.type.trim() }
      if (!opts.column) return addColumn(opts.ctx, opts.table, after)
      const statements = alterColumn(opts.ctx, opts.table, spec(opts.column), after)
      if (statements.length === 0) throw new UnsupportedDdl('Nothing changed')
      return statements
    }
    case 'index':
      return createIndex(opts.ctx, opts.table, indexForm.name, indexForm.columns, indexForm.unique)
    case 'check':
      return addCheck(opts.ctx, opts.table, checkForm.name, checkForm.expression)
    case 'unique':
      return addUnique(opts.ctx, opts.table, uniqueForm.name, uniqueForm.columns)
    case 'foreignKey': {
      const target: TableRef = opts.ctx.supportsSchemas
        ? { catalog: null, schema: fkForm.targetSchema.trim() || null, name: fkForm.targetTable.trim() }
        : { catalog: fkForm.targetSchema.trim() || null, schema: null, name: fkForm.targetTable.trim() }
      if (!target.name) throw new UnsupportedDdl('Referenced table is required')
      const targetColumns = fkForm.targetColumns.split(',').map((c) => c.trim()).filter(Boolean)
      return addForeignKey(opts.ctx, opts.table, fkForm.name, fkForm.columns, target, targetColumns, fkForm.onDelete, fkForm.onUpdate)
    }
  }
}

function submit() {
  try {
    finish(build())
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="schema-dialog-title" @cancel.prevent="finish(null)">
    <form class="form" @submit.prevent="submit">
      <h2 id="schema-dialog-title">{{ title }}</h2>
      <datalist id="schema-types">
        <option v-for="t in suggestions" :key="t" :value="t" />
      </datalist>

      <template v-if="mode === 'table'">
        <label class="field">
          <span>Table name<template v-if="options?.table.schema || options?.table.catalog"> (in {{ options?.table.schema ?? options?.table.catalog }})</template></span>
          <input ref="firstInput" v-model="tableForm.name" required />
        </label>
        <div class="columns">
          <div class="colhead muted">
            <span>Column</span><span>Type</span><span>Default</span><span title="Allows NULL">Null</span><span title="Primary key">PK</span><span />
          </div>
          <div v-for="c in tableForm.columns" :key="c.key" class="colrow">
            <input v-model="c.name" class="mono" aria-label="Column name" required />
            <input v-model="c.type" class="mono" list="schema-types" aria-label="Column type" required />
            <input v-model="c.defaultValue" class="mono" placeholder="—" aria-label="Default expression" />
            <input v-model="c.nullable" type="checkbox" aria-label="Allows NULL" :disabled="c.primaryKey" />
            <input v-model="c.primaryKey" type="checkbox" aria-label="Primary key" @change="c.primaryKey && (c.nullable = false)" />
            <button type="button" class="ghost danger" aria-label="Remove column" @click="removeRow(c.key)">
              <AppIcon name="close" :size="12" />
            </button>
          </div>
          <button type="button" class="add" @click="addRow"><AppIcon name="plus" /> Column</button>
        </div>
      </template>

      <template v-else-if="mode === 'column'">
        <div class="row">
          <label class="field grow">
            <span>Name</span>
            <input ref="firstInput" v-model="columnForm.name" class="mono" required />
          </label>
          <label class="field grow">
            <span>Type</span>
            <input v-model="columnForm.type" class="mono" list="schema-types" required />
          </label>
        </div>
        <label class="field">
          <span>Default (SQL expression, e.g. <code>0</code>, <code>'text'</code>, <code>CURRENT_TIMESTAMP</code>)</span>
          <input v-model="columnForm.defaultValue" class="mono" />
        </label>
        <label class="check"><input v-model="columnForm.nullable" type="checkbox" /> Allows NULL</label>
      </template>

      <template v-else-if="mode === 'index'">
        <label class="field">
          <span>Index name</span>
          <input ref="firstInput" v-model="indexForm.name" class="mono" required />
        </label>
        <fieldset class="picks">
          <legend class="muted">Columns, in order of selection</legend>
          <label v-for="name in existingColumns" :key="name" class="check">
            <input v-model="indexForm.columns" type="checkbox" :value="name" />
            <span class="mono">{{ name }}</span>
            <span v-if="indexForm.columns.includes(name)" class="muted">#{{ indexForm.columns.indexOf(name) + 1 }}</span>
          </label>
        </fieldset>
        <label class="check"><input v-model="indexForm.unique" type="checkbox" /> Unique</label>
      </template>

      <template v-else-if="mode === 'check'">
        <label class="field">
          <span>Constraint name (optional)</span>
          <input ref="firstInput" v-model="checkForm.name" class="mono" />
        </label>
        <label class="field">
          <span>Condition every row must satisfy (SQL), e.g. <code>total &gt;= 0</code>, <code>status IN ('new', 'paid')</code></span>
          <textarea v-model="checkForm.expression" class="mono" rows="3" required />
        </label>
        <p class="muted hint">Columns: <span class="mono">{{ existingColumns.join(', ') }}</span></p>
      </template>

      <template v-else-if="mode === 'unique'">
        <label class="field">
          <span>Constraint name (optional)</span>
          <input ref="firstInput" v-model="uniqueForm.name" class="mono" />
        </label>
        <fieldset class="picks">
          <legend class="muted">Columns whose combined values must be unique, in order</legend>
          <label v-for="name in existingColumns" :key="name" class="check">
            <input v-model="uniqueForm.columns" type="checkbox" :value="name" />
            <span class="mono">{{ name }}</span>
            <span v-if="uniqueForm.columns.includes(name)" class="muted">#{{ uniqueForm.columns.indexOf(name) + 1 }}</span>
          </label>
        </fieldset>
      </template>

      <template v-else>
        <label class="field">
          <span>Constraint name (optional)</span>
          <input ref="firstInput" v-model="fkForm.name" class="mono" />
        </label>
        <fieldset class="picks">
          <legend class="muted">Local columns, in order</legend>
          <label v-for="name in existingColumns" :key="name" class="check">
            <input v-model="fkForm.columns" type="checkbox" :value="name" />
            <span class="mono">{{ name }}</span>
          </label>
        </fieldset>
        <div class="row">
          <label class="field grow">
            <span>{{ options?.ctx.supportsSchemas ? 'Referenced schema' : 'Referenced database' }}</span>
            <input v-model="fkForm.targetSchema" class="mono" />
          </label>
          <label class="field grow">
            <span>Referenced table</span>
            <input v-model="fkForm.targetTable" class="mono" required />
          </label>
        </div>
        <label class="field">
          <span>Referenced columns (comma-separated, same order)</span>
          <input v-model="fkForm.targetColumns" class="mono" placeholder="id" required />
        </label>
        <div class="row">
          <label class="field grow">
            <span>On delete</span>
            <select v-model="fkForm.onDelete">
              <option v-for="a in actions" :key="a" :value="a">{{ a || '(default)' }}</option>
            </select>
          </label>
          <label class="field grow">
            <span>On update</span>
            <select v-model="fkForm.onUpdate">
              <option v-for="a in actions" :key="a" :value="a">{{ a || '(default)' }}</option>
            </select>
          </label>
        </div>
      </template>

      <p v-if="error" class="status err">{{ error }}</p>
      <div class="buttons">
        <button type="button" @click="finish(null)">Cancel</button>
        <button type="submit" class="primary">Preview SQL</button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(680px, calc(100vw - 32px));
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
  margin: 0;
  font-size: 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field > span,
legend {
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

.check {
  display: flex;
  align-items: center;
  gap: 8px;
}

.columns {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.colhead,
.colrow {
  display: grid;
  grid-template-columns: 1.2fr 1.2fr 1fr 40px 40px 30px;
  gap: 6px;
  align-items: center;
}

.colhead {
  font-size: 11px;
}

.colrow input[type='checkbox'] {
  justify-self: center;
}

.add {
  align-self: flex-start;
}

.picks {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 4px 12px;
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  max-height: 220px;
  overflow: auto;
}

.hint {
  margin: 0;
  font-size: 12px;
}

.status {
  margin: 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--panel-2);
}

.status.err {
  color: var(--danger);
}

.buttons {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

code {
  font-family: var(--font-mono);
}
</style>
