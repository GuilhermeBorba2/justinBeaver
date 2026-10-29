<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { closeContextMenu, contextMenu } from '@/composables/contextMenu'
import MenuList from './MenuList.vue'

const box = ref<HTMLElement | null>(null)

/** Escape closes the menu wherever the focus is (the click that opened it may have left it on the page). */
function onEscape(event: KeyboardEvent) {
  if (event.key === 'Escape' && contextMenu.open) closeContextMenu()
}
onMounted(() => window.addEventListener('keydown', onEscape))
onBeforeUnmount(() => window.removeEventListener('keydown', onEscape))
const pos = ref({ x: 0, y: 0 })

/** Keeps the menu inside the window: it opens at the pointer, shifted back when it would overflow. */
watch(
  () => contextMenu.open,
  async (open) => {
    if (!open) return
    pos.value = { x: contextMenu.x, y: contextMenu.y }
    await nextTick()
    const rect = box.value?.getBoundingClientRect()
    if (!rect) return
    pos.value = {
      x: Math.max(4, Math.min(contextMenu.x, window.innerWidth - rect.width - 4)),
      y: Math.max(4, Math.min(contextMenu.y, window.innerHeight - rect.height - 4)),
    }
    box.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
  },
)
</script>

<template>
  <Teleport to="body">
    <div v-if="contextMenu.open" class="backdrop" @click="closeContextMenu" @contextmenu.prevent="closeContextMenu" />
    <div v-if="contextMenu.open" ref="box" class="host" :style="{ left: pos.x + 'px', top: pos.y + 'px' }">
      <MenuList :items="contextMenu.items" @done="closeContextMenu" />
    </div>
  </Teleport>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
}

.host {
  position: fixed;
  z-index: 1001;
}
</style>
