<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from './AppIcon.vue'

/**
 * Toolbar drop-down: with `split`, the left part runs the main action (`main` event) and the caret
 * opens the menu; otherwise the whole button opens it. The menu slot gets `close`.
 */
const props = withDefaults(defineProps<{ title: string; disabled?: boolean; split?: boolean; wide?: boolean; alignRight?: boolean }>(), {
  disabled: false,
  split: false,
  wide: false,
  alignRight: false,
})
const emit = defineEmits<{ main: []; open: [] }>()

const open = ref(false)

function toggle() {
  if (props.disabled) return
  open.value = !open.value
  if (open.value) emit('open')
}

function close() {
  open.value = false
}
</script>

<template>
  <div class="tool-menu" :class="{ split, wide, right: alignRight }" @keydown.esc="close">
    <template v-if="split">
      <button class="tool main" :title="title" :aria-label="title" :disabled="disabled" @click="emit('main')">
        <slot name="button" />
      </button>
      <button class="tool caret" :title="`${title}: more`" :aria-expanded="open" aria-haspopup="menu" :disabled="disabled" @click="toggle">
        <AppIcon name="caret" :size="12" />
      </button>
    </template>
    <button v-else class="tool whole" :title="title" :aria-expanded="open" aria-haspopup="menu" :disabled="disabled" @click="toggle">
      <slot name="button" />
      <AppIcon name="caret" :size="12" />
    </button>
    <div v-if="open" class="backdrop" @click="close" />
    <div v-if="open" class="menu" role="menu">
      <slot :close="close" />
    </div>
  </div>
</template>

<style scoped>
.tool-menu {
  position: relative;
  display: inline-flex;
  align-items: center;
}

.tool {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 5px;
  border-color: transparent;
  background: transparent;
  white-space: nowrap;
}

.tool:hover:not(:disabled) {
  border-color: var(--border);
  background: var(--accent-soft);
}

.split .main {
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
  padding-right: 3px;
}

.split .caret {
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
  padding: 0 2px;
}

.wide .whole {
  border-color: var(--border);
  background: var(--panel-2);
  max-width: 240px;
}

.right .menu {
  left: auto;
  right: 0;
}

.backdrop {
  position: fixed;
  inset: 0;
  z-index: 29;
}

.menu {
  position: absolute;
  top: calc(100% + 3px);
  left: 0;
  z-index: 30;
  min-width: 220px;
  max-height: 70vh;
  overflow: auto;
  padding: 4px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
  box-shadow: 0 10px 30px rgb(0 0 0 / 0.25);
}

.menu :deep(.item) {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 5px 8px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  text-align: left;
  white-space: nowrap;
}

.menu :deep(.item:hover:not(:disabled)) {
  background: var(--accent-soft);
}

.menu :deep(.item.on) {
  font-weight: 600;
  color: var(--accent);
}

.menu :deep(.heading) {
  padding: 6px 8px 2px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  color: var(--muted);
}

.menu :deep(hr) {
  margin: 4px 0;
  border: 0;
  border-top: 1px solid var(--border);
}
</style>
