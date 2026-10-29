<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useMenus } from '@/composables/menus'
import { flattenMenu } from '@/lib/menu'

/** Ctrl+Shift+P: every command of the menu bar in one searchable list. */
const bar = useMenus().menuBar()

const dialog = ref<HTMLDialogElement | null>(null)
const input = ref<HTMLInputElement | null>(null)
const text = ref('')
const active = ref(0)

const commands = ref<{ label: string; run: () => unknown; shortcut?: string; disabled: boolean }[]>([])

const matches = computed(() => {
  const words = text.value.toLowerCase().split(/\s+/).filter(Boolean)
  return commands.value.filter((c) => words.every((w) => c.label.toLowerCase().includes(w))).slice(0, 60)
})

watch(text, () => (active.value = 0))

function open() {
  const seen = new Set<string>()
  commands.value = bar
    .flatMap((menu) => flattenMenu(menu.items()).map(({ label, item }) => ({ label: `${menu.label} › ${label}`, item })))
    // the same command can sit in several menus: list it once, under the first
    .filter(({ item }) => {
      if (seen.has(item.label!)) return false
      seen.add(item.label!)
      return true
    })
    .map(({ label, item }) => ({ label, run: item.action!, shortcut: item.shortcut, disabled: item.disabled === true }))
  text.value = ''
  active.value = 0
  dialog.value?.showModal()
  void nextTick(() => input.value?.focus())
}

function choose(index = active.value) {
  const command = matches.value[index]
  if (!command || command.disabled) return
  dialog.value?.close()
  void command.run()
}

function onKey(event: KeyboardEvent) {
  if (event.key === 'ArrowDown') active.value = Math.min(matches.value.length - 1, active.value + 1)
  else if (event.key === 'ArrowUp') active.value = Math.max(0, active.value - 1)
  else if (event.key === 'Enter') choose()
  else return
  event.preventDefault()
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-label="Command palette" @keydown="onKey" @click.self="dialog?.close()">
    <div class="body">
      <input ref="input" v-model="text" class="search" type="search" placeholder="Type a command…" autocomplete="off" spellcheck="false" />
      <ul class="results" role="listbox">
        <li
          v-for="(command, i) in matches"
          :key="command.label"
          role="option"
          :aria-selected="i === active"
          :class="{ on: i === active, off: command.disabled }"
          @mousemove="active = i"
          @click="choose(i)"
        >
          <span>{{ command.label }}</span>
          <span v-if="command.shortcut" class="muted keys">{{ command.shortcut }}</span>
        </li>
        <li v-if="matches.length === 0" class="muted empty">No command matches "{{ text }}"</li>
      </ul>
      <p class="muted hint">↑↓ to move · Enter to run · Esc to close</p>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(620px, calc(100vw - 32px));
  margin-top: 12vh;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
  color: var(--text);
  box-shadow: 0 20px 60px rgb(0 0 0 / 0.35);
}

.dialog::backdrop {
  background: rgb(0 0 0 / 0.35);
}

.body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
}

.search {
  font-size: 15px;
  padding: 8px 10px;
}

.results {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 50vh;
  overflow: auto;
}

.results li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 5px;
  cursor: pointer;
}

.results li.on {
  background: var(--accent-soft);
}

.results li.off {
  opacity: 0.45;
}

.keys,
.hint {
  font-size: 11px;
}

.empty {
  cursor: default;
}

.hint {
  margin: 2px 4px;
}
</style>
