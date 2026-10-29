<script setup lang="ts">
import { nextTick, reactive, ref } from 'vue'
import { rememberedValues, rememberValues } from '@/lib/params'

/** Asks for the values of SQL variables before a statement runs. */
const dialog = ref<HTMLDialogElement | null>(null)
const firstInput = ref<HTMLInputElement[]>([])
const names = ref<string[]>([])
const values = reactive<Record<string, string>>({})
let resolve: ((values: Record<string, string> | null) => void) | undefined

function ask(params: string[]): Promise<Record<string, string> | null> {
  names.value = params
  const remembered = rememberedValues()
  for (const key of Object.keys(values)) delete values[key]
  for (const name of params) values[name] = remembered[name] ?? ''
  dialog.value?.showModal()
  void nextTick(() => firstInput.value[0]?.focus())
  return new Promise((r) => {
    resolve = r
  })
}

function finish(ok: boolean) {
  dialog.value?.close()
  if (ok) rememberValues({ ...values })
  resolve?.(ok ? { ...values } : null)
  resolve = undefined
}

defineExpose({ ask })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="params-title" @cancel.prevent="finish(false)">
    <form class="form" @submit.prevent="finish(true)">
      <h2 id="params-title">SQL variables</h2>
      <p class="muted">Values replace the variables as SQL text: quote strings (<code>'abc'</code>), write numbers as is, or <code>NULL</code>.</p>
      <label v-for="name in names" :key="name" class="field">
        <span class="mono">:{{ name }}</span>
        <input ref="firstInput" v-model="values[name]" class="mono" :aria-label="`Value of ${name}`" />
      </label>
      <div class="buttons">
        <button type="button" @click="finish(false)">Cancel</button>
        <button type="submit" class="primary">Run</button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(460px, calc(100vw - 32px));
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
  color: var(--text);
  box-shadow: 0 20px 60px rgb(0 0 0 / 0.35);
}

.dialog::backdrop {
  background: rgb(0 0 0 / 0.45);
}

.form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 20px;
}

h2 {
  margin: 0;
  font-size: 16px;
}

p {
  margin: 0;
  font-size: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.buttons {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

code {
  font-family: var(--font-mono);
}
</style>
