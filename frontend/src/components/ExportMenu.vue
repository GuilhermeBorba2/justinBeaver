<script setup lang="ts">
import { ref } from 'vue'
import type { CellValue, ResultColumn } from '@/api/types'
import { downloadText, EXPORT_FORMATS, render, type ExportFormat } from '@/lib/export'
import { toXlsx } from '@/lib/xlsx'
import { useUi } from '@/stores/ui'
import AppIcon from './AppIcon.vue'

const props = defineProps<{
  columns: ResultColumn[]
  rows: CellValue[][]
  name: string
  /** Fetches every row (the query or table again, without the page limit) for "All rows". */
  fetchAll?: () => Promise<{ columns: ResultColumn[]; rows: CellValue[][] }>
}>()

const ui = useUi()
const open = ref(false)
const scope = ref<'loaded' | 'all'>('loaded')
const busy = ref(false)

async function data(): Promise<{ columns: ResultColumn[]; rows: CellValue[][] } | undefined> {
  if (scope.value === 'loaded' || !props.fetchAll) return { columns: props.columns, rows: props.rows }
  busy.value = true
  try {
    return await ui.guard(props.fetchAll)
  } finally {
    busy.value = false
  }
}

const baseName = () => props.name.replace(/[^\w.-]+/g, '_') || 'result'

async function save(format: ExportFormat) {
  const spec = EXPORT_FORMATS.find((f) => f.id === format)!
  const d = await data()
  if (!d) return
  downloadText(`${baseName()}.${spec.extension}`, render(format, d.columns, d.rows, props.name), `${spec.mime};charset=utf-8`)
  open.value = false
}

async function saveXlsx() {
  const d = await data()
  if (!d) return
  const bytes = toXlsx(d.columns, d.rows, props.name.split('.').pop() ?? 'Sheet1')
  const url = URL.createObjectURL(new Blob([bytes], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `${baseName()}.xlsx`
  link.click()
  URL.revokeObjectURL(url)
  open.value = false
}

async function copy(format: ExportFormat) {
  const d = await data()
  if (!d) return
  open.value = false
  try {
    await navigator.clipboard.writeText(render(format, d.columns, d.rows, props.name))
    ui.info(`Copied ${d.rows.length} row(s) as ${EXPORT_FORMATS.find((f) => f.id === format)!.label}`)
  } catch {
    ui.error('Clipboard is not available in this context')
  }
}
</script>

<template>
  <div class="export" @keydown.esc="open = false">
    <button class="ghost" :aria-expanded="open" aria-haspopup="menu" :disabled="columns.length === 0" @click="open = !open">
      <AppIcon name="download" /> Export
    </button>
    <div v-if="open" class="backdrop" @click="open = false" />
    <div v-if="open" class="menu" role="menu">
      <div v-if="fetchAll" class="scope" role="radiogroup" aria-label="Rows to export">
        <label><input v-model="scope" type="radio" value="loaded" /> Loaded rows ({{ rows.length.toLocaleString() }})</label>
        <label><input v-model="scope" type="radio" value="all" /> All rows</label>
      </div>
      <div class="item" role="none">
        <span class="label">Excel (XLSX)</span>
        <button class="ghost" role="menuitem" :disabled="busy" @click="saveXlsx">Save</button>
      </div>
      <div v-for="format in EXPORT_FORMATS" :key="format.id" class="item" role="none">
        <span class="label">{{ format.label }}</span>
        <button class="ghost" role="menuitem" :disabled="busy" @click="save(format.id)">Save</button>
        <button class="ghost" role="menuitem" :disabled="busy" @click="copy(format.id)">Copy</button>
      </div>
      <div class="note muted">{{ busy ? 'Fetching all rows…' : scope === 'all' ? 'Runs the query again without the row limit (up to 1,000,000 rows)' : `${rows.length} row(s) currently loaded` }}</div>
    </div>
  </div>
</template>

<style scoped>
.export {
  position: relative;
}

.backdrop {
  position: fixed;
  inset: 0;
  z-index: 19;
}

.menu {
  position: absolute;
  right: 0;
  bottom: calc(100% + 4px);
  z-index: 20;
  min-width: 260px;
  padding: 4px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
  box-shadow: 0 10px 30px rgb(0 0 0 / 0.25);
}

.item {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 1px 4px;
  border-radius: 4px;
}

.item:hover {
  background: var(--panel-2);
}

.label {
  flex: 1;
}

.scope {
  display: flex;
  gap: 12px;
  padding: 4px 6px 6px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 4px;
  font-size: 12px;
}

.scope label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.note {
  padding: 4px 6px 2px;
  font-size: 11px;
}
</style>
