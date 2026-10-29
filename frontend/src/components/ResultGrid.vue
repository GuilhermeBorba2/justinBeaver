<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Directive } from 'vue'
import type { CellValue, ResultColumn } from '@/api/types'
import { openContextMenu } from '@/composables/contextMenu'
import { calc, cellsIn, findCells, rangeText, type CellRange } from '@/lib/calc'
import { inList, linesOf, names, sliceOf, withHeaders, type Slice } from '@/lib/copy'
import { EXPORT_FORMATS, render } from '@/lib/export'
import { SEP, tr, type MenuItem } from '@/lib/menu'
import { useUi } from '@/stores/ui'
import type { DisplayCell } from '@/lib/edits'
import AppIcon from './AppIcon.vue'

const ROW_HEIGHT = 24
const OVERSCAN = 8
const ROW_NUMBER_WIDTH = 52

const props = withDefaults(
  defineProps<{
    columns: ResultColumn[]
    /** `undefined` cells render as DEFAULT (unset column of a new row). */
    rows: DisplayCell[][]
    /** Number shown for the first row (pagination offset + 1). */
    startRow?: number
    sortable?: boolean
    sortColumn?: string | null
    sortDesc?: boolean
    /** Double-click / Enter / F2 edits a cell; emits `edit`. */
    editable?: boolean
    isChanged?: (row: number, col: number) => boolean
    deletedRows?: ReadonlySet<number>
    /** Rows at or after this index are new (not yet saved). */
    insertedFrom?: number
    /** Columns that cannot be edited even when the grid is editable. */
    readOnlyColumns?: ReadonlySet<number>
    /** Columns of a foreign key: their cells get a link that emits `follow`. */
    links?: ReadonlySet<number>
    /** Show the Calc panel (aggregates of the selected cells). */
    calcPanel?: boolean
    /** Table name used by "Copy as ▸ SQL INSERT". */
    exportName?: string
  }>(),
  {
    startRow: 1,
    sortable: false,
    sortColumn: null,
    sortDesc: false,
    editable: false,
    isChanged: () => false,
    deletedRows: () => new Set<number>(),
    insertedFrom: Number.POSITIVE_INFINITY,
    readOnlyColumns: () => new Set<number>(),
    links: () => new Set<number>(),
    calcPanel: false,
    exportName: 'table',
  },
)

const ui = useUi()

const emit = defineEmits<{
  sort: [column: string]
  select: [row: number, col: number]
  edit: [row: number, col: number, value: CellValue]
  follow: [row: number, col: number]
}>()

const scroller = ref<HTMLElement | null>(null)
const scrollTop = ref(0)
const viewportHeight = ref(400)
/** The focused cell (keyboard moves, editing); with `anchor` it spans the selected rectangle. */
const selected = ref<{ row: number; col: number } | null>(null)
const anchor = ref<{ row: number; col: number } | null>(null)
let dragging = false
const editing = ref<{ row: number; col: number; draft: string } | null>(null)

// ---- hidden columns ("Hide column" in the header's context menu) ------------------
const hidden = ref<Set<number>>(new Set())
/** Indexes of the columns on screen, in order; selection and search only cover these. */
const shownCols = computed(() => props.columns.flatMap((_, i) => (hidden.value.has(i) ? [] : [i])))

watch(
  () => props.columns.map((c) => c.name).join('\u0000'),
  () => {
    hidden.value = new Set()
    search.value = ''
  },
)

/** Right-click on a column title: hide it, copy its name or values, select it. */
function onHeaderMenu(event: MouseEvent, col: number) {
  const whole = { r1: 0, r2: props.rows.length - 1, c1: col, c2: col }
  openContextMenu(event, [
    { label: tr(`Hide "${props.columns[col]?.name}"`, `Ocultar "${props.columns[col]?.name}"`), disabled: shownCols.value.length <= 1, action: () => hideColumn(col) },
    { label: tr('Show all columns', 'Mostrar todas as colunas'), disabled: hidden.value.size === 0, action: showAllColumns },
    SEP,
    { label: tr('Copy column name', 'Copiar nome da coluna'), icon: 'copy', action: () => void copyText(props.columns[col]!.name, tr('Column name', 'Nome da coluna')) },
    {
      label: tr('Copy column values', 'Copiar valores da coluna'),
      disabled: props.rows.length === 0,
      action: () => void copyText(linesOf(sliceOf(props.columns, props.rows, whole, shownCols.value)), tr('Column', 'Coluna'), props.rows.length),
    },
    {
      label: tr('Copy column as SQL IN list', 'Copiar coluna como lista SQL (IN)'),
      disabled: props.rows.length === 0,
      action: () => void copyText(inList(sliceOf(props.columns, props.rows, whole, shownCols.value)), tr('List', 'Lista'), props.rows.length),
    },
    { label: tr('Select column', 'Selecionar coluna'), disabled: props.rows.length === 0, action: () => selectColumn(col) },
    SEP,
    { label: tr('Find in results… (Ctrl+F)', 'Localizar nos resultados… (Ctrl+F)'), icon: 'search', action: () => void openSearch() },
  ])
}

// ---- copy ----------------------------------------------------------------------------
async function copyText(text: string, what: string, count?: number) {
  try {
    await navigator.clipboard.writeText(text)
    ui.info(count === undefined ? tr(`${what} copied`, `${what} copiado`) : tr(`${what} copied (${count.toLocaleString()} row(s))`, `${what} copiado (${count.toLocaleString()} linha(s))`))
  } catch {
    ui.error(tr('The clipboard is not available', 'A área de transferência não está disponível'))
  }
}

const selectedSlice = (): Slice | null => (range.value ? sliceOf(props.columns, props.rows, range.value, shownCols.value) : null)

/** Everything shown: all rows, the visible columns. */
function wholeSlice(): Slice | null {
  const cols = shownCols.value
  if (props.rows.length === 0 || cols.length === 0) return null
  return sliceOf(props.columns, props.rows, { r1: 0, r2: props.rows.length - 1, c1: 0, c2: props.columns.length - 1 }, cols)
}

/** The right-click menu of cells and row numbers: copy the selection in many forms, select, find. */
/** Cells of the selection that can be changed, as [row, col] pairs. */
function editableCells(): [number, number][] {
  const r = range.value
  if (!props.editable || !r) return []
  const out: [number, number][] = []
  for (let row = r.r1; row <= r.r2; row++) {
    if (props.deletedRows.has(row)) continue
    for (const col of shownCols.value) if (col >= r.c1 && col <= r.c2 && !props.readOnlyColumns.has(col)) out.push([row, col])
  }
  return out
}

function fillCells(value: (row: number, col: number) => CellValue | 'keep') {
  for (const [row, col] of editableCells()) {
    const v = value(row, col)
    if (v !== 'keep') emit('edit', row, col, v)
  }
}

/** DBeaver's "Edit" group of the cell menu: edit, set to NULL, generate UUID, copy the value of the row above / below. */
function editMenu(): (MenuItem | false)[] {
  const cells = editableCells()
  if (cells.length === 0) return []
  const from = (delta: number) => (row: number, col: number): CellValue | 'keep' => {
    const v = props.rows[row + delta]?.[col]
    return v === undefined ? 'keep' : (v as CellValue)
  }
  const first = cells[0]!
  return [
    { label: tr('Edit cell', 'Editar célula'), icon: 'edit', shortcut: 'F2', action: () => startEdit(first[0], first[1]) },
    { label: tr('Set to NULL', 'Definir como NULL'), action: () => fillCells(() => null) },
    { label: tr('Generate UUID', 'Gerar UUID'), action: () => fillCells(() => crypto.randomUUID()) },
    { label: tr('Copy from the row above', 'Copiar da linha acima'), disabled: first[0] === 0, action: () => fillCells(from(-1)) },
    { label: tr('Copy from the row below', 'Copiar da linha abaixo'), disabled: range.value!.r2 >= props.rows.length - 1, action: () => fillCells(from(1)) },
    SEP,
  ]
}

function copyMenu(): (MenuItem | false)[] {
  const slice = selectedSlice()
  if (!slice || !range.value) return []
  const cells = slice.rows.length * slice.columns.length
  const what = tr(`${cells.toLocaleString()} cell(s)`, `${cells.toLocaleString()} célula(s)`)
  const whole = wholeSlice()
  return [
    ...editMenu(),
    { label: tr('Copy', 'Copiar'), icon: 'copy', shortcut: 'Ctrl+C', action: () => void copyText(rangeText(props.rows, range.value!, shownCols.value), what) },
    { label: tr('Copy with column names', 'Copiar com nomes das colunas'), shortcut: 'Ctrl+Shift+C', action: () => void copyText(withHeaders(slice), what) },
    {
      label: tr('Copy as', 'Copiar como'),
      children: EXPORT_FORMATS.map((f) => ({
        label: f.label,
        action: () => void copyText(render(f.id, slice.columns, slice.rows, props.exportName), `${f.label}: ${what}`),
      })),
    },
    {
      label: tr('Advanced copy', 'Cópia avançada'),
      children: [
        { label: tr('Column name(s)', 'Nome(s) da(s) coluna(s)'), action: () => void copyText(names(slice), tr('Names', 'Nomes')) },
        { label: tr('Column name(s) as SQL identifiers', 'Nome(s) da(s) coluna(s) como identificadores SQL'), action: () => void copyText(names(slice, true), tr('Names', 'Nomes')) },
        SEP,
        { label: tr('Values, one per line', 'Valores, um por linha'), action: () => void copyText(linesOf(slice), what) },
        { label: tr('Values as SQL IN list', 'Valores como lista SQL (IN)'), action: () => void copyText(inList(slice), what) },
        SEP,
        {
          label: tr('Whole column(s) of the selection', 'Coluna(s) inteira(s) da seleção'),
          action: () =>
            void copyText(
              linesOf(sliceOf(props.columns, props.rows, { ...range.value!, r1: 0, r2: props.rows.length - 1 }, shownCols.value)),
              tr('Column(s)', 'Coluna(s)'),
              props.rows.length,
            ),
        },
        {
          label: tr('All rows, with column names', 'Todas as linhas, com nomes das colunas'),
          disabled: !whole,
          action: () => whole && void copyText(withHeaders(whole), tr('Result', 'Resultado'), whole.rows.length),
        },
      ],
    },
    SEP,
    {
      label: tr('Select', 'Selecionar'),
      children: [
        { label: tr('Row', 'Linha'), action: () => selectRow(range.value!.r1, range.value!.r2) },
        { label: tr('Column', 'Coluna'), action: () => selectColumn(selected.value!.col, range.value!.c1, range.value!.c2) },
        { label: tr('All', 'Tudo'), shortcut: 'Ctrl+A', action: selectAll },
      ],
    },
    { label: tr('Find in results… (Ctrl+F)', 'Localizar nos resultados… (Ctrl+F)'), icon: 'search', action: () => void openSearch() },
  ]
}

/** Right-click on a cell: a cell outside the selection becomes the selection first, as in DBeaver. */
function onCellMenu(event: MouseEvent, row: number, col: number) {
  if (!inRange(row, col) && !(selected.value?.row === row && selected.value?.col === col)) select(row, col)
  scroller.value?.focus()
  openContextMenu(event, copyMenu())
}

function onRowMenu(event: MouseEvent, row: number) {
  const r = range.value
  if (!r || row < r.r1 || row > r.r2) selectRow(row, row)
  openContextMenu(event, copyMenu())
}

function selectRow(r1: number, r2: number) {
  const cols = shownCols.value
  if (cols.length === 0) return
  anchor.value = { row: r1, col: cols[0]! }
  selected.value = { row: r2, col: cols[cols.length - 1]! }
  emit('select', r1, cols[0]!)
}

/** `from`..`to` columns (just `col` when omitted) over every row. */
function selectColumn(col: number, from = col, to = col) {
  if (props.rows.length === 0) return
  anchor.value = { row: 0, col: from }
  selected.value = { row: props.rows.length - 1, col: to }
  emit('select', 0, col)
}

function hideColumn(col: number) {
  if (shownCols.value.length <= 1) return
  hidden.value = new Set([...hidden.value, col])
  if (selected.value?.col === col) selected.value = null
  anchor.value = null
}

function showAllColumns() {
  hidden.value = new Set()
}

// ---- find in results (Ctrl+F) ------------------------------------------------
const searchOpen = ref(false)
const search = ref('')
const matchIndex = ref(0)
const searchInput = ref<HTMLInputElement | null>(null)
const matches = computed(() => (searchOpen.value ? findCells(props.rows, shownCols.value, search.value) : []))
const matchKeys = computed(() => new Set(matches.value.map((m) => `${m.row}:${m.col}`)))

async function openSearch() {
  searchOpen.value = true
  await nextTick()
  searchInput.value?.focus()
  searchInput.value?.select()
}

function closeSearch() {
  searchOpen.value = false
  scroller.value?.focus()
}

function goToMatch(step: number) {
  const all = matches.value
  if (all.length === 0) return
  matchIndex.value = (matchIndex.value + step + all.length) % all.length
  const m = all[matchIndex.value]!
  anchor.value = null
  select(m.row, m.col)
  reveal(m.row)
  revealColumn(m.col)
}

watch(search, () => {
  matchIndex.value = -1
  goToMatch(1)
})

const vFocus: Directive<HTMLInputElement> = {
  mounted: (el) => {
    el.focus()
    el.select()
  },
}

let observer: ResizeObserver | undefined
onMounted(() => {
  if (!scroller.value) return
  viewportHeight.value = scroller.value.clientHeight
  observer = new ResizeObserver(() => {
    viewportHeight.value = scroller.value?.clientHeight ?? viewportHeight.value
  })
  observer.observe(scroller.value)
})
const stopDrag = () => (dragging = false)
window.addEventListener('mouseup', stopDrag)
onBeforeUnmount(() => {
  observer?.disconnect()
  window.removeEventListener('mouseup', stopDrag)
})

const range = computed<CellRange | null>(() => {
  const a = anchor.value ?? selected.value
  const b = selected.value
  if (!a || !b) return null
  return { r1: Math.min(a.row, b.row), r2: Math.max(a.row, b.row), c1: Math.min(a.col, b.col), c2: Math.max(a.col, b.col) }
})

const multi = computed(() => !!range.value && (range.value.r1 !== range.value.r2 || range.value.c1 !== range.value.c2))

function inRange(row: number, col: number): boolean {
  const r = range.value
  return !!r && multi.value && row >= r.r1 && row <= r.r2 && col >= r.c1 && col <= r.c2
}

const stats = computed(() => (props.calcPanel && range.value ? calc(cellsIn(props.rows, range.value, shownCols.value)) : null))
const rangeSize = computed(() => (range.value ? `${range.value.r2 - range.value.r1 + 1} × ${range.value.c2 - range.value.c1 + 1}` : ''))

function show(value: string | number | null): string {
  if (value === null) return '—'
  return typeof value === 'number' ? value.toLocaleString(undefined, { maximumFractionDigits: 10 }) : value
}

function text(value: DisplayCell): string {
  if (value === undefined) return 'DEFAULT'
  return value === null ? 'NULL' : String(value)
}

const widths = computed(() =>
  props.columns.map((column, index) => {
    let longest = column.name.length + (props.sortable ? 3 : 0)
    for (const row of props.rows.slice(0, 100)) {
      const value = row[index]
      if (value !== null && value !== undefined) longest = Math.max(longest, String(value).length)
    }
    return Math.min(480, Math.max(80, longest * 7.6 + 24))
  }),
)

const template = computed(() => `${ROW_NUMBER_WIDTH}px ${shownCols.value.map((c) => `${widths.value[c]}px`).join(' ')}`)
const totalWidth = computed(() => ROW_NUMBER_WIDTH + shownCols.value.reduce((a, c) => a + widths.value[c]!, 0))

/** Scroll horizontally so a column is on screen (search jumps). */
function revealColumn(col: number) {
  const el = scroller.value
  if (!el) return
  let left = ROW_NUMBER_WIDTH
  for (const c of shownCols.value) {
    if (c === col) break
    left += widths.value[c]!
  }
  const right = left + widths.value[col]!
  if (left - ROW_NUMBER_WIDTH < el.scrollLeft) el.scrollLeft = left - ROW_NUMBER_WIDTH
  else if (right > el.scrollLeft + el.clientWidth) el.scrollLeft = right - el.clientWidth
}

const first = computed(() => Math.max(0, Math.floor(scrollTop.value / ROW_HEIGHT) - OVERSCAN))
const last = computed(() =>
  Math.min(props.rows.length, Math.ceil((scrollTop.value + viewportHeight.value) / ROW_HEIGHT) + OVERSCAN),
)
const visible = computed(() =>
  props.rows.slice(first.value, last.value).map((cells, offset) => ({ cells, index: first.value + offset })),
)

function onScroll(event: Event) {
  scrollTop.value = (event.target as HTMLElement).scrollTop
}

function select(row: number, col: number, extend = false) {
  if (!extend || !selected.value) anchor.value = { row, col }
  else if (!anchor.value) anchor.value = selected.value
  selected.value = { row, col }
  emit('select', row, col)
}

function onCellDown(event: MouseEvent, row: number, col: number) {
  if (event.button !== 0 || editing.value?.row === row && editing.value?.col === col) return
  select(row, col, event.shiftKey)
  dragging = true
}

function onCellEnter(row: number, col: number) {
  if (dragging && selected.value && (selected.value.row !== row || selected.value.col !== col)) select(row, col, true)
}

/** The row number selects the whole row (Shift extends over rows). */
function onRowDown(event: MouseEvent, row: number) {
  const cols = shownCols.value
  if (event.button !== 0 || cols.length === 0) return
  const firstCol = cols[0]!
  const lastCol = cols[cols.length - 1]!
  if (event.shiftKey && anchor.value) {
    anchor.value = { row: anchor.value.row, col: firstCol }
  } else {
    anchor.value = { row, col: firstCol }
  }
  selected.value = { row, col: lastCol }
  emit('select', row, firstCol)
  dragging = true
  scroller.value?.focus()
}

function selectAll() {
  const cols = shownCols.value
  if (props.rows.length === 0 || cols.length === 0) return
  anchor.value = { row: 0, col: cols[0]! }
  selected.value = { row: props.rows.length - 1, col: cols[cols.length - 1]! }
}

function startEdit(row: number, col: number) {
  if (!props.editable || props.deletedRows.has(row) || props.readOnlyColumns.has(col)) return
  const value = props.rows[row]?.[col]
  editing.value = { row, col, draft: value === null || value === undefined ? '' : String(value) }
}

function finishEdit(value: CellValue | 'keep') {
  const current = editing.value
  if (!current) return
  editing.value = null
  if (value !== 'keep') emit('edit', current.row, current.col, value)
  scroller.value?.focus()
}

/** Keep the selected cell visible after keyboard moves. */
function reveal(row: number) {
  const el = scroller.value
  if (!el) return
  const top = row * ROW_HEIGHT
  const headerHeight = ROW_HEIGHT
  if (top < el.scrollTop) el.scrollTop = top
  else if (top + ROW_HEIGHT > el.scrollTop + el.clientHeight - headerHeight) {
    el.scrollTop = top + ROW_HEIGHT - el.clientHeight + headerHeight
  }
}

async function onKeydown(event: KeyboardEvent) {
  if (editing.value) return
  const sel = selected.value
  if ((event.ctrlKey || event.metaKey) && event.key === 'f') {
    event.preventDefault()
    void openSearch()
    return
  }
  if ((event.ctrlKey || event.metaKey) && event.key === 'a') {
    event.preventDefault()
    selectAll()
    return
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'c' && sel && range.value && !window.getSelection()?.toString()) {
    event.preventDefault()
    const slice = selectedSlice()
    if (!slice) return
    const cells = slice.rows.length * slice.columns.length
    const what = tr(`${cells.toLocaleString()} cell(s)`, `${cells.toLocaleString()} célula(s)`)
    // Ctrl+Shift+C adds the column names
    await copyText(event.shiftKey ? withHeaders(slice) : rangeText(props.rows, range.value, shownCols.value), what)
    return
  }
  if (!sel) return
  const moves: Record<string, [number, number]> = {
    ArrowUp: [-1, 0],
    ArrowDown: [1, 0],
    ArrowLeft: [0, -1],
    ArrowRight: [0, 1],
  }
  const move = moves[event.key]
  if (move) {
    event.preventDefault()
    const cols = shownCols.value
    const row = Math.min(props.rows.length - 1, Math.max(0, sel.row + move[0]))
    const position = Math.min(cols.length - 1, Math.max(0, cols.indexOf(sel.col) + move[1]))
    const col = cols[position] ?? sel.col
    select(row, col, event.shiftKey)
    reveal(row)
    revealColumn(col)
  } else if (event.key === 'Enter' || event.key === 'F2') {
    event.preventDefault()
    startEdit(sel.row, sel.col)
  }
}

function isSorted(column: ResultColumn): boolean {
  return props.sortColumn === column.name
}
</script>

<template>
  <div class="result-grid">
  <div v-if="columns.length === 0" class="empty muted">No result set</div>
  <div v-else class="grid-area">
  <form v-if="searchOpen" class="search" role="search" @submit.prevent="goToMatch(1)">
    <AppIcon name="search" :size="12" />
    <input
      ref="searchInput"
      v-model="search"
      placeholder="Find in results"
      aria-label="Find in results"
      @keydown.esc.prevent="closeSearch"
      @keydown.enter.shift.prevent="goToMatch(-1)"
    />
    <span class="muted count">{{ search ? `${matches.length ? matchIndex + 1 : 0}/${matches.length}${matches.length >= 10000 ? '+' : ''}` : '' }}</span>
    <button type="button" class="ghost" title="Previous (Shift+Enter)" aria-label="Previous match" @click="goToMatch(-1)"><AppIcon name="prev" :size="12" /></button>
    <button type="button" class="ghost" title="Next (Enter)" aria-label="Next match" @click="goToMatch(1)"><AppIcon name="next" :size="12" /></button>
    <button type="button" class="ghost" title="Close (Esc)" aria-label="Close search" @click="closeSearch"><AppIcon name="close" :size="12" /></button>
  </form>
  <div ref="scroller" class="scroller" tabindex="0" @scroll="onScroll" @keydown="onKeydown">
    <div class="header" :style="{ gridTemplateColumns: template, width: totalWidth + 'px' }">
      <div class="cell rownum">
        <button v-if="hidden.size" class="unhide" :title="`Show ${hidden.size} hidden column(s)`" @click="showAllColumns">+{{ hidden.size }}</button>
      </div>
      <component
        :is="sortable ? 'button' : 'div'"
        v-for="c in shownCols"
        :key="columns[c]!.name + c"
        class="cell head"
        :class="[columns[c]!.kind, { sortable }]"
        :title="`${columns[c]!.name} · ${columns[c]!.typeName} (right-click for more)`"
        @click="sortable && emit('sort', columns[c]!.name)"
        @contextmenu="onHeaderMenu($event, c)"
      >
        <span class="name">{{ columns[c]!.name }}</span>
        <span v-if="isSorted(columns[c]!)" class="arrow" :class="{ desc: sortDesc }">
          <AppIcon name="chevron" :size="10" />
        </span>
      </component>
    </div>
    <div class="body" :style="{ height: rows.length * ROW_HEIGHT + 'px', width: totalWidth + 'px' }">
      <div
        v-for="{ cells, index } in visible"
        :key="index"
        class="row"
        :class="{ alt: index % 2 === 1, deleted: deletedRows.has(index), inserted: index >= insertedFrom }"
        :style="{ gridTemplateColumns: template, transform: `translateY(${index * ROW_HEIGHT}px)` }"
      >
        <div class="cell rownum" @mousedown.prevent="onRowDown($event, index)" @contextmenu="onRowMenu($event, index)">{{ index >= insertedFrom ? '+' : startRow + index }}</div>
        <div
          v-for="col in shownCols"
          :key="col"
          class="cell data"
          :class="[
            columns[col]!.kind,
            {
              null: cells[col] === null,
              unset: cells[col] === undefined,
              changed: isChanged(index, col),
              locked: editable && readOnlyColumns.has(col),
              selected: selected?.row === index && selected?.col === col,
              inrange: inRange(index, col),
              match: searchOpen && matchKeys.has(`${index}:${col}`),
              editing: editing?.row === index && editing?.col === col,
              link: links.has(col) && cells[col] !== null && cells[col] !== undefined,
            },
          ]"
          :title="text(cells[col])"
          @mousedown="onCellDown($event, index, col)"
          @mouseenter="onCellEnter(index, col)"
          @dblclick="startEdit(index, col)"
          @contextmenu="onCellMenu($event, index, col)"
        >
          <template v-if="editing?.row === index && editing?.col === col">
            <input
              v-model="editing.draft"
              v-focus
              class="editor mono"
              :aria-label="`Edit ${columns[col]!.name}`"
              @keydown.stop
              @keydown.enter.prevent="finishEdit(editing.draft)"
              @keydown.esc.prevent="finishEdit('keep')"
              @keydown.tab.prevent="finishEdit(editing.draft)"
              @blur="finishEdit(editing?.draft ?? 'keep')"
            />
            <button class="null-btn" title="Set NULL" @mousedown.prevent="finishEdit(null)">NULL</button>
          </template>
          <template v-else>
            <span class="value">{{ text(cells[col]) }}</span>
            <button
              v-if="links.has(col) && cells[col] !== null && cells[col] !== undefined"
              class="follow"
              tabindex="-1"
              title="Open the referenced row (foreign key)"
              :aria-label="`Open the row referenced by ${columns[col]!.name}`"
              @mousedown.stop.prevent
              @click.stop="emit('follow', index, col)"
            >
              <AppIcon name="link" :size="11" />
            </button>
          </template>
        </div>
      </div>
    </div>
  </div>
  </div>
  <aside v-if="calcPanel && columns.length" class="calc" aria-label="Calc">
    <div class="calc-head">Calc <span class="muted">{{ rangeSize }}</span></div>
    <dl v-if="stats">
      <dt>Count</dt><dd>{{ (stats.cells - stats.nulls).toLocaleString() }}</dd>
      <dt>Nulls</dt><dd>{{ stats.nulls.toLocaleString() }}</dd>
      <dt>Distinct</dt><dd>{{ stats.distinct.toLocaleString() }}</dd>
      <dt>Sum</dt><dd :title="stats.exactSum ?? undefined">{{ stats.exactSum ?? show(stats.sum) }}</dd>
      <dt>Average</dt><dd>{{ show(stats.avg) }}</dd>
      <dt>Min</dt><dd :title="String(stats.min ?? '')">{{ show(stats.min) }}</dd>
      <dt>Max</dt><dd :title="String(stats.max ?? '')">{{ show(stats.max) }}</dd>
      <dt>Numbers</dt><dd>{{ stats.numbers.toLocaleString() }}</dd>
    </dl>
    <p v-else class="muted hint">Select cells: drag, Shift+click, Shift+arrows, click a row number, or Ctrl+A.</p>
  </aside>
  </div>
</template>

<style scoped>
.result-grid {
  display: flex;
  height: 100%;
  min-height: 0;
}

.grid-area {
  position: relative;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  height: 100%;
}

.search {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 6px;
  border-bottom: 1px solid var(--border);
  background: var(--panel-2);
}

.search input {
  flex: 1;
  max-width: 280px;
  height: 22px;
}

.search .count {
  min-width: 48px;
  font-size: 11px;
}

.unhide {
  height: 18px;
  padding: 0 4px;
  font-size: 10px;
}

.data.match {
  background: color-mix(in srgb, var(--warn) 30%, transparent);
}

.scroller {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: auto;
  background: var(--panel);
  outline: none;
  font-size: 12px;
}

.empty {
  flex: 1;
  display: grid;
  place-items: center;
  height: 100%;
}

.header {
  position: sticky;
  top: 0;
  z-index: 2;
  display: grid;
  background: var(--panel-2);
  border-bottom: 1px solid var(--border);
}

.body {
  position: relative;
  user-select: none;
}

.row {
  position: absolute;
  left: 0;
  display: grid;
  height: 24px;
  width: 100%;
}

.row.alt {
  background: var(--row-alt);
}

.row.deleted .data {
  text-decoration: line-through;
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 10%, transparent);
}

.row.inserted .data {
  background: color-mix(in srgb, var(--ok) 10%, transparent);
}

.cell {
  height: 24px;
  line-height: 23px;
  padding: 0 8px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  border-right: 1px solid var(--border);
  border-bottom: 1px solid transparent;
}

.header .cell {
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 4px;
}

.head.sortable {
  border-radius: 0;
  border-top: 0;
  border-left: 0;
  border-bottom: 0;
  background: transparent;
  text-align: left;
}

.head.sortable:hover {
  background: var(--accent-soft);
}

.head .name {
  overflow: hidden;
  text-overflow: ellipsis;
}

.arrow {
  display: inline-flex;
  transform: rotate(-90deg);
  color: var(--accent);
}

.arrow.desc {
  transform: rotate(90deg);
}

.rownum {
  cursor: pointer;
  color: var(--muted);
  text-align: right;
  position: sticky;
  left: 0;
  background: var(--panel-2);
  z-index: 1;
}

.data {
  font-family: var(--font-mono);
  cursor: default;
}

.data.number {
  text-align: right;
}

.head.number {
  justify-content: flex-end;
}

.data.null,
.data.unset {
  color: var(--muted);
  font-style: italic;
}

.data.changed {
  background: color-mix(in srgb, var(--warn) 22%, transparent);
  font-weight: 600;
}

.data.locked {
  opacity: 0.7;
}

.data.selected {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
  background: var(--selected);
}

.data.inrange {
  background: var(--selected);
}

.data.link {
  display: flex;
  align-items: center;
  gap: 4px;
}

.data.link .value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.follow {
  flex: none;
  display: inline-flex;
  height: 16px;
  padding: 0 2px;
  border: 0;
  background: transparent;
  color: var(--accent);
  opacity: 0.55;
}

.follow:hover {
  opacity: 1;
  background: var(--accent-soft);
}

.data.editing {
  display: flex;
  padding: 0;
  overflow: visible;
}

.editor {
  flex: 1;
  min-width: 0;
  height: 24px;
  border-radius: 0;
  border: 2px solid var(--accent);
  padding: 0 6px;
  font-size: 12px;
  text-align: inherit;
}

.null-btn {
  flex: none;
  height: 24px;
  padding: 0 6px;
  border-radius: 0;
  font-size: 10px;
  font-family: var(--font-ui);
}
.calc {
  flex: none;
  width: 200px;
  overflow: auto;
  border-left: 1px solid var(--border);
  background: var(--panel);
  font-size: 12px;
}

.calc-head {
  display: flex;
  justify-content: space-between;
  padding: 5px 8px;
  border-bottom: 1px solid var(--border);
  background: var(--panel-2);
  font-weight: 600;
}

.calc dl {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 3px 10px;
  margin: 0;
  padding: 8px;
}

.calc dt {
  color: var(--muted);
}

.calc dd {
  margin: 0;
  overflow: hidden;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
}

.calc .hint {
  margin: 0;
  padding: 8px;
}
</style>
