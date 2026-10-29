<script setup lang="ts">
import { nextTick, ref } from 'vue'
import type { MenuItem } from '@/lib/menu'
import AppIcon from './AppIcon.vue'

/** The entries of one menu level; submenus open to the side on hover or click, flipping when they would leave the window. */
const props = defineProps<{ items: MenuItem[] }>()
const emit = defineEmits<{ done: [] }>()

const openIndex = ref<number | null>(null)
const root = ref<HTMLElement | null>(null)
const flip = ref(false)

async function openSub(index: number) {
  openIndex.value = index
  flip.value = false
  await nextTick()
  const rect = root.value?.querySelector<HTMLElement>(':scope > .item > .sub')?.getBoundingClientRect()
  if (rect && rect.right > window.innerWidth - 4) flip.value = true
}

async function choose(item: MenuItem, index: number) {
  if (item.disabled) return
  if (item.children) {
    await openSub(index)
    return
  }
  emit('done')
  await item.action?.()
}

function onKey(event: KeyboardEvent) {
  const buttons = [...(root.value?.querySelectorAll<HTMLButtonElement>(':scope > .item > button:not(:disabled)') ?? [])]
  if (buttons.length === 0) return
  const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
  if (event.key === 'ArrowDown') buttons[(at + 1) % buttons.length]!.focus()
  else if (event.key === 'ArrowUp') buttons[(at - 1 + buttons.length) % buttons.length]!.focus()
  else if (event.key === 'ArrowRight' && at >= 0) {
    const index = props.items.findIndex((i) => i.label === buttons[at]!.dataset.label)
    if (index >= 0 && props.items[index]!.children) void openSub(index)
    else return
  } else if (event.key === 'ArrowLeft' && openIndex.value !== null) openIndex.value = null
  else return
  event.preventDefault()
  event.stopPropagation()
}
</script>

<template>
  <div ref="root" class="menu-list" role="menu" @keydown="onKey">
    <template v-for="(item, i) in items" :key="i">
      <hr v-if="item.separator" />
      <div v-else class="item">
      <button
        class="entry"
        :class="{ danger: item.danger, open: openIndex === i }"
        role="menuitem"
        :data-label="item.label"
        :disabled="item.disabled"
        :aria-haspopup="item.children ? 'menu' : undefined"
        :aria-expanded="item.children ? openIndex === i : undefined"
        @click="choose(item, i)"
        @mouseenter="item.children && !item.disabled ? openSub(i) : (openIndex = null)"
      >
        <span class="mark">
          <template v-if="item.checked">✓</template>
          <AppIcon v-else-if="item.icon" :name="item.icon" :size="13" />
        </span>
        <span class="text">{{ item.label }}</span>
        <span v-if="item.shortcut" class="shortcut">{{ item.shortcut }}</span>
        <AppIcon v-if="item.children" name="chevron" :size="10" class="arrow" />
      </button>
      <div v-if="item.children && openIndex === i" class="sub" :class="{ flip }">
        <MenuList :items="item.children" @done="emit('done')" />
      </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.menu-list {
  min-width: 210px;
  padding: 4px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
  color: var(--text);
  box-shadow: 0 10px 30px rgb(0 0 0 / 0.3);
}

hr {
  margin: 4px 2px;
  border: 0;
  border-top: 1px solid var(--border);
}

.item {
  position: relative;
}

.entry {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 26px;
  padding: 0 8px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  white-space: nowrap;
  cursor: default;
}

.entry:hover:not(:disabled),
.entry:focus-visible,
.entry.open {
  background: var(--accent-soft);
}

.entry:disabled {
  opacity: 0.45;
}

.entry.danger:not(:disabled) {
  color: var(--danger);
}

.mark {
  display: inline-flex;
  flex: none;
  justify-content: center;
  width: 16px;
  color: var(--accent);
}

.text {
  flex: 1;
}

.shortcut {
  margin-left: 18px;
  color: var(--muted);
  font-size: 11px;
}

.arrow {
  color: var(--muted);
}

.sub {
  position: absolute;
  top: -5px;
  left: calc(100% - 2px);
  z-index: 2;
}

.sub.flip {
  left: auto;
  right: calc(100% - 2px);
}
</style>
