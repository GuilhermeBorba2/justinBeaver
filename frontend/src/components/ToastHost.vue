<script setup lang="ts">
import { useUi } from '@/stores/ui'
import AppIcon from './AppIcon.vue'

const ui = useUi()
</script>

<template>
  <div class="toasts" role="status" aria-live="polite">
    <div v-for="toast in ui.toasts" :key="toast.id" class="toast" :class="toast.kind">
      <span class="text">{{ toast.text }}</span>
      <button class="ghost" aria-label="Dismiss" @click="ui.dismiss(toast.id)">
        <AppIcon name="close" :size="12" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.toasts {
  position: fixed;
  right: 16px;
  bottom: 36px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 100;
  max-width: min(520px, calc(100vw - 32px));
}

.toast {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  border: 1px solid var(--border);
  border-left: 4px solid var(--accent);
  background: var(--panel);
  box-shadow: 0 6px 20px rgb(0 0 0 / 0.18);
}

.toast.error {
  border-left-color: var(--danger);
}

.text {
  flex: 1;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
