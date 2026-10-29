<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Container } from '@/api/types'
import { tr } from '@/lib/menu'
import AppIcon from './AppIcon.vue'

/**
 * DBeaver's "Select Database/Schema": databases on the left, the schemas of the chosen one on the right, each with a
 * filter box. OK (or a double click) switches the script to the chosen database and/or the active schema.
 */
const props = defineProps<{
  loading: boolean
  /** Databases of the server (PostgreSQL: each is its own session); empty when the driver lists none. */
  databases: string[]
  containers: Container[]
  currentDatabase: string | null
  currentSchema: string | null
  /** Switching database needs an open SQL script. */
  canSwitchDatabase: boolean
}>()

const emit = defineEmits<{
  database: [name: string]
  container: [container: Container]
}>()

const dialog = ref<HTMLDialogElement | null>(null)
const dbFilter = ref('')
const schemaFilter = ref('')
const pickedDb = ref<string | null>(null)
const pickedSchema = ref<Container | null>(null)

/** Left list: server databases, else the catalogs of the containers (MySQL/MariaDB), else just the current one. */
const leftItems = computed(() => {
  if (props.databases.length > 1) return props.databases
  const catalogs = [...new Set(props.containers.map((c) => c.catalog).filter((c): c is string => !!c))]
  if (catalogs.length > 0) return catalogs
  return props.currentDatabase ? [props.currentDatabase] : []
})

const mysqlStyle = computed(() => props.databases.length <= 1 && props.containers.some((c) => c.catalog && !c.schema))

/** Right list: the schemas of the chosen database; only the current database's are known without opening it. */
const rightItems = computed(() => {
  const db = pickedDb.value
  if (!db || mysqlStyle.value) return []
  if (props.databases.length > 1 && db !== props.currentDatabase) return []
  return props.containers.filter((c) => !!c.schema)
})

const matches = (text: string, filter: string) => text.toLowerCase().includes(filter.trim().toLowerCase())
const shownLeft = computed(() => leftItems.value.filter((d) => matches(d, dbFilter.value)))
const shownRight = computed(() => rightItems.value.filter((c) => matches(c.label, schemaFilter.value)))

const otherDatabase = computed(() => props.databases.length > 1 && !!pickedDb.value && pickedDb.value !== props.currentDatabase)
const hint = computed(() => {
  if (!pickedDb.value) return ''
  if (otherDatabase.value) {
    return props.canSwitchDatabase
      ? tr('Press OK to run this script on that database; open the dialog again to pick its schema.', 'Clique em OK para executar este script nesse banco; abra de novo para escolher o schema.')
      : tr('Open a SQL script to switch its database.', 'Abra um script SQL para trocar o banco dele.')
  }
  return mysqlStyle.value ? tr('This database has no schemas: it is the active one.', 'Este banco não tem schemas: ele é o ativo.') : ''
})

const canOk = computed(() => {
  if (pickedSchema.value) return true
  if (!pickedDb.value || pickedDb.value === props.currentDatabase) return false
  return otherDatabase.value ? props.canSwitchDatabase : mysqlStyle.value
})

function pickDb(db: string) {
  pickedDb.value = db
  pickedSchema.value = null
  schemaFilter.value = ''
}

function ok() {
  if (!canOk.value) return
  if (pickedSchema.value) emit('container', pickedSchema.value)
  else if (otherDatabase.value) emit('database', pickedDb.value!)
  else if (mysqlStyle.value) emit('container', { catalog: pickedDb.value!, schema: null, label: pickedDb.value! })
  dialog.value?.close()
}

function onDbDouble(db: string) {
  pickDb(db)
  if (canOk.value) ok()
}

function onSchemaDouble(c: Container) {
  pickedSchema.value = c
  ok()
}

function open() {
  dbFilter.value = ''
  schemaFilter.value = ''
  pickedDb.value = props.currentDatabase ?? leftItems.value[0] ?? null
  pickedSchema.value = null
  dialog.value?.showModal()
}

// once the lists arrive, preselect the active database
watch(
  () => props.loading,
  (loading) => {
    if (!loading && dialog.value?.open && !pickedDb.value) pickedDb.value = props.currentDatabase ?? leftItems.value[0] ?? null
  },
)

const isCurrentSchema = (c: Container) => c.schema === props.currentSchema

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="picker-title" @keydown.enter.prevent="ok">
    <div class="body">
      <h2 id="picker-title">{{ tr('Select Database/Schema', 'Selecionar Database/Schema') }}</h2>
      <div v-if="loading" class="muted">{{ tr('Loading…', 'Carregando…') }}</div>
      <div v-else class="panes">
        <section>
          <div class="head"><span>{{ tr('Name', 'Nome') }}</span></div>
          <ul role="listbox" :aria-label="tr('Databases', 'Databases')">
            <li
              v-for="db in shownLeft"
              :key="db"
              role="option"
              :aria-selected="db === pickedDb"
              :class="{ on: db === pickedDb, active: db === currentDatabase }"
              @click="pickDb(db)"
              @dblclick="onDbDouble(db)"
            >
              <AppIcon name="database" /> {{ db }}
            </li>
            <li v-if="shownLeft.length === 0" class="empty">{{ tr('Nothing found', 'Nada encontrado') }}</li>
          </ul>
          <input v-model="dbFilter" type="search" :placeholder="tr('Filter databases', 'Filtrar databases')" autocomplete="off" spellcheck="false" />
        </section>
        <section>
          <div class="head"><span>{{ tr('Schema', 'Schema') }}</span></div>
          <ul role="listbox" :aria-label="tr('Schemas', 'Schemas')">
            <li
              v-for="c in shownRight"
              :key="`${c.catalog}.${c.schema}`"
              role="option"
              :aria-selected="c === pickedSchema"
              :class="{ on: c === pickedSchema, active: isCurrentSchema(c) }"
              @click="pickedSchema = c"
              @dblclick="onSchemaDouble(c)"
            >
              <AppIcon name="schema" /> {{ c.label }}
            </li>
            <li v-if="hint" class="empty">{{ hint }}</li>
          </ul>
          <input v-model="schemaFilter" type="search" :placeholder="tr('Filter schemas', 'Filtrar schemas')" autocomplete="off" spellcheck="false" />
        </section>
      </div>
      <div class="actions">
        <button type="button" @click="dialog?.close()">{{ tr('Cancel', 'Cancelar') }}</button>
        <button type="button" class="primary" :disabled="!canOk" @click="ok">OK</button>
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
  background: rgb(0 0 0 / 0.35);
}

.body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
}

h2 {
  margin: 0;
  font-size: 15px;
}

.panes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

section {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.head {
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: 6px 6px 0 0;
  background: var(--panel-2);
  font-weight: 600;
}

ul {
  height: 260px;
  margin: -6px 0 0;
  padding: 0;
  overflow: auto;
  list-style: none;
  border: 1px solid var(--border);
  border-top: 0;
  background: var(--bg);
}

li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  cursor: default;
  white-space: nowrap;
}

li:hover {
  background: var(--accent-soft);
}

li.active {
  font-weight: 700;
}

li.on {
  background: var(--accent);
  color: var(--accent-contrast, #fff);
}

li.empty {
  color: var(--muted);
  white-space: normal;
  cursor: default;
}

li.empty:hover {
  background: transparent;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
