<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { answerPrompt, promptState } from '@/composables/prompt'

const dialog = ref<HTMLDialogElement | null>(null)
const input = ref<HTMLInputElement | null>(null)

watch(
  () => promptState.open,
  async (open) => {
    if (open && !dialog.value?.open) {
      dialog.value?.showModal()
      await nextTick()
      input.value?.select()
    } else if (!open && dialog.value?.open) {
      dialog.value.close()
    }
  },
)
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="prompt-title" @cancel.prevent="answerPrompt(null)">
    <form class="form" @submit.prevent="answerPrompt(promptState.value)">
      <h2 id="prompt-title">{{ promptState.title }}</h2>
      <label class="field">
        <span v-if="promptState.label">{{ promptState.label }}</span>
        <input ref="input" v-model="promptState.value" type="text" autocomplete="off" spellcheck="false" />
      </label>
      <div class="actions">
        <button type="button" @click="answerPrompt(null)">Cancel</button>
        <button type="submit" class="primary" :disabled="!promptState.value.trim()">{{ promptState.confirmText }}</button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(420px, calc(100vw - 32px));
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

.form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
}

h2 {
  margin: 0;
  font-size: 15px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
