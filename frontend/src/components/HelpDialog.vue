<script setup lang="ts">
import { ref } from 'vue'
import { keys } from '@/composables/menus'
import { tr } from '@/lib/menu'
import { useApp } from '@/stores/app'

/** Help ▸ Keyboard shortcuts / About. */
const app = useApp()
const dialog = ref<HTMLDialogElement | null>(null)
const mode = ref<'shortcuts' | 'about'>('shortcuts')
const EMAIL = 'guilhermepmb13@gmail.com'
const copied = ref(false)

/** The system mail client cannot be opened from the app window, so the address is copied instead. */
async function copyEmail() {
  try {
    await navigator.clipboard.writeText(EMAIL)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch {
    // the address stays on screen to copy by hand
  }
}

const groups = () => [
  {
    title: tr('SQL editor', 'Editor SQL'),
    rows: [
      [tr('Execute statement', 'Executar instrução'), keys('Enter')],
      [tr('Execute in a new tab', 'Executar em nova aba'), keys('\\')],
      [tr('Execute script', 'Executar script'), 'Alt+X'],
      [tr('Explain plan', 'Plano de execução'), keys('Shift', 'E')],
      [tr('Format SQL', 'Formatar SQL'), keys('Shift', 'F')],
      [tr('Find / replace', 'Localizar / substituir'), keys('F')],
      [tr('Expand a snippet', 'Expandir snippet'), 'Tab'],
    ],
  },
  {
    title: tr('Navigator', 'Navegador'),
    rows: [
      [tr('Open / expand', 'Abrir / expandir'), 'Enter'],
      [tr('Edit connection', 'Editar conexão'), 'F4'],
      [tr('Rename', 'Renomear'), 'F2'],
      [tr('Refresh', 'Atualizar'), 'F5'],
      [tr('Delete', 'Excluir'), 'Delete'],
      [tr('Menu of the item', 'Menu do item'), tr('Right click', 'Botão direito')],
    ],
  },
  {
    title: tr('Anywhere', 'Em qualquer lugar'),
    rows: [
      [tr('Open database object', 'Abrir objeto do banco'), keys('Shift', 'D')],
      [tr('Command palette', 'Paleta de comandos'), keys('Shift', 'P')],
      [tr('Keyboard shortcuts', 'Atalhos de teclado'), keys('/')],
      [tr('Tab menu', 'Menu da aba'), tr('Right click on a tab', 'Botão direito na aba')],
      [tr('Close tab', 'Fechar aba'), tr('Middle click', 'Clique do meio')],
    ],
  },
]

function open(next: 'shortcuts' | 'about') {
  mode.value = next
  copied.value = false
  dialog.value?.showModal()
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" aria-labelledby="help-title" @click.self="dialog?.close()">
    <div class="body">
      <template v-if="mode === 'shortcuts'">
        <h2 id="help-title">{{ tr('Keyboard shortcuts', 'Atalhos de teclado') }}</h2>
        <section v-for="group in groups()" :key="group.title">
          <h3>{{ group.title }}</h3>
          <dl>
            <template v-for="[name, key] in group.rows" :key="name">
              <dt>{{ name }}</dt>
              <dd class="mono">{{ key }}</dd>
            </template>
          </dl>
        </section>
      </template>
      <template v-else>
        <h2 id="help-title" class="about"><img src="/logo.png" alt="" width="40" height="40" /> JustinBeaver</h2>
        <p class="muted">{{ tr('Version', 'Versão') }} {{ app.info?.version ?? '—' }}{{ app.info?.desktop ? ` · ${tr('desktop app', 'aplicativo desktop')}` : '' }}</p>
        <p>{{ tr('A desktop database client with JDBC support: browse, query and edit databases.', 'Um cliente de banco de dados desktop com suporte a JDBC: navegue, consulte e edite bancos.') }}</p>
        <div class="author">
          <p class="muted">{{ tr('Made by', 'Feito por') }}</p>
          <p class="name">Guilherme Pereira de Moraes Borba</p>
          <p class="contact">
            <span class="muted">{{ tr('Contact', 'Contato') }}:</span>
            <span class="mono email">{{ EMAIL }}</span>
            <button class="ghost" @click="copyEmail">{{ copied ? tr('Copied', 'Copiado') : tr('Copy', 'Copiar') }}</button>
          </p>
        </div>
      </template>
      <div class="actions"><button class="primary" @click="dialog?.close()">{{ tr('Close', 'Fechar') }}</button></div>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(560px, calc(100vw - 32px));
  max-height: 80vh;
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
  gap: 8px;
  padding: 16px;
}

h2 {
  margin: 0;
  font-size: 16px;
}

h2.about {
  display: flex;
  align-items: center;
  gap: 10px;
}

h3 {
  margin: 8px 0 4px;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--muted);
}

dl {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px 16px;
  margin: 0;
}

dt {
  margin: 0;
}

dd {
  margin: 0;
  color: var(--accent);
}

p {
  margin: 0;
}

.actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
}
.author {
  margin-top: 6px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
}

.author p {
  margin: 0;
}

.author .name {
  margin: 2px 0 6px;
  font-size: 15px;
  font-weight: 600;
}

.contact {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.email {
  user-select: all;
}
</style>
