<script setup lang="ts">
import { ref } from 'vue'

const dialog = ref<HTMLDialogElement | null>(null)
const statements = ref<string[]>([])
const manualTransaction = ref(false)
/** schema change (DDL) instead of a data save */
const ddl = ref<{ title: string; danger: boolean } | null>(null)
let resolve: ((apply: boolean) => void) | undefined
/** In a manual transaction the user picks whether Apply also commits. */
const commitAfter = ref(true)

/**
 * Show the SQL about to run; resolves true when the user clicks Apply. In a manual transaction,
 * {@link commitRequested} then says whether to commit right after ("Apply and commit").
 */
function confirm(sql: string[], inManualTransaction: boolean): Promise<boolean> {
  commitAfter.value = true
  statements.value = sql
  manualTransaction.value = inManualTransaction
  ddl.value = null
  dialog.value?.showModal()
  return new Promise((r) => {
    resolve = r
  })
}

function commitRequested(): boolean {
  return manualTransaction.value && commitAfter.value
}

function close(apply: boolean, commit = true) {
  commitAfter.value = commit
  dialog.value?.close()
  resolve?.(apply)
  resolve = undefined
}

/** Same, for schema changes; `danger` marks destructive ones (drop, truncate). */
function confirmDdl(sql: string[], title: string, danger = false): Promise<boolean> {
  statements.value = sql
  ddl.value = { title, danger }
  dialog.value?.showModal()
  return new Promise((r) => {
    resolve = r
  })
}

defineExpose({ confirm, confirmDdl, commitRequested })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="preview-title" @cancel.prevent="close(false)">
    <div class="body">
      <h2 id="preview-title">{{ ddl ? ddl.title : 'Save changes' }}</h2>
      <p v-if="ddl" class="muted">
        {{ statements.length }} statement(s), run in order.
        <strong v-if="ddl.danger" class="danger">This destroys data and cannot be undone.</strong>
        Most databases commit schema changes immediately, even with auto-commit off.
      </p>
      <p v-else class="muted">
        {{ statements.length }} statement(s). Values are sent as bind parameters; literals below are only for reading.
        <template v-if="manualTransaction">
          <strong class="warn">Auto-commit is off.</strong> "Apply and commit" saves now; "Apply only" leaves the changes
          pending in the open transaction, invisible to others until you Commit (and lost if you Rollback or disconnect).
        </template>
        <template v-else>They run in one transaction: all succeed or none do.</template>
      </p>
      <ol class="sql mono">
        <li v-for="(sql, i) in statements" :key="i">{{ sql }};</li>
      </ol>
      <div class="buttons">
        <button type="button" @click="close(false)">Cancel</button>
        <button v-if="!ddl && manualTransaction" type="button" @click="close(true, false)">Apply only</button>
        <button type="button" class="primary" :class="{ destroy: ddl?.danger }" @click="close(true)">
          {{ ddl?.danger ? 'Run anyway' : ddl ? 'Run' : manualTransaction ? 'Apply and commit' : 'Apply' }}
        </button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(760px, calc(100vw - 32px));
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

.body {
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
}

.sql {
  margin: 0;
  padding: 10px 10px 10px 36px;
  max-height: 50vh;
  overflow: auto;
  background: var(--panel-2);
  border-radius: 6px;
  font-size: 12px;
}

.sql li {
  padding: 2px 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.danger {
  color: var(--danger);
}

.warn {
  color: var(--warn);
}

.primary.destroy {
  background: var(--danger);
  border-color: var(--danger);
}

.buttons {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
