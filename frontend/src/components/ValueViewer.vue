<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CellContent, CellValue } from '@/api/types'
import { downloadText } from '@/lib/export'
import { useUi } from '@/stores/ui'

/**
 * Value viewer ("Switch content viewer" / "Open with" in DBeaver): text, JSON, XML, hex and
 * images. When the cell can be addressed by primary key, `load` fetches the full content instead
 * of the grid's truncated preview.
 */
interface OpenOptions {
  column: string
  typeName: string
  value: CellValue
  load?: () => Promise<CellContent>
}

type View = 'text' | 'json' | 'xml' | 'hex' | 'image'

const ui = useUi()
const dialog = ref<HTMLDialogElement | null>(null)
const column = ref('')
const typeName = ref('')
const text = ref<string | null>(null)
const bytes = ref<Uint8Array | null>(null)
const mime = ref<string | null>(null)
const size = ref(0)
const truncated = ref(false)
const loading = ref(false)
const view = ref<View>('text')
const note = ref('')

function base64ToBytes(base64: string): Uint8Array {
  const raw = atob(base64)
  const out = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

/** Grid previews of binary data look like `0x89504e47… (1234 bytes)`. */
function previewBytes(value: string): Uint8Array | null {
  const match = /^0x([0-9a-f]*)/i.exec(value)
  if (!match) return null
  const hex = match[1]!
  const out = new Uint8Array(hex.length / 2)
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  return out
}

const json = computed(() => {
  if (text.value === null) return null
  const trimmed = text.value.trim()
  if (!/^[[{]/.test(trimmed)) return null
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2)
  } catch {
    return null
  }
})

const xml = computed(() => {
  if (text.value === null || !text.value.trim().startsWith('<')) return null
  const doc = new DOMParser().parseFromString(text.value, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length) return null
  return prettyXml(doc.documentElement, 0)
})

function prettyXml(node: Element, depth: number): string {
  const pad = '  '.repeat(depth)
  const attrs = [...node.attributes].map((a) => ` ${a.name}="${a.value}"`).join('')
  const children = [...node.childNodes].filter((n) => n.nodeType === Node.ELEMENT_NODE || (n.textContent ?? '').trim())
  if (children.length === 0) return `${pad}<${node.tagName}${attrs}/>`
  if (children.length === 1 && children[0]!.nodeType === Node.TEXT_NODE) {
    return `${pad}<${node.tagName}${attrs}>${children[0]!.textContent!.trim()}</${node.tagName}>`
  }
  const inner = children
    .map((c) => (c.nodeType === Node.ELEMENT_NODE ? prettyXml(c as Element, depth + 1) : `${pad}  ${c.textContent!.trim()}`))
    .join('\n')
  return `${pad}<${node.tagName}${attrs}>\n${inner}\n${pad}</${node.tagName}>`
}

const hex = computed(() => {
  const data = bytes.value
  if (!data) return null
  const lines: string[] = []
  const limit = Math.min(data.length, 64 * 1024)
  for (let offset = 0; offset < limit; offset += 16) {
    const chunk = data.slice(offset, offset + 16)
    const hexPart = [...chunk].map((b) => b.toString(16).padStart(2, '0')).join(' ')
    const ascii = [...chunk].map((b) => (b >= 32 && b < 127 ? String.fromCharCode(b) : '.')).join('')
    lines.push(`${offset.toString(16).padStart(8, '0')}  ${hexPart.padEnd(47)}  ${ascii}`)
  }
  if (data.length > limit) lines.push(`… ${data.length - limit} more byte(s)`)
  return lines.join('\n')
})

const imageUrl = computed(() => {
  if (!bytes.value || !mime.value?.startsWith('image/')) return null
  let binary = ''
  for (const b of bytes.value) binary += String.fromCharCode(b)
  return `data:${mime.value};base64,${btoa(binary)}`
})

const views = computed(() => {
  const list: { id: View; label: string }[] = []
  if (imageUrl.value) list.push({ id: 'image', label: 'Image' })
  if (json.value) list.push({ id: 'json', label: 'JSON' })
  if (xml.value) list.push({ id: 'xml', label: 'XML' })
  if (text.value !== null) list.push({ id: 'text', label: 'Text' })
  if (bytes.value) list.push({ id: 'hex', label: 'Hex' })
  return list
})

async function open(options: OpenOptions) {
  column.value = options.column
  typeName.value = options.typeName
  text.value = options.value === null ? null : String(options.value)
  bytes.value = text.value ? previewBytes(text.value) : null
  if (bytes.value) text.value = null
  mime.value = null
  size.value = 0
  truncated.value = !!options.load
  note.value = options.load ? 'Loading full value…' : 'Showing the value as returned in the grid (long values may be cut).'
  view.value = 'text'
  dialog.value?.showModal()
  pick()
  if (options.load) {
    loading.value = true
    try {
      const content = await options.load()
      if (content.base64 !== null) {
        bytes.value = base64ToBytes(content.base64)
        text.value = null
      } else {
        text.value = content.text
        bytes.value = null
      }
      mime.value = content.mime
      size.value = content.size
      truncated.value = content.truncated
      note.value = content.truncated ? `Showing the first part of ${content.size.toLocaleString()} ${content.base64 !== null ? 'bytes' : 'characters'}.` : ''
      pick()
    } catch (e) {
      note.value = `Could not load the full value: ${e instanceof Error ? e.message : String(e)}`
    } finally {
      loading.value = false
    }
  }
}

/** Most specific view available. */
function pick() {
  view.value = views.value[0]?.id ?? 'text'
}

const shown = computed(() => {
  switch (view.value) {
    case 'json':
      return json.value
    case 'xml':
      return xml.value
    case 'hex':
      return hex.value
    default:
      return text.value
  }
})

async function copy() {
  try {
    await navigator.clipboard.writeText(shown.value ?? '')
    ui.info('Copied')
  } catch {
    ui.error('Clipboard is not available in this context')
  }
}

function save() {
  if (bytes.value) {
    const url = URL.createObjectURL(new Blob([bytes.value as BlobPart], { type: mime.value ?? 'application/octet-stream' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `${column.value}.${mime.value?.split('/')[1] ?? 'bin'}`
    link.click()
    URL.revokeObjectURL(url)
  } else if (text.value !== null) {
    downloadText(`${column.value}.${view.value === 'json' ? 'json' : view.value === 'xml' ? 'xml' : 'txt'}`, shown.value ?? '', 'text/plain;charset=utf-8')
  }
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" :aria-label="`Value of ${column}`">
    <div class="body">
      <div class="head">
        <strong class="mono">{{ column }}</strong>
        <span class="muted">{{ typeName }}<template v-if="size"> · {{ size.toLocaleString() }} {{ bytes ? 'bytes' : 'chars' }}</template></span>
        <span class="spacer" />
        <div class="views" role="tablist">
          <button
            v-for="v in views"
            :key="v.id"
            class="ghost"
            role="tab"
            :aria-selected="view === v.id"
            :class="{ on: view === v.id }"
            @click="view = v.id"
          >
            {{ v.label }}
          </button>
        </div>
      </div>
      <p v-if="note" class="muted note">{{ note }}</p>
      <div class="content">
        <div v-if="text === null && !bytes" class="muted center">NULL</div>
        <div v-else-if="view === 'image' && imageUrl" class="image"><img :src="imageUrl" :alt="`Image in ${column}`" /></div>
        <pre v-else class="mono">{{ shown }}</pre>
      </div>
      <div class="buttons">
        <button :disabled="loading || (text === null && !bytes)" @click="copy">Copy</button>
        <button :disabled="loading || (text === null && !bytes)" @click="save">Save to file</button>
        <span class="spacer" />
        <button class="primary" @click="dialog?.close()">Close</button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(900px, calc(100vw - 32px));
  height: min(640px, calc(100vh - 32px));
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
  color: var(--text);
  box-shadow: 0 20px 60px rgb(0 0 0 / 0.35);
}

.dialog[open] {
  display: flex;
}

.dialog::backdrop {
  background: rgb(0 0 0 / 0.45);
}

.body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  width: 100%;
  min-height: 0;
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.spacer {
  flex: 1;
}

.views .on {
  background: var(--accent-soft);
}

.note {
  margin: 0;
  font-size: 12px;
}

.content {
  flex: 1;
  min-height: 0;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--panel-2);
}

pre {
  margin: 0;
  padding: 10px 12px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}

.image {
  display: grid;
  place-items: center;
  min-height: 100%;
  padding: 10px;
}

.image img {
  max-width: 100%;
  max-height: 100%;
  image-rendering: auto;
  background: repeating-conic-gradient(#ccc 0 25%, #fff 0 50%) 50% / 16px 16px;
}

.center {
  display: grid;
  place-items: center;
  height: 100%;
}

.buttons {
  display: flex;
  gap: 8px;
}
</style>
