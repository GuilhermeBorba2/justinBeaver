<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMenus } from '@/composables/menus'
import type { MenuItem } from '@/lib/menu'
import MenuList from './MenuList.vue'

/** File, Edit, Navigate, Search, SQL editor, Database, Window, Help: DBeaver's main menu. Entries are built when a menu opens. */
const menus = useMenus()
/** Rebuilt when the language changes, so the titles follow it. */
const bar = computed(() => menus.menuBar())

const openId = ref<string | null>(null)
const items = ref<MenuItem[]>([])

function show(id: string) {
  openId.value = id
  items.value = bar.value.find((m) => m.id === id)?.items() ?? []
}

function toggle(id: string) {
  if (openId.value === id) close()
  else show(id)
}

function close() {
  openId.value = null
}

/** With a menu open, moving over another title switches to it, as in desktop menu bars. */
function hover(id: string) {
  if (openId.value && openId.value !== id) show(id)
}

function onKey(event: KeyboardEvent) {
  if (event.key === 'Escape') close()
  else if (openId.value && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) {
    const at = bar.value.findIndex((m) => m.id === openId.value)
    const next = bar.value[(at + (event.key === 'ArrowRight' ? 1 : -1) + bar.value.length) % bar.value.length]!
    show(next.id)
  }
}
</script>

<template>
  <nav class="menubar" aria-label="Main menu" @keydown="onKey">
    <div v-for="menu in bar" :key="menu.id" class="slot">
      <button
        class="title"
        :class="{ on: openId === menu.id }"
        aria-haspopup="menu"
        :aria-expanded="openId === menu.id"
        @click="toggle(menu.id)"
        @mouseenter="hover(menu.id)"
      >
        {{ menu.label }}
      </button>
      <div v-if="openId === menu.id" class="drop">
        <MenuList :items="items" @done="close" />
      </div>
    </div>
    <div v-if="openId" class="backdrop" @click="close" />
  </nav>
</template>

<style scoped>
.menubar {
  display: flex;
  align-items: center;
  gap: 2px;
}

.slot {
  position: relative;
}

.title {
  height: 24px;
  padding: 0 9px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: inherit;
  font: inherit;
}

.title:hover,
.title.on {
  background: var(--accent-soft);
}

.drop {
  position: absolute;
  top: 100%;
  left: 0;
  z-index: 101;
  margin-top: 2px;
}

.backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
}
</style>
