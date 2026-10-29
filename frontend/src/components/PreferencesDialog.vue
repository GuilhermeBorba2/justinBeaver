<script setup lang="ts">
import { ref } from 'vue'
import { DEFAULT_SNIPPETS, readSnippets, saveSnippets, type Snippet } from '@/lib/snippets'
import { tr } from '@/lib/menu'
import { ACCENT_PRESETS, BACKGROUND_PRESETS } from '@/lib/colors'
import { usePrefs } from '@/stores/prefs'

/** Window → Preferences: appearance, editor, result sets, navigator and SQL templates. */
const prefs = usePrefs()
const dialog = ref<HTMLDialogElement | null>(null)
const section = ref<'general' | 'editor' | 'templates'>('general')
const templates = ref<Snippet[]>([])
const savedNote = ref('')

function open() {
  templates.value = readSnippets().map((s) => ({ ...s }))
  savedNote.value = ''
  dialog.value?.showModal()
}

function addTemplate() {
  templates.value.push({ name: '', description: '', template: '' })
}

function removeTemplate(i: number) {
  templates.value.splice(i, 1)
}

/** Only templates that differ from the defaults (or are new) are stored; removing a default restores it. */
function saveTemplates() {
  const valid = templates.value.filter((t) => /^\w+$/.test(t.name) && t.template.trim())
  const defaults = new Map(DEFAULT_SNIPPETS.map((s) => [s.name, s]))
  saveSnippets(valid.filter((t) => defaults.get(t.name)?.template !== t.template || defaults.get(t.name)?.description !== t.description))
  savedNote.value = 'Saved: new SQL editors use them (open scripts after reopening them).'
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="prefs-title">
    <div class="layout">
      <nav class="side" aria-label="Preference pages">
        <h2 id="prefs-title">{{ tr('Preferences', 'Preferências') }}</h2>
        <button class="ghost" :class="{ on: section === 'general' }" @click="section = 'general'">{{ tr('General', 'Geral') }}</button>
        <button class="ghost" :class="{ on: section === 'editor' }" @click="section = 'editor'">{{ tr('SQL editor & results', 'Editor SQL e resultados') }}</button>
        <button class="ghost" :class="{ on: section === 'templates' }" @click="section = 'templates'">{{ tr('SQL templates', 'Modelos SQL') }}</button>
      </nav>
      <div class="page">
        <template v-if="section === 'general'">
          <label class="field">
            <span>{{ tr('Language', 'Idioma') }}</span>
            <select v-model="prefs.state.language">
              <option value="auto">{{ tr('Automatic (browser)', 'Automático (navegador)') }}</option>
              <option value="en">English</option>
              <option value="pt">Português</option>
            </select>
          </label>
          <label class="field">
            <span>{{ tr('Theme', 'Tema') }}</span>
            <select v-model="prefs.state.theme">
              <option value="system">{{ tr('Follow the system', 'Seguir o sistema') }}</option>
              <option value="light">{{ tr('Light', 'Claro') }}</option>
              <option value="dark">{{ tr('Dark', 'Escuro') }}</option>
            </select>
          </label>
          <fieldset class="colors">
            <legend>{{ tr('Colors', 'Cores') }}</legend>
            <div class="color-row">
              <span class="label">{{ tr('Accent', 'Destaque') }}</span>
              <button
                v-for="p in ACCENT_PRESETS"
                :key="p.name"
                class="swatch"
                :class="{ on: prefs.state.accentColor === p.value }"
                :style="{ background: p.value || '#d08a5c' }"
                :title="p.name"
                :aria-label="`Accent: ${p.name}`"
                :aria-pressed="prefs.state.accentColor === p.value"
                @click="prefs.state.accentColor = p.value"
              />
              <input
                type="color"
                class="picker"
                :value="prefs.state.accentColor || '#d08a5c'"
                title="Pick any color"
                aria-label="Custom accent color"
                @input="prefs.state.accentColor = ($event.target as HTMLInputElement).value"
              />
            </div>
            <div class="color-row">
              <span class="label">{{ tr('Background', 'Fundo') }}</span>
              <button
                v-for="p in BACKGROUND_PRESETS"
                :key="p.name"
                class="swatch"
                :class="{ on: prefs.state.backgroundColor === p.value, none: !p.value }"
                :style="p.value ? { background: p.value } : undefined"
                :title="p.name"
                :aria-label="`Background: ${p.name}`"
                :aria-pressed="prefs.state.backgroundColor === p.value"
                @click="prefs.state.backgroundColor = p.value"
              >
                <template v-if="!p.value">A</template>
              </button>
              <input
                type="color"
                class="picker"
                :value="prefs.state.backgroundColor || (prefs.dark ? '#1b1a18' : '#f6f5f3')"
                title="Pick any color"
                aria-label="Custom background color"
                @input="prefs.state.backgroundColor = ($event.target as HTMLInputElement).value"
              />
            </div>
            <p class="muted note">
              {{ tr('Panels, borders and text adjust to the background you pick, light or dark. They are saved with your preferences and sync with your Google account.', 'Painéis, bordas e texto se ajustam ao fundo escolhido, claro ou escuro. Ficam salvos nas preferências e sincronizam com sua conta Google.') }}
            </p>
            <button :disabled="!prefs.state.accentColor && !prefs.state.backgroundColor" @click="(prefs.state.accentColor = ''), (prefs.state.backgroundColor = '')">
              {{ tr('Use the default colors', 'Usar as cores padrão') }}
            </button>
          </fieldset>
          <label class="check">
            <input v-model="prefs.state.hideSystemObjects" type="checkbox" />
            {{ tr('Hide system schemas in the navigator (information_schema, pg_catalog, mysql…)', 'Ocultar schemas do sistema no navegador (information_schema, pg_catalog, mysql…)') }}
          </label>
          <p class="muted note">Takes effect the next time a connection is expanded.</p>
          <label class="check">
            <input v-model="prefs.state.confirmProductionWrites" type="checkbox" />
            {{ tr('Ask before running data-changing statements on PRODUCTION connections', 'Perguntar antes de executar comandos que alteram dados em conexões de PRODUÇÃO') }}
          </label>
        </template>
        <template v-else-if="section === 'editor'">
          <label class="field">
            <span>{{ tr('Editor font size', 'Tamanho da fonte do editor') }}: {{ prefs.state.editorFontSize }} px</span>
            <input v-model.number="prefs.state.editorFontSize" type="range" min="10" max="22" />
          </label>
          <label class="check">
            <input v-model="prefs.state.uppercaseKeywords" type="checkbox" />
            {{ tr('Convert keywords to upper case as you type (select → SELECT)', 'Converter palavras-chave para maiúsculas ao digitar (select → SELECT)') }}
          </label>
          <p class="muted note">
            {{ tr('Applies when a space, new line or punctuation ends the word. Text inside strings, comments and names is never changed.', 'Vale quando um espaço, nova linha ou pontuação termina a palavra. O que está dentro de textos, comentários e nomes nunca é alterado.') }}
          </p>
          <label class="field">
            <span>Default "Max rows" of new SQL editors</span>
            <select v-model.number="prefs.state.defaultMaxRows">
              <option :value="100">100</option>
              <option :value="200">200</option>
              <option :value="1000">1,000</option>
              <option :value="10000">10,000</option>
              <option :value="100000">100,000</option>
            </select>
          </label>
          <p class="muted note">
            Shortcuts: Ctrl+Enter run · Ctrl+\ run in new tab · Alt+X script · Ctrl+Shift+E explain · Ctrl+Shift+F format ·
            Ctrl+Space complete · Tab expands a template · Ctrl+F find in results · Ctrl+Shift+D find object.
          </p>
        </template>
        <template v-else>
          <p class="muted note">Type the name in a SQL editor and press Tab. <code>${name}</code> is a field to fill; Tab moves to the next.</p>
          <div class="templates">
            <div v-for="(t, i) in templates" :key="i" class="template">
              <input v-model="t.name" class="mono name" placeholder="name" aria-label="Template name" />
              <input v-model="t.description" placeholder="description" aria-label="Template description" />
              <textarea v-model="t.template" class="mono" rows="2" aria-label="Template text" />
              <button class="ghost" title="Remove" aria-label="Remove template" @click="removeTemplate(i)">✕</button>
            </div>
          </div>
          <div class="buttons">
            <button @click="addTemplate">Add template</button>
            <span class="spacer" />
            <span class="muted note">{{ savedNote }}</span>
            <button class="primary" @click="saveTemplates">Save templates</button>
          </div>
        </template>
        <div class="buttons end">
          <button @click="prefs.reset()">{{ tr('Restore defaults', 'Restaurar padrões') }}</button>
          <span class="spacer" />
          <button class="primary" @click="dialog?.close()">{{ tr('Close', 'Fechar') }}</button>
        </div>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(820px, calc(100vw - 32px));
  max-height: calc(100vh - 32px);
  overflow: auto;
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

.layout {
  display: grid;
  grid-template-columns: 190px 1fr;
  min-height: 420px;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 16px 8px;
  border-right: 1px solid var(--border);
  background: var(--panel-2);
}

.side h2 {
  margin: 0 8px 10px;
  font-size: 16px;
}

.side button {
  justify-content: flex-start;
  text-align: left;
}

.side .on {
  background: var(--accent-soft);
  border-color: var(--accent);
}

.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 20px;
  min-width: 0;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 360px;
}

.field > span {
  font-size: 12px;
  color: var(--muted);
}

.check {
  display: flex;
  align-items: center;
  gap: 8px;
}

.note {
  margin: 0;
  font-size: 12px;
}

code {
  font-family: var(--font-mono);
}

.templates {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 300px;
  overflow: auto;
}

.template {
  display: grid;
  grid-template-columns: 80px 150px 1fr auto;
  gap: 6px;
  align-items: start;
}

.template textarea {
  resize: vertical;
  font-size: 12px;
}

.buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.buttons.end {
  margin-top: auto;
}

.spacer {
  flex: 1;
}

.colors {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 4px 0;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.colors legend {
  padding: 0 6px;
  color: var(--muted);
}

.colors > button {
  align-self: flex-start;
}

.color-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.color-row .label {
  width: 86px;
}

.swatch {
  width: 24px;
  height: 24px;
  padding: 0;
  border: 2px solid var(--border);
  border-radius: 50%;
  font-size: 11px;
  font-weight: 700;
  color: var(--muted);
  line-height: 1;
}

.swatch.none {
  background: var(--bg);
}

.swatch.on {
  border-color: var(--text);
  box-shadow: 0 0 0 2px var(--accent);
}

.picker {
  width: 32px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
}
</style>
