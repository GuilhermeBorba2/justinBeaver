<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { TableDetails, TableInfo } from '@/api/types'
import { openContextMenu } from '@/composables/contextMenu'
import { useSchemaService } from '@/composables/schema'
import { layeredLayout } from '@/lib/erLayout'
import { selectAll } from '@/lib/generateSql'
import { tr, SEP } from '@/lib/menu'
import { useConnections } from '@/stores/connections'
import { usePrefs } from '@/stores/prefs'
import { useUi } from '@/stores/ui'
import { useWorkspace, type DiagramTab } from '@/stores/workspace'
import AppIcon from './AppIcon.vue'

/**
 * Editable ER diagram of a schema (DBeaver's "ER Diagram" editor): pick the tables, drag the boxes, and the
 * layout is saved with the workspace. Lines join a foreign key column to the column it references.
 * Export as SVG or PNG; double-click a table to open it.
 */
const props = defineProps<{ tab: DiagramTab; active: boolean }>()

const connections = useConnections()
const workspace = useWorkspace()
const prefs = usePrefs()
const ui = useUi()
const schemaService = useSchemaService()

const BOX_W = 220
const HEADER = 26
const ROW = 18
const MAX_ROWS = 30
const MAX_TABLES = 80

/** Everything the server listed; `all` is what the diagram offers (without bookkeeping tables, and views only when asked). */
const rawTables = ref<TableInfo[]>([])
const details = ref<Record<string, TableDetails>>({})
const loading = ref(false)
const filter = ref('')
const zoom = ref(1)
const svg = ref<SVGSVGElement | null>(null)
const canvas = ref<HTMLElement | null>(null)
const includeViews = ref(false)
/** The table clicked (kept highlighted with its links), the one under the pointer, and the one drawn on top. */
const selected = ref<string | null>(null)
const hovered = ref<string | null>(null)
const front = ref<string | null>(null)
const ZOOM_MIN = 0.2
const ZOOM_MAX = 2
const connected = computed(() => connections.isConnected(props.tab.connectionId))
const scope = computed(() => ({ catalog: props.tab.catalog, schema: props.tab.schema }))

/** Colors resolved from the theme, written as attributes so an exported file looks the same. */
const colors = ref(readColors())
function readColors() {
  const css = getComputedStyle(document.documentElement)
  const v = (name: string) => css.getPropertyValue(name).trim() || '#888'
  return { panel: v('--panel'), panel2: v('--panel-2'), border: v('--border'), text: v('--text'), muted: v('--muted'), accent: v('--accent'), soft: v('--accent-soft') }
}
watch(() => prefs.dark, () => setTimeout(() => (colors.value = readColors()), 0))

async function loadTables() {
  if (!connected.value) return
  rawTables.value = (await ui.guard(() => api.tables(props.tab.connectionId, scope.value))) ?? []
}

const all = computed(() =>
  rawTables.value.filter((t) => {
    const type = t.type.toUpperCase()
    const keep = props.tab.tables.includes(t.name)
    // the database's own bookkeeping tables are not part of the model
    if (type.includes('SYSTEM') && !keep) return false
    return includeViews.value || !type.includes('VIEW') || keep
  }),
)

async function loadDetails() {
  if (!connected.value) return
  const missing = props.tab.tables.filter((t) => !details.value[t])
  if (missing.length === 0) {
    // tables ticked again after being hidden: their details are cached, they only need a place
    placeNew()
    return
  }
  loading.value = true
  try {
    const loaded = await Promise.all(missing.map((t) => api.tableDetails(props.tab.connectionId, scope.value, t).catch(() => null)))
    const next = { ...details.value }
    loaded.forEach((d, i) => {
      if (d) next[missing[i]!] = d
    })
    details.value = next
    placeNew()
  } finally {
    loading.value = false
  }
}

const boxHeight = (t: string) => HEADER + Math.min(MAX_ROWS, details.value[t]?.columns.length ?? 0) * ROW + 8 + (overflow(t) ? ROW : 0)
const overflow = (t: string) => Math.max(0, (details.value[t]?.columns.length ?? 0) - MAX_ROWS)

/** Boxes of the layout: name, height and the shown tables each one references. */
function layoutNodes(names: string[]) {
  const on = new Set(names)
  return names.map((name) => ({
    name,
    height: boxHeight(name),
    refs: [...new Set((details.value[name]?.foreignKeys ?? []).map((k) => k.refTable))].filter((r) => on.has(r)),
  }))
}

/** The first tables get a layered layout; tables added later go in a grid below what is already on the canvas. */
function placeNew() {
  const positions = props.tab.positions
  const ready = props.tab.tables.filter((t) => details.value[t])
  const unplaced = ready.filter((t) => !positions[t])
  if (!unplaced.length) return
  if (unplaced.length === ready.length) {
    Object.assign(positions, layeredLayout(layoutNodes(ready), { boxWidth: BOX_W }))
    void nextTick(fit)
    return
  }
  const below = Math.max(20, ...ready.filter((t) => positions[t]).map((t) => positions[t]!.y + boxHeight(t) + 30))
  const cols = Math.max(3, Math.floor(Math.sqrt(unplaced.length)))
  let rowTop = below
  for (let i = 0; i < unplaced.length; i += cols) {
    const row = unplaced.slice(i, i + cols)
    row.forEach((t, k) => (positions[t] = { x: 20 + k * (BOX_W + 80), y: rowTop }))
    rowTop += Math.max(...row.map(boxHeight)) + 30
  }
}

function toggle(table: string) {
  const list = props.tab.tables
  const at = list.indexOf(table)
  if (at >= 0) {
    list.splice(at, 1)
    delete props.tab.positions[table]
  } else if (list.length >= MAX_TABLES) {
    ui.error(`A diagram holds up to ${MAX_TABLES} tables`)
  } else {
    list.push(table)
  }
}

/** Add the tables linked by foreign keys to `only` (or to every shown table), in the same schema. */
function addRelated(only?: string) {
  const names = new Set(all.value.map((t) => t.name))
  let added = 0
  for (const t of only ? [only] : [...props.tab.tables]) {
    const d = details.value[t]
    if (!d) continue
    for (const k of [...d.foreignKeys, ...d.referencedBy]) {
      const same = (k.refSchema ?? null) === props.tab.schema || !k.refSchema
      if (same && names.has(k.refTable) && !props.tab.tables.includes(k.refTable) && props.tab.tables.length < MAX_TABLES) {
        props.tab.tables.push(k.refTable)
        added++
      }
    }
  }
  if (added === 0) ui.info(tr('No more linked tables to add', 'Não há mais tabelas ligadas para adicionar'))
}

function hide(table: string) {
  toggle(table)
  if (selected.value === table) selected.value = null
}

function addAllListed() {
  for (const t of listed.value) {
    if (props.tab.tables.length >= MAX_TABLES) {
      ui.error(`A diagram holds up to ${MAX_TABLES} tables`)
      break
    }
    if (!props.tab.tables.includes(t.name)) props.tab.tables.push(t.name)
  }
}

function clearAll() {
  props.tab.tables.splice(0)
  for (const key of Object.keys(props.tab.positions)) delete props.tab.positions[key]
  selected.value = null
}

const shown = computed(() => props.tab.tables.filter((t) => details.value[t] && props.tab.positions[t]))
const listed = computed(() => {
  const needle = filter.value.toLowerCase()
  return all.value.filter((t) => !needle || t.name.toLowerCase().includes(needle))
})

function rowY(table: string, column: string) {
  const p = props.tab.positions[table]!
  const index = details.value[table]!.columns.findIndex((c) => c.name === column)
  const i = index < 0 || index >= MAX_ROWS ? -1 : index
  return i < 0 ? p.y + HEADER / 2 : p.y + HEADER + i * ROW + ROW / 2 + 2
}

interface Edge {
  key: string
  d: string
  from: string
  to: string
}

const edges = computed<Edge[]>(() => {
  const out: Edge[] = []
  const on = new Set(shown.value)
  for (const t of shown.value) {
    for (const k of details.value[t]!.foreignKeys) {
      if (!on.has(k.refTable)) continue
      const a = props.tab.positions[t]!
      const b = props.tab.positions[k.refTable]!
      const y1 = rowY(t, k.column)
      const y2 = rowY(k.refTable, k.refColumn)
      let x1: number
      let x2: number
      if (k.refTable === t) {
        x1 = a.x + BOX_W
        x2 = a.x + BOX_W
        out.push({ key: `${t}.${k.name}.${k.column}`, d: `M${x1},${y1} C${x1 + 50},${y1} ${x2 + 50},${y2} ${x2},${y2}`, from: t, to: t })
        continue
      }
      // leave from the side facing the other table
      if (b.x > a.x + BOX_W / 2) {
        x1 = a.x + BOX_W
        x2 = b.x
      } else {
        x1 = a.x
        x2 = b.x + BOX_W
      }
      const bend = Math.max(40, Math.abs(x2 - x1) / 2)
      const dir = x2 > x1 ? 1 : -1
      out.push({ key: `${t}.${k.name}.${k.column}`, d: `M${x1},${y1} C${x1 + dir * bend},${y1} ${x2 - dir * bend},${y2} ${x2},${y2}`, from: t, to: k.refTable })
    }
  }
  return out
})

const active = computed(() => hovered.value ?? selected.value)
/** Tables joined by a line to each table. */
const linked = computed(() => {
  const map = new Map<string, Set<string>>()
  for (const e of edges.value) {
    if (!map.has(e.from)) map.set(e.from, new Set())
    if (!map.has(e.to)) map.set(e.to, new Set())
    map.get(e.from)!.add(e.to)
    map.get(e.to)!.add(e.from)
  }
  return map
})
const isDim = (t: string) => active.value !== null && t !== active.value && !linked.value.get(active.value)?.has(t)
const isHot = (e: Edge) => active.value !== null && (e.from === active.value || e.to === active.value)
/** The clicked box is drawn last, on top; the saved order of `tab.tables` is not touched. */
const drawn = computed(() => (front.value && shown.value.includes(front.value) ? [...shown.value.filter((t) => t !== front.value), front.value] : shown.value))

const size = computed(() => {
  let w = 600
  let h = 400
  for (const t of shown.value) {
    const p = props.tab.positions[t]!
    w = Math.max(w, p.x + BOX_W + 40)
    h = Math.max(h, p.y + boxHeight(t) + 40)
  }
  return { w, h }
})

// ---- dragging, panning, zoom ------------------------------------------------------
let drag: { table: string; dx: number; dy: number } | null = null
let pan: { x: number; y: number; left: number; top: number } | null = null

function point(event: PointerEvent) {
  const rect = svg.value!.getBoundingClientRect()
  return { x: (event.clientX - rect.left) / zoom.value, y: (event.clientY - rect.top) / zoom.value }
}

/** Keep receiving the pointer while it leaves the box (a synthetic event has no pointer to capture). */
function capture(event: PointerEvent) {
  try {
    svg.value?.setPointerCapture(event.pointerId)
  } catch {
    // not an active pointer: dragging still works while it stays over the diagram
  }
}

function startDrag(event: PointerEvent, table: string) {
  if (event.button !== 0) return
  const p = point(event)
  const at = props.tab.positions[table]!
  drag = { table, dx: p.x - at.x, dy: p.y - at.y }
  selected.value = table
  front.value = table
  capture(event)
}

/** Dragging the empty canvas scrolls it, and clears the selection. */
function startPan(event: PointerEvent) {
  if (event.button !== 0 || !canvas.value) return
  selected.value = null
  pan = { x: event.clientX, y: event.clientY, left: canvas.value.scrollLeft, top: canvas.value.scrollTop }
  capture(event)
}

function moveDrag(event: PointerEvent) {
  if (drag) {
    const p = point(event)
    props.tab.positions[drag.table] = { x: Math.max(0, Math.round(p.x - drag.dx)), y: Math.max(0, Math.round(p.y - drag.dy)) }
  } else if (pan && canvas.value) {
    canvas.value.scrollLeft = pan.left - (event.clientX - pan.x)
    canvas.value.scrollTop = pan.top - (event.clientY - pan.y)
  }
}

function endDrag() {
  drag = null
  pan = null
}

const clampZoom = (z: number) => Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, +z.toFixed(2)))

/** Zoom keeping the point under `at` (canvas coordinates) where it is. */
function zoomTo(next: number, at?: { x: number; y: number }) {
  const el = canvas.value
  const old = zoom.value
  const value = clampZoom(next)
  if (value === old) return
  zoom.value = value
  if (!el) return
  const around = at ?? { x: el.clientWidth / 2, y: el.clientHeight / 2 }
  void nextTick(() => {
    el.scrollLeft = ((el.scrollLeft + around.x) * value) / old - around.x
    el.scrollTop = ((el.scrollTop + around.y) * value) / old - around.y
  })
}

/** Ctrl + wheel zooms (the plain wheel scrolls). */
function onWheel(event: WheelEvent) {
  if (!event.ctrlKey && !event.metaKey) return
  event.preventDefault()
  const rect = canvas.value!.getBoundingClientRect()
  zoomTo(zoom.value * (event.deltaY < 0 ? 1.1 : 1 / 1.1), { x: event.clientX - rect.left, y: event.clientY - rect.top })
}

/** Zoom so that the whole diagram is visible. */
function fit() {
  const el = canvas.value
  if (!el || !shown.value.length) return
  zoom.value = clampZoom(Math.min((el.clientWidth - 4) / size.value.w, (el.clientHeight - 4) / size.value.h, 1))
  el.scrollLeft = 0
  el.scrollTop = 0
}

function onKey(event: KeyboardEvent) {
  if (event.target instanceof HTMLInputElement) return
  if ((event.key === 'Delete' || event.key === 'Backspace') && selected.value) {
    event.preventDefault()
    hide(selected.value)
  } else if (event.key === 'Escape') selected.value = null
  else if (event.key === '+' || event.key === '=') zoomTo(zoom.value + 0.1)
  else if (event.key === '-') zoomTo(zoom.value - 0.1)
  else if (event.key === '0') fit()
}

function open(table: string) {
  workspace.openTable(props.tab.connectionId, props.tab.catalog, props.tab.schema, table)
}

/** Layered layout again: referenced tables on the left, the ones that point at them to the right. */
function autoLayout() {
  const names = [...shown.value]
  for (const t of names) delete props.tab.positions[t]
  Object.assign(props.tab.positions, layeredLayout(layoutNodes(names), { boxWidth: BOX_W }))
  void nextTick(fit)
}

// ---- right-click menus -----------------------------------------------------------
async function newSelect(table: string) {
  const ctx = await ui.guard(() => schemaService.context(props.tab.connectionId))
  if (!ctx) return
  const sql = selectAll(ctx, { catalog: props.tab.catalog, schema: props.tab.schema, name: table })
  workspace.openSql(props.tab.connectionId, connections.labelOf(props.tab.connectionId), sql)
}

function boxMenu(event: MouseEvent, table: string) {
  selected.value = table
  openContextMenu(event, [
    { label: tr('Open data', 'Abrir dados'), icon: 'table', action: () => open(table) },
    { label: tr('New SQL script (SELECT)', 'Novo script SQL (SELECT)'), icon: 'sql', action: () => void newSelect(table) },
    SEP,
    { label: tr('Add linked tables', 'Adicionar tabelas ligadas'), icon: 'link', action: () => addRelated(table) },
    { label: tr('Remove from diagram', 'Remover do diagrama'), icon: 'close', shortcut: 'Del', action: () => hide(table) },
    SEP,
    { label: tr('Copy name', 'Copiar nome'), icon: 'copy', action: () => void navigator.clipboard.writeText(table).catch(() => undefined) },
  ])
}

function canvasMenu(event: MouseEvent) {
  openContextMenu(event, [
    { label: tr('Auto layout', 'Organizar automaticamente'), icon: 'diagram', disabled: !shown.value.length, action: autoLayout },
    { label: tr('Fit to window', 'Ajustar à janela'), disabled: !shown.value.length, action: fit },
    { label: tr('Add linked tables', 'Adicionar tabelas ligadas'), icon: 'link', disabled: !shown.value.length, action: () => addRelated() },
    SEP,
    { label: tr('Export SVG', 'Exportar SVG'), icon: 'download', disabled: !shown.value.length, action: exportSvg },
    { label: tr('Export PNG', 'Exportar PNG'), icon: 'download', disabled: !shown.value.length, action: exportPng },
  ])
}

// ---- export ----------------------------------------------------------------------
function svgText(): string {
  const clone = svg.value!.cloneNode(true) as SVGSVGElement
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  clone.setAttribute('width', String(size.value.w))
  clone.setAttribute('height', String(size.value.h))
  clone.removeAttribute('style')
  return new XMLSerializer().serializeToString(clone)
}

function download(name: string, blob: Blob) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

const fileBase = computed(() => `${props.tab.schema ?? props.tab.catalog ?? 'schema'}-er`.replace(/[^\w.-]+/g, '_'))

function exportSvg() {
  download(`${fileBase.value}.svg`, new Blob([svgText()], { type: 'image/svg+xml' }))
}

function exportPng() {
  const image = new Image()
  const url = URL.createObjectURL(new Blob([svgText()], { type: 'image/svg+xml' }))
  image.onload = () => {
    const scale = 2
    const canvas = document.createElement('canvas')
    canvas.width = size.value.w * scale
    canvas.height = size.value.h * scale
    const g = canvas.getContext('2d')!
    g.fillStyle = colors.value.panel
    g.fillRect(0, 0, canvas.width, canvas.height)
    g.scale(scale, scale)
    g.drawImage(image, 0, 0)
    URL.revokeObjectURL(url)
    canvas.toBlob((blob) => blob && download(`${fileBase.value}.png`, blob), 'image/png')
  }
  image.src = url
}

watch(connected, (isConnected) => {
  if (isConnected) {
    void loadTables()
    void loadDetails()
  }
})
watch(() => props.tab.tables.length, () => void loadDetails())
watch(
  () => connections.schemaEpoch[props.tab.connectionId],
  () => {
    details.value = {}
    void loadTables()
    void loadDetails()
  },
)
onMounted(() => {
  void loadTables()
  void loadDetails()
})
</script>

<template>
  <section class="diagram" tabindex="0" @keydown="onKey">
    <div class="bar">
      <AppIcon name="diagram" :size="15" class="icon" />
      <strong>{{ tab.schema ?? tab.catalog ?? 'Schema' }}</strong>
      <span class="muted info">
        {{ tr(`${shown.length} table(s)`, `${shown.length} tabela(s)`) }} ·
        {{ tr('drag boxes to arrange, double-click to open, right-click for more', 'arraste as caixas, clique duplo abre, botão direito mostra mais') }}
      </span>
      <span class="spacer" />
      <button class="ghost" :disabled="!shown.length" :title="tr('Add the tables linked by foreign keys', 'Adicionar as tabelas ligadas por chave estrangeira')" @click="addRelated()">
        {{ tr('Add related', 'Adicionar ligadas') }}
      </button>
      <button class="ghost" :disabled="!shown.length" :title="tr('Referenced tables left, the ones pointing at them right', 'Tabelas referenciadas à esquerda, as que apontam para elas à direita')" @click="autoLayout">
        {{ tr('Auto layout', 'Organizar') }}
      </button>
      <button class="ghost" :disabled="!shown.length" :title="tr('Zoom to show everything (0)', 'Ajustar o zoom para ver tudo (0)')" @click="fit">{{ tr('Fit', 'Ajustar') }}</button>
      <button class="ghost" :aria-label="tr('Zoom out', 'Diminuir zoom')" @click="zoomTo(zoom - 0.1)">−</button>
      <button class="ghost zoom" :title="tr('Back to 100%', 'Voltar a 100%')" @click="zoomTo(1)">{{ Math.round(zoom * 100) }}%</button>
      <button class="ghost" :aria-label="tr('Zoom in', 'Aumentar zoom')" @click="zoomTo(zoom + 0.1)">+</button>
      <button class="ghost" :disabled="!shown.length" @click="exportSvg"><AppIcon name="download" /> SVG</button>
      <button class="ghost" :disabled="!shown.length" @click="exportPng"><AppIcon name="download" /> PNG</button>
    </div>
    <div v-if="!connected" class="banner">
      {{ tr('Not connected — connect from the navigator to draw the diagram.', 'Desconectado — conecte pelo navegador para desenhar o diagrama.') }}
    </div>
    <div class="body">
      <aside class="tables" :aria-label="tr('Tables in the diagram', 'Tabelas do diagrama')">
        <input v-model="filter" type="search" :placeholder="tr('Filter tables…', 'Filtrar tabelas…')" :aria-label="tr('Filter tables', 'Filtrar tabelas')" />
        <div class="quick">
          <button class="ghost" :disabled="!listed.length" @click="addAllListed">{{ tr('All', 'Todas') }}</button>
          <button class="ghost" :disabled="!tab.tables.length" @click="clearAll">{{ tr('None', 'Nenhuma') }}</button>
          <label class="check views"><input v-model="includeViews" type="checkbox" /> {{ tr('Views', 'Views') }}</label>
        </div>
        <label v-for="t in listed" :key="t.name" class="check" :class="{ current: selected === t.name }">
          <input type="checkbox" :checked="tab.tables.includes(t.name)" @change="toggle(t.name)" />
          <span class="mono">{{ t.name }}</span>
        </label>
        <p v-if="!listed.length" class="muted small">{{ connected ? tr('No tables', 'Nenhuma tabela') : '' }}</p>
      </aside>
      <div ref="canvas" class="canvas" @wheel="onWheel" @contextmenu.prevent="canvasMenu">
        <p v-if="!tab.tables.length" class="muted empty">{{ tr('Tick tables on the left to add them to the diagram.', 'Marque tabelas à esquerda para colocá-las no diagrama.') }}</p>
        <p v-else-if="loading && !shown.length" class="muted empty">{{ tr('Loading…', 'Carregando…') }}</p>
        <svg
          v-show="shown.length"
          ref="svg"
          :viewBox="`0 0 ${size.w} ${size.h}`"
          :width="size.w * zoom"
          :height="size.h * zoom"
          role="img"
          :aria-label="`ER diagram of ${tab.schema ?? tab.catalog}`"
          @pointermove="moveDrag"
          @pointerup="endDrag"
          @pointercancel="endDrag"
        >
          <defs>
            <marker id="erd-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" :fill="colors.accent" />
            </marker>
          </defs>
          <rect x="0" y="0" :width="size.w" :height="size.h" :fill="colors.panel" class="bg" @pointerdown="startPan" />
          <path
            v-for="e in edges"
            :key="e.key"
            :d="e.d"
            fill="none"
            :stroke="colors.accent"
            :stroke-width="isHot(e) ? 2.4 : 1.4"
            :opacity="active === null ? 0.85 : isHot(e) ? 1 : 0.15"
            marker-end="url(#erd-arrow)"
            pointer-events="none"
          />
          <g
            v-for="t in drawn"
            :key="t"
            class="box"
            :data-table="t"
            :opacity="isDim(t) ? 0.35 : 1"
            :transform="`translate(${tab.positions[t]!.x},${tab.positions[t]!.y})`"
            @pointerdown.stop="startDrag($event, t)"
            @pointerenter="hovered = t"
            @pointerleave="hovered = null"
            @dblclick="open(t)"
            @contextmenu.prevent.stop="boxMenu($event, t)"
          >
            <rect :width="BOX_W" :height="boxHeight(t)" rx="6" :fill="colors.panel" :stroke="selected === t ? colors.accent : colors.border" :stroke-width="selected === t ? 2.2 : 1" />
            <rect :width="BOX_W" :height="HEADER" rx="6" :fill="colors.soft" />
            <text x="10" y="17" :fill="colors.text" font-family="system-ui, sans-serif" font-size="12" font-weight="600">{{ t }}</text>
            <g v-for="(c, i) in details[t]!.columns.slice(0, MAX_ROWS)" :key="c.name">
              <text
                x="10"
                :y="HEADER + i * ROW + ROW / 2 + 6"
                :fill="details[t]!.foreignKeys.some((k) => k.column === c.name) ? colors.accent : colors.text"
                font-family="ui-monospace, monospace"
                font-size="11.5"
                :font-weight="c.primaryKey ? 700 : 400"
              >{{ c.primaryKey ? '🔑 ' : '' }}{{ c.name }}</text>
              <text
                :x="BOX_W - 10"
                :y="HEADER + i * ROW + ROW / 2 + 6"
                text-anchor="end"
                :fill="colors.muted"
                font-family="ui-monospace, monospace"
                font-size="10.5"
              >{{ c.typeName }}{{ c.nullable ? '' : ' •' }}</text>
            </g>
            <text v-if="overflow(t)" x="10" :y="HEADER + MAX_ROWS * ROW + ROW / 2 + 6" :fill="colors.muted" font-size="11">
              … {{ overflow(t) }} {{ tr('more', 'mais') }}
            </text>
          </g>
        </svg>
        <p v-if="shown.length" class="legend muted">
          🔑 {{ tr('primary key', 'chave primária') }} · <span class="fk">{{ tr('colored name', 'nome colorido') }}</span> = {{ tr('foreign key', 'chave estrangeira') }} · • {{ tr('NOT NULL', 'NOT NULL') }} · Ctrl + {{ tr('wheel zooms', 'roda dá zoom') }}
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.diagram {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.bar {
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

.zoom {
  min-width: 46px;
  text-align: center;
  font-size: 12px;
}

.banner {
  padding: 6px 10px;
  background: var(--accent-soft);
  border-bottom: 1px solid var(--border);
}

.body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.tables {
  flex: none;
  width: 210px;
  overflow: auto;
  padding: 6px 8px;
  border-right: 1px solid var(--border);
  background: var(--panel);
  font-size: 12px;
}

.tables input[type='search'] {
  width: 100%;
  margin-bottom: 6px;
}

.check {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 1px 0;
  white-space: nowrap;
}

.canvas {
  flex: 1;
  min-width: 0;
  overflow: auto;
  background: var(--panel);
}

.diagram:focus-visible {
  outline: none;
}

.info {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quick {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 6px;
}

.quick .views {
  margin-left: auto;
}

.check.current {
  color: var(--accent);
  font-weight: 600;
}

.legend {
  margin: 0;
  padding: 6px 12px;
  font-size: 11px;
}

.legend .fk {
  color: var(--accent);
}

.bg {
  cursor: grab;
}

.box {
  cursor: grab;
}

.box:active {
  cursor: grabbing;
}

.empty {
  padding: 20px;
}

.small {
  font-size: 11px;
}
</style>
