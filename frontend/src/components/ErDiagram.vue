<script setup lang="ts">
import { computed } from 'vue'
import type { ForeignKeyInfo, TableDetails } from '@/api/types'

/**
 * A focused ER view of one table: the tables its foreign keys point to on the left, the tables
 * pointing to it on the right, one line per key column. Click a related table to open it.
 */
const props = defineProps<{ details: TableDetails; label: string }>()
const emit = defineEmits<{ open: [catalog: string | null, schema: string | null, table: string] }>()

const BOX_W = 230
const HEADER = 26
const ROW = 19
const GAP = 22
const LEFT_X = 20
const CENTER_X = 330
const RIGHT_X = 640
const MAX_CENTER_ROWS = 40

interface Box {
  key: string
  catalog: string | null
  schema: string | null
  table: string
  rows: string[]
  x: number
  y: number
}

function group(keys: ForeignKeyInfo[], x: number): Box[] {
  const map = new Map<string, Box>()
  for (const k of keys) {
    const id = `${k.refCatalog ?? ''}.${k.refSchema ?? ''}.${k.refTable}`
    const box = map.get(id) ?? { key: id, catalog: k.refCatalog, schema: k.refSchema, table: k.refTable, rows: [], x, y: 0 }
    if (!box.rows.includes(k.refColumn)) box.rows.push(k.refColumn)
    map.set(id, box)
  }
  let y = 20
  const boxes = [...map.values()]
  for (const box of boxes) {
    box.y = y
    y += HEADER + box.rows.length * ROW + 8 + GAP
  }
  return boxes
}

const targets = computed(() => group(props.details.foreignKeys, LEFT_X))
const referrers = computed(() => group(props.details.referencedBy, RIGHT_X))

const centerRows = computed(() => props.details.columns.slice(0, MAX_CENTER_ROWS))
const hiddenColumns = computed(() => props.details.columns.length - centerRows.value.length)
const fkColumns = computed(() => new Set(props.details.foreignKeys.map((k) => k.column)))
const center = { x: CENTER_X, y: 20 }

const boxHeight = (rows: number) => HEADER + rows * ROW + 8
const centerHeight = computed(() => boxHeight(centerRows.value.length + (hiddenColumns.value ? 1 : 0)))
const rowY = (boxY: number, index: number) => boxY + HEADER + index * ROW + ROW / 2 + 2

function centerRowY(column: string): number {
  const index = centerRows.value.findIndex((c) => c.name === column)
  return index < 0 ? center.y + HEADER / 2 : rowY(center.y, index)
}

interface Edge {
  key: string
  d: string
}

const edges = computed<Edge[]>(() => {
  const list: Edge[] = []
  for (const k of props.details.foreignKeys) {
    const box = targets.value.find((b) => b.table === k.refTable && b.schema === k.refSchema && b.catalog === k.refCatalog)
    if (!box) continue
    const x1 = CENTER_X
    const y1 = centerRowY(k.column)
    const x2 = box.x + BOX_W
    const y2 = rowY(box.y, box.rows.indexOf(k.refColumn))
    list.push({ key: `out-${k.name}-${k.column}`, d: `M${x1},${y1} C${x1 - 40},${y1} ${x2 + 40},${y2} ${x2},${y2}` })
  }
  for (const k of props.details.referencedBy) {
    const box = referrers.value.find((b) => b.table === k.refTable && b.schema === k.refSchema && b.catalog === k.refCatalog)
    if (!box) continue
    // drawn from the referencing table to this one, so the arrow points at the referenced key
    const x1 = box.x
    const y1 = rowY(box.y, box.rows.indexOf(k.refColumn))
    const x2 = CENTER_X + BOX_W
    const y2 = centerRowY(k.column)
    list.push({ key: `in-${k.refTable}-${k.name}-${k.refColumn}`, d: `M${x1},${y1} C${x1 - 40},${y1} ${x2 + 40},${y2} ${x2},${y2}` })
  }
  return list
})

const height = computed(() => {
  const stack = (boxes: Box[]) => boxes.reduce((h, b) => Math.max(h, b.y + boxHeight(b.rows.length)), 0)
  return Math.max(centerHeight.value + 40, stack(targets.value) + 20, stack(referrers.value) + 20, 200)
})
const nothingRelated = computed(() => targets.value.length === 0 && referrers.value.length === 0)
</script>

<template>
  <div class="er">
    <p v-if="nothingRelated" class="muted note">No foreign keys to or from this table.</p>
    <p v-else class="muted note">
      Left: tables this one references ({{ targets.length }}). Right: tables referencing it ({{ referrers.length }}).
      Click a table to open it.
    </p>
    <svg :viewBox="`0 0 ${RIGHT_X + BOX_W + 20} ${height}`" :width="RIGHT_X + BOX_W + 20" :height="height" role="img"
         :aria-label="`Relationships of ${label}`">
      <defs>
        <marker id="er-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" class="arrow" />
        </marker>
      </defs>

      <path v-for="edge in edges" :key="edge.key" :d="edge.d" class="edge" marker-end="url(#er-arrow)" />

      <!-- the table itself -->
      <g>
        <rect :x="center.x" :y="center.y" :width="BOX_W" :height="centerHeight" rx="6" class="box main" />
        <rect :x="center.x" :y="center.y" :width="BOX_W" :height="HEADER" rx="6" class="head main" />
        <text :x="center.x + 10" :y="center.y + 17" class="title">{{ label }}</text>
        <g v-for="(column, i) in centerRows" :key="column.name">
          <text :x="center.x + 10" :y="rowY(center.y, i) + 4" class="col" :class="{ pk: column.primaryKey, fk: fkColumns.has(column.name) }">
            {{ column.primaryKey ? '🔑 ' : fkColumns.has(column.name) ? '↗ ' : '' }}{{ column.name }}
          </text>
          <text :x="center.x + BOX_W - 10" :y="rowY(center.y, i) + 4" class="type" text-anchor="end">{{ column.typeName }}</text>
        </g>
        <text v-if="hiddenColumns" :x="center.x + 10" :y="rowY(center.y, centerRows.length) + 4" class="type">
          … {{ hiddenColumns }} more column(s)
        </text>
      </g>

      <!-- related tables -->
      <g
        v-for="box in [...targets, ...referrers]"
        :key="box.key + box.x"
        class="related"
        tabindex="0"
        role="button"
        :aria-label="`Open ${box.table}`"
        @click="emit('open', box.catalog, box.schema, box.table)"
        @keydown.enter="emit('open', box.catalog, box.schema, box.table)"
      >
        <rect :x="box.x" :y="box.y" :width="BOX_W" :height="boxHeight(box.rows.length)" rx="6" class="box" />
        <rect :x="box.x" :y="box.y" :width="BOX_W" :height="HEADER" rx="6" class="head" />
        <text :x="box.x + 10" :y="box.y + 17" class="title">
          {{ [box.schema ?? box.catalog, box.table].filter(Boolean).join('.') }}
        </text>
        <text v-for="(row, i) in box.rows" :key="row" :x="box.x + 10" :y="rowY(box.y, i) + 4" class="col">{{ row }}</text>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.er {
  height: 100%;
  overflow: auto;
  padding: 8px 12px;
  background: var(--panel);
}

.note {
  margin: 0 0 6px;
}

.box {
  fill: var(--panel);
  stroke: var(--border);
}

.box.main {
  stroke: var(--accent);
  stroke-width: 1.5;
}

.head {
  fill: var(--panel-2);
}

.head.main {
  fill: var(--accent-soft);
}

.title {
  font: 600 12px var(--font-ui);
  fill: var(--text);
}

.col {
  font: 12px var(--font-mono);
  fill: var(--text);
}

.col.pk {
  font-weight: 700;
}

.col.fk {
  fill: var(--accent);
}

.type {
  font: 11px var(--font-mono);
  fill: var(--muted);
}

.edge {
  fill: none;
  stroke: var(--accent);
  stroke-width: 1.4;
  opacity: 0.8;
}

.arrow {
  fill: var(--accent);
}

.related {
  cursor: pointer;
}

.related:hover .box,
.related:focus-visible .box {
  stroke: var(--accent);
}

.related:focus-visible {
  outline: none;
}
</style>
