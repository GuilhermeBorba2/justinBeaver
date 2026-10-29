import { inject } from 'vue'
import { api } from '@/api/client'
import type { ColumnInfo, Connection, IsolationLevel } from '@/api/types'
import { maintenance, maintenanceTasks, qualified, renameTable, truncateTable, dropTable, type DdlContext, type Dialect, type TableRef } from '@/lib/ddl'
import { deleteRow, insertInto, selectAll, selectColumns, updateRow } from '@/lib/generateSql'
import { SEP, cleanMenu, tr, type MenuItem } from '@/lib/menu'
import type { NavDescriptor } from '@/lib/navigator'
import { useApp } from '@/stores/app'
import { useConnections } from '@/stores/connections'
import { usePrefs } from '@/stores/prefs'
import { useUi } from '@/stores/ui'
import { useWorkspace, type Tab } from '@/stores/workspace'
import { APP_ACTIONS, type AppActions } from './appActions'
import { calcOpen } from './calc'
import { useConnectionActions } from './connectionActions'
import { ALL_KINDS, CONNECTIONS, SCRIPTS, useDrive } from './drive'
import { editors } from './editorBus'
import { askText } from './prompt'
import { SCHEMA_SERVICE } from './schema'
import { TOOLS } from './tools'

const isMac = typeof navigator !== 'undefined' && /mac/i.test(navigator.platform)
export const MOD = isMac ? '⌘' : 'Ctrl+'

/** The shortcut text shown beside a menu entry: `keys('Shift', 'D')` is "Ctrl+Shift+D" (⌘⇧D on a Mac). */
export function keys(...parts: string[]): string {
  return isMac ? `⌘${parts.map((p) => (p === 'Shift' ? '⇧' : p === 'Alt' ? '⌥' : p)).join('')}` : `Ctrl+${parts.join('+')}`
}

export interface BarMenu {
  id: string
  label: string
  /** Built when the menu opens, so it reflects the current tab, connection and editor. */
  items: () => MenuItem[]
}

const ADMIN_SECTIONS = [
  { id: 'sessions', en: 'Sessions', pt: 'Sessões' },
  { id: 'locks', en: 'Locks', pt: 'Bloqueios' },
  { id: 'dashboard', en: 'Dashboard', pt: 'Painel' },
  { id: 'users', en: 'Users and roles', pt: 'Usuários e papéis' },
  { id: 'backup', en: 'Backup and restore', pt: 'Backup e restauração' },
] as const

const ISOLATION: { id: IsolationLevel; label: string }[] = [
  { id: 'READ_UNCOMMITTED', label: 'Read uncommitted' },
  { id: 'READ_COMMITTED', label: 'Read committed' },
  { id: 'REPEATABLE_READ', label: 'Repeatable read' },
  { id: 'SERIALIZABLE', label: 'Serializable' },
]

/** Tab menu: right-click on an editor/table tab. Needs only the workspace, so the shell can use it before it provides anything. */
export function tabMenuItems(tab: Tab): MenuItem[] {
  const workspace = useWorkspace()
  const index = workspace.tabs.findIndex((t) => t.id === tab.id)
  const others = workspace.tabs.filter((t) => t.id !== tab.id)
  return cleanMenu([
    { label: tr('Close', 'Fechar'), icon: 'close', action: () => workspace.close(tab.id) },
    { label: tr('Close others', 'Fechar outras'), disabled: others.length === 0, action: () => others.forEach((t) => workspace.close(t.id)) },
    {
      label: tr('Close tabs to the right', 'Fechar abas à direita'),
      disabled: index >= workspace.tabs.length - 1,
      action: () => workspace.tabs.slice(index + 1).forEach((t) => workspace.close(t.id)),
    },
    { label: tr('Close all', 'Fechar todas'), action: () => [...workspace.tabs].forEach((t) => workspace.close(t.id)) },
    SEP,
    {
      label: tr('Copy title', 'Copiar título'),
      icon: 'copy',
      action: () => void navigator.clipboard.writeText(tab.title).catch(() => undefined),
    },
    tab.kind === 'sql' && {
      label: tr('Export script…', 'Exportar script…'),
      icon: 'download',
      action: () => {
        workspace.activeId = tab.id
        // the editor of that tab answers once it is the visible one
        setTimeout(() => editors.get(tab.id)?.exportFile(), 0)
      },
    },
  ])
}

export function useMenus() {
  const connections = useConnections()
  const workspace = useWorkspace()
  const prefs = usePrefs()
  const app = useApp()
  const ui = useUi()
  const actions = useConnectionActions()
  const drive = useDrive()
  const schema = inject(SCHEMA_SERVICE, null)
  const tools = inject(TOOLS, null)
  const shell = inject(APP_ACTIONS, null) as AppActions

  /** The connection the menus act on: the active tab's, else the navigator's selection, else the first connected. */
  function target(): Connection | undefined {
    const tab = workspace.active
    if (tab) return connections.connectionOf(tab.connectionId)
    const focused = workspace.focusedConnectionId
    if (focused && connections.byId.has(focused)) return connections.byId.get(focused)
    return connections.items.find((c) => c.connected) ?? connections.items[0]
  }

  const editor = () => editors.get(workspace.activeId)

  function scriptsMenu(c: Connection | undefined): MenuItem[] {
    return workspace.scripts.slice(0, 15).map((s) => ({
      label: s.name,
      icon: 'script',
      action: () => void workspace.openScript(s.name, c?.id ?? null),
    }))
  }

  function adminMenu(c: Connection | undefined): MenuItem[] {
    return ADMIN_SECTIONS.map((s) => ({
      label: tr(s.en, s.pt),
      icon: 'activity',
      disabled: !c?.connected,
      action: () => c && workspace.openAdmin(c.id, c.name, s.id),
    }))
  }

  function compareMenu(): MenuItem[] {
    return [
      { label: tr('Compare schemas…', 'Comparar esquemas…'), icon: 'diagram', action: () => shell.compareSchema() },
      { label: tr('Compare data…', 'Comparar dados…'), icon: 'table', action: () => shell.tool('compare-data') },
      { label: tr('Migrate data…', 'Migrar dados…'), icon: 'upload', action: () => shell.tool('migrate') },
    ]
  }

  async function refreshNavigator() {
    for (const c of connections.items.filter((x) => x.connected)) connections.schemaChanged(c.id)
    ui.info(tr('Navigator refreshed', 'Navegador atualizado'))
  }

  // ---- right-click on a connection ----------------------------------------------------------------------------------

  /** DBeaver's connection menu: SQL editor, create, edit, tools, compare/migrate, security, copy/paste, rename, refresh. */
  function connectionMenu(c: Connection, hooks: { refresh(): void; navigateHere(): void }): MenuItem[] {
    const live = c.connected
    return cleanMenu([
      {
        label: tr('SQL editor', 'Editor SQL'),
        icon: 'sql',
        children: [
          { label: tr('New SQL script', 'Novo script SQL'), icon: 'plus', action: () => void actions.newScript(c) },
          { label: tr('Open SQL script', 'Abrir script SQL'), icon: 'script', children: scriptsMenu(c) },
          { label: tr('SQL history…', 'Histórico de SQL…'), icon: 'history', action: () => shell.tool('query-manager') },
        ],
      },
      {
        label: tr('Create', 'Criar'),
        icon: 'plus',
        children: [
          { label: tr('New connection…', 'Nova conexão…'), icon: 'database', action: () => shell.newConnection() },
          { label: tr('New SQL script', 'Novo script SQL'), icon: 'sql', action: () => void actions.newScript(c) },
        ],
      },
      SEP,
      { label: tr('Edit connection', 'Editar conexão'), icon: 'edit', shortcut: 'F4', action: () => shell.editConnection(c) },
      {
        label: tr('Connection type', 'Tipo de conexão'),
        children: (['DEV', 'TEST', 'PROD'] as const).map((type) => ({
          label: { DEV: tr('Development', 'Desenvolvimento'), TEST: tr('Test', 'Teste'), PROD: tr('Production', 'Produção') }[type],
          checked: c.connectionType === type,
          action: () => void actions.setType(c, type),
        })),
      },
      { label: tr('Navigate from here', 'Navegue daqui'), icon: 'database', action: hooks.navigateHere },
      SEP,
      { label: tr('Connect', 'Conectar'), icon: 'plug', disabled: live, action: () => void actions.connect(c) },
      { label: tr('Invalidate/Reconnect', 'Invalidar/Reconectar'), icon: 'reconnect', disabled: !live, action: () => void actions.reconnect(c) },
      { label: tr('Disconnect', 'Desconectar'), icon: 'plug-off', disabled: !live, action: () => void actions.disconnect(c) },
      SEP,
      { label: tr('Compare/Migrate', 'Comparar/Migrar'), icon: 'diagram', children: compareMenu() },
      { label: tr('Tools', 'Ferramentas'), icon: 'activity', children: adminMenu(c) },
      {
        label: tr('Security', 'Segurança'),
        icon: 'lock',
        children: [
          {
            label: tr('Read-only connection', 'Conexão somente leitura'),
            checked: c.readOnly,
            action: () => void actions.setReadOnly(c, !c.readOnly),
          },
        ],
      },
      SEP,
      {
        label: tr('Copy', 'Copiar'),
        icon: 'copy',
        shortcut: keys('C'),
        action: () => actions.copy(c),
      },
      { label: tr('Paste', 'Colar'), shortcut: keys('V'), disabled: !actions.copied.value, action: () => void actions.paste() },
      {
        label: tr('Copy advanced info', 'Copiar informações avançadas'),
        children: [
          { label: tr('Name', 'Nome'), action: () => void actions.copyText(c.name, 'Name') },
          { label: tr('JDBC URL / host', 'URL JDBC / host'), disabled: !(c.url || c.host), action: () => void actions.copyText(c.url ?? `${c.host}:${c.port ?? ''}`, 'Address') },
          { label: tr('All settings (no password)', 'Todas as configurações (sem senha)'), action: () => void actions.copyText(actions.details(c), 'Settings') },
        ],
      },
      SEP,
      { label: tr('Delete', 'Excluir'), icon: 'trash', danger: true, shortcut: 'Del', action: () => shell.deleteConnection(c) },
      { label: tr('Rename', 'Renomear'), icon: 'edit', shortcut: 'F2', action: () => void actions.rename(c) },
      { label: tr('Refresh', 'Atualizar'), icon: 'refresh', shortcut: 'F5', disabled: !live, action: hooks.refresh },
    ])
  }

  // ---- right-click on a database object -------------------------------------------------------------------------

  /** Generated SQL goes into a new script on the object's connection. */
  async function generate(node: NavDescriptor, make: (ctx: DdlContext, table: TableRef, columns: ColumnInfo[]) => string) {
    if (!schema || !node.table) return
    const ref: TableRef = { catalog: node.catalog ?? null, schema: node.schema ?? null, name: node.table }
    const sql = await ui.guard(async () => {
      const [ctx, details] = await Promise.all([
        schema.context(node.connectionId),
        api.tableDetails(node.connectionId, { catalog: ref.catalog, schema: ref.schema }, ref.name),
      ])
      return make(ctx, ref, details.columns)
    })
    if (sql !== undefined) workspace.openSql(node.connectionId, connections.labelOf(node.connectionId), sql)
  }

  async function copyName(node: NavDescriptor, full: boolean) {
    let text = node.label
    if (full && node.table && schema) {
      const ctx = await ui.guard(() => schema.context(node.connectionId))
      if (ctx) text = qualified(ctx, { catalog: node.catalog ?? null, schema: node.schema ?? null, name: node.table })
    }
    await actions.copyText(text, 'Name')
  }

  function nodeMenu(node: NavDescriptor, hooks: { refresh(): void; open(): void; expandable: boolean; dialect?: Dialect }): MenuItem[] {
    const c = connections.connectionOf(node.connectionId)
    const ref: TableRef | null = node.table ? { catalog: node.catalog ?? null, schema: node.schema ?? null, name: node.table } : null
    const bookmark = node.kind === 'table' && ref ? { connectionId: node.connectionId, catalog: ref.catalog, schema: ref.schema, table: ref.name } : null
    const starred = bookmark ? prefs.isBookmarked(bookmark) : false
    const newScript = (sql = '') => workspace.openSql(node.connectionId, connections.labelOf(node.connectionId), sql)
    const isTables = node.kind === 'group' && node.label === 'Tables'
    const dangerRun = (title: string, build: Parameters<NonNullable<typeof schema>['run']>[2]) => schema && void schema.run(node.connectionId, title, build, true)

    return cleanMenu([
      node.kind === 'table' && { label: tr('Open data', 'Abrir dados'), icon: 'table', shortcut: 'Enter', action: hooks.open },
      node.kind === 'object' && { label: tr('Open source', 'Abrir código'), icon: 'script', shortcut: 'Enter', action: hooks.open },
      hooks.expandable && node.kind !== 'table' && node.kind !== 'connection' && { label: tr('Expand / collapse', 'Expandir / recolher'), action: hooks.open },
      node.kind === 'table' && {
        label: tr('SQL editor', 'Editor SQL'),
        icon: 'sql',
        children: [
          { label: tr('New SQL script', 'Novo script SQL'), icon: 'plus', action: () => newScript() },
          { label: tr('Select from this table', 'Selecionar desta tabela'), action: () => void generate(node, (ctx, t) => selectAll(ctx, t)) },
        ],
      },
      node.kind !== 'table' && node.kind !== 'column' && node.kind !== 'object' && { label: tr('New SQL script', 'Novo script SQL'), icon: 'sql', action: () => newScript() },
      node.kind === 'table' && {
        label: tr('Generate SQL', 'Gerar SQL'),
        icon: 'script',
        children: [
          { label: 'SELECT', action: () => void generate(node, (ctx, t, cols) => selectColumns(ctx, t, cols)) },
          { label: 'INSERT', disabled: node.isView, action: () => void generate(node, insertInto) },
          { label: 'UPDATE', disabled: node.isView, action: () => void generate(node, updateRow) },
          { label: 'DELETE', disabled: node.isView, action: () => void generate(node, deleteRow) },
        ],
      },
      (node.kind === 'container' || isTables) && {
        label: tr('ER diagram', 'Diagrama ER'),
        icon: 'diagram',
        action: () => workspace.openDiagram(node.connectionId, node.catalog ?? null, node.schema ?? null, node.kind === 'group' ? connections.labelOf(node.connectionId) : node.label),
      },
      (node.kind === 'container' || isTables) && {
        label: tr('New table…', 'Nova tabela…'),
        icon: 'plus',
        action: () => void schema?.createTable(node.connectionId, node.catalog ?? null, node.schema ?? null),
      },
      bookmark && {
        label: starred ? tr('Remove bookmark', 'Remover favorito') : tr('Bookmark', 'Adicionar favorito'),
        icon: 'star',
        action: () => prefs.toggleBookmark(bookmark),
      },
      node.kind === 'table' &&
        ref &&
        !node.isView && {
          label: tr('Copy data to…', 'Copiar dados para…'),
          icon: 'download',
          action: () => tools?.copyData({ connectionId: node.connectionId, catalog: ref.catalog, schema: ref.schema, table: ref.name, where: '' }),
        },
      SEP,
      { label: tr('Copy name', 'Copiar nome'), icon: 'copy', action: () => void copyName(node, false) },
      !!node.table && { label: tr('Copy qualified name', 'Copiar nome completo'), action: () => void copyName(node, true) },
      node.kind === 'table' &&
        ref &&
        schema && {
          label: tr('Maintenance', 'Manutenção'),
          icon: 'gauge',
          children: maintenanceTasksFor(node, ref, hooks.dialect),
        },
      node.kind === 'table' &&
        ref &&
        !c?.readOnly && {
          label: tr('Rename…', 'Renomear…'),
          icon: 'edit',
          shortcut: 'F2',
          action: async () => {
            const name = await askText(tr('Rename table', 'Renomear tabela'), ref.name, tr('New name', 'Novo nome'), tr('Rename', 'Renomear'))
            if (name?.trim() && name.trim() !== ref.name) dangerRun(`Rename ${ref.name}`, (ctx) => renameTable(ctx, ref, name.trim()))
          },
        },
      node.kind === 'table' &&
        ref &&
        !node.isView &&
        !c?.readOnly && {
          label: tr('Truncate…', 'Truncar…'),
          icon: 'trash',
          danger: true,
          action: () => dangerRun(`Truncate ${ref.name}`, (ctx) => truncateTable(ctx, ref)),
        },
      node.kind === 'table' &&
        ref &&
        !c?.readOnly && {
          label: tr('Drop…', 'Excluir…'),
          icon: 'trash',
          danger: true,
          shortcut: 'Del',
          action: () => dangerRun(`Drop ${ref.name}`, (ctx) => dropTable(ctx, ref, node.isView === true, false)),
        },
      SEP,
      { label: tr('Refresh', 'Atualizar'), icon: 'refresh', shortcut: 'F5', action: hooks.refresh },
    ])
  }

  /** The tasks of the table's database; the dialect comes from the server (the caller fetched it before opening the menu). */
  function maintenanceTasksFor(node: NavDescriptor, ref: TableRef, dialect: Dialect = 'generic'): MenuItem[] {
    return maintenanceTasks(dialect).map((task) => ({
      label: task.label,
      action: () => schema && void schema.run(node.connectionId, `${task.label} · ${ref.name}`, (ctx) => maintenance(ctx, ref, task.id)),
    }))
  }

  // ---- the menu bar -------------------------------------------------------------------------------------------------

  function menuBar(): BarMenu[] {
    const e = () => editor()
    const noEditor = () => !e()
    const t = () => target()
    const sqlItem = (label: string, run: () => void, extra: Partial<MenuItem> = {}): MenuItem => ({ label, disabled: noEditor(), action: run, ...extra })
    /** A preference, so it works with no editor open too. */
    const upperToggle = (): MenuItem => ({
      label: tr('Upper-case keywords as you type', 'Palavras-chave em maiúsculas ao digitar'),
      checked: prefs.state.uppercaseKeywords,
      action: () => (prefs.state.uppercaseKeywords = !prefs.state.uppercaseKeywords),
    })

    return [
      {
        id: 'file',
        label: tr('File', 'Arquivo'),
        items: () =>
          cleanMenu([
            { label: tr('New connection…', 'Nova conexão…'), icon: 'database', action: () => shell.newConnection() },
            { label: tr('New SQL script', 'Novo script SQL'), icon: 'sql', disabled: !t(), action: () => t() && void actions.newScript(t()!) },
            { label: tr('Open SQL script', 'Abrir script SQL'), icon: 'script', disabled: workspace.scripts.length === 0, children: scriptsMenu(t()) },
            SEP,
            sqlItem(tr('Import SQL script…', 'Importar script SQL…'), () => e()?.importFile(), { icon: 'upload' }),
            sqlItem(tr('Export SQL script…', 'Exportar script SQL…'), () => e()?.exportFile(), { icon: 'download' }),
            SEP,
            { label: tr('Import connections from DBeaver…', 'Importar conexões do DBeaver…'), icon: 'upload', action: () => shell.tool('import-dbeaver') },
            {
              label: 'Google Drive',
              icon: 'cloud',
              children: [
                { label: tr('Save everything to Google Drive', 'Salvar tudo no Google Drive'), icon: 'upload', action: () => void drive.save(ALL_KINDS) },
                { label: tr('Save connections to Google Drive', 'Salvar conexões no Google Drive'), action: () => void drive.save(CONNECTIONS) },
                { label: tr('Save scripts to Google Drive', 'Salvar scripts no Google Drive'), action: () => void drive.save(SCRIPTS) },
                SEP,
                { label: tr('Import everything from Google Drive', 'Importar tudo do Google Drive'), icon: 'download', action: () => void drive.load(ALL_KINDS) },
                { label: tr('Import connections from Google Drive', 'Importar conexões do Google Drive'), action: () => void drive.load(CONNECTIONS) },
                { label: tr('Import scripts from Google Drive', 'Importar scripts do Google Drive'), action: () => void drive.load(SCRIPTS) },
                SEP,
                { label: tr('Account & sync…', 'Conta e sincronização…'), icon: 'settings', action: () => shell.googleSync() },
              ],
            },
            SEP,
            { label: tr('Close tab', 'Fechar aba'), disabled: !workspace.active, action: () => workspace.active && workspace.close(workspace.active.id) },
            { label: tr('Close all tabs', 'Fechar todas as abas'), disabled: workspace.tabs.length === 0, action: () => [...workspace.tabs].forEach((x) => workspace.close(x.id)) },
            SEP,
            { label: tr('Preferences…', 'Preferências…'), icon: 'settings', action: () => shell.tool('preferences') },
            { label: tr('Quit', 'Sair'), icon: 'power', action: () => void app.quit() },
          ]),
      },
      {
        id: 'edit',
        label: tr('Edit', 'Editar'),
        items: () => {
          const table = workspace.active?.kind === 'table' ? workspace.active : null
          return cleanMenu([
            sqlItem(tr('Undo', 'Desfazer'), () => e()?.undo(), { shortcut: keys('Z') }),
            sqlItem(tr('Redo', 'Refazer'), () => e()?.redo(), { shortcut: keys('Shift', 'Z') }),
            SEP,
            sqlItem(tr('Cut', 'Recortar'), () => e()?.cut(), { shortcut: keys('X') }),
            sqlItem(tr('Copy', 'Copiar'), () => e()?.copy(), { shortcut: keys('C') }),
            sqlItem(tr('Paste', 'Colar'), () => e()?.paste(), { shortcut: keys('V') }),
            sqlItem(tr('Select all', 'Selecionar tudo'), () => e()?.selectAll(), { shortcut: keys('A') }),
            SEP,
            sqlItem(tr('Find/Replace…', 'Localizar/Substituir…'), () => e()?.find(), { icon: 'search', shortcut: keys('F') }),
            sqlItem(tr('Go to line…', 'Ir para a linha…'), () => e()?.goToLine(), { shortcut: keys('Alt', 'G') }),
            SEP,
            {
              label: tr('Format', 'Formatar'),
              children: [
                sqlItem(tr('Format SQL', 'Formatar SQL'), () => e()?.format(), { shortcut: keys('Shift', 'F') }),
                sqlItem(tr('Toggle comment', 'Alternar comentário'), () => e()?.toggleComment(), { shortcut: keys('/') }),
                sqlItem(tr('To upper case', 'Para maiúsculas'), () => e()?.changeCase('upper')),
                sqlItem(tr('To lower case', 'Para minúsculas'), () => e()?.changeCase('lower')),
                sqlItem(tr('Trim trailing spaces', 'Remover espaços no fim das linhas'), () => e()?.trimSpaces()),
                sqlItem(tr('Word wrap', 'Quebra de linha'), () => e()?.toggleWrap(), { checked: e()?.isWrapping() === true }),
                SEP,
                upperToggle(),
              ],
            },
            table && {
              label: prefs.isBookmarked({ connectionId: table.connectionId, catalog: table.catalog, schema: table.schema, table: table.table })
                ? tr('Remove bookmark', 'Remover favorito')
                : tr('Bookmark this table', 'Favoritar esta tabela'),
              icon: 'star',
              action: () => prefs.toggleBookmark({ connectionId: table.connectionId, catalog: table.catalog, schema: table.schema, table: table.table }),
            },
          ])
        },
      },
      {
        id: 'navigate',
        label: tr('Navigate', 'Navegar'),
        items: () => [
          { label: tr('Open database object…', 'Abrir objeto do banco…'), icon: 'search', shortcut: keys('Shift', 'D'), action: () => shell.findObject() },
          SEP,
          {
            label: tr('Next tab', 'Próxima aba'),
            icon: 'next',
            disabled: workspace.tabs.length < 2,
            action: () => stepTab(1),
          },
          { label: tr('Previous tab', 'Aba anterior'), icon: 'prev', disabled: workspace.tabs.length < 2, action: () => stepTab(-1) },
          SEP,
          { label: tr('Refresh navigator', 'Atualizar navegador'), icon: 'refresh', disabled: !connections.items.some((c) => c.connected), action: () => void refreshNavigator() },
          { label: tr('Show/hide navigator', 'Mostrar/ocultar navegador'), action: () => shell.toggleSidebar() },
        ],
      },
      {
        id: 'search',
        label: tr('Search', 'Procurar'),
        items: () => [
          { label: tr('Database object…', 'Objeto do banco…'), icon: 'search', shortcut: keys('Shift', 'D'), action: () => shell.findObject() },
          sqlItem(tr('Text in editor…', 'Texto no editor…'), () => e()?.find(), { shortcut: keys('F') }),
          { label: tr('SQL history…', 'Histórico de SQL…'), icon: 'history', action: () => shell.tool('query-manager') },
          { label: tr('Command palette…', 'Paleta de comandos…'), shortcut: keys('Shift', 'P'), action: () => shell.palette() },
        ],
      },
      {
        id: 'sql',
        label: tr('SQL editor', 'Editor SQL'),
        items: () =>
          cleanMenu([
            { label: tr('New SQL script', 'Novo script SQL'), icon: 'plus', disabled: !t(), action: () => t() && void actions.newScript(t()!) },
            { label: tr('Open SQL script', 'Abrir script SQL'), icon: 'script', disabled: workspace.scripts.length === 0, children: scriptsMenu(t()) },
            SEP,
            sqlItem(tr('Execute statement', 'Executar instrução SQL'), () => e()?.runStatement(), { icon: 'play', shortcut: keys('Enter') }),
            sqlItem(tr('Execute in a new tab', 'Executar SQL em uma nova aba'), () => e()?.runStatement(true), { shortcut: keys('\\') }),
            sqlItem(tr('Execute script', 'Executar script SQL'), () => e()?.runScript(), { shortcut: 'Alt+X' }),
            sqlItem(tr('Execute script from cursor', 'Executar script da posição'), () => e()?.runScript(true)),
            sqlItem(tr('Cancel running query', 'Cancelar consulta ativa'), () => e()?.cancel(), { icon: 'stop' }),
            SEP,
            sqlItem(tr('Explain plan', 'Explicar o plano de execução'), () => e()?.explain(), { shortcut: keys('Shift', 'E') }),
            sqlItem(tr('Explain analyze', 'Explicar e analisar (executa)'), () => e()?.explainAnalyze()),
            SEP,
            sqlItem(tr('Format SQL', 'Formatar SQL'), () => e()?.format(), { shortcut: keys('Shift', 'F') }),
            SEP,
            {
              label: tr('Panels', 'Painéis'),
              children: (['results', 'output', 'history'] as const).map((panel) =>
                sqlItem(
                  { results: tr('Results', 'Resultados'), output: tr('Server output', 'Saída do servidor'), history: tr('Execution log', 'Log de execução') }[panel],
                  () => e()?.showPanel(panel),
                  { checked: e()?.panel() === panel },
                ),
              ),
            },
            { label: tr('Result calculator', 'Calculadora de resultados'), icon: 'sigma', checked: calcOpen.value, action: () => (calcOpen.value = !calcOpen.value) },
            sqlItem(tr('Word wrap', 'Quebra de linha'), () => e()?.toggleWrap(), { checked: e()?.isWrapping() === true }),
            upperToggle(),
          ]),
      },
      {
        id: 'database',
        label: tr('Database', 'Banco de dados'),
        items: () => {
          const c = t()
          const live = c?.connected === true
          const auto = c ? connections.autoCommit[c.id] : undefined
          const level = c ? connections.isolation[c.id] : undefined
          return cleanMenu([
            { label: tr('New connection…', 'Nova conexão…'), icon: 'plus', action: () => shell.newConnection() },
            { label: tr('Edit connection', 'Editar conexão'), icon: 'edit', shortcut: 'F4', disabled: !c, action: () => c && shell.editConnection(c) },
            SEP,
            { label: tr('Connect', 'Conectar'), icon: 'plug', disabled: !c || live, action: () => c && void actions.connect(c) },
            { label: tr('Invalidate/Reconnect', 'Invalidar/Reconectar'), icon: 'reconnect', disabled: !live, action: () => c && void actions.reconnect(c) },
            { label: tr('Disconnect', 'Desconectar'), icon: 'plug-off', disabled: !live, action: () => c && void actions.disconnect(c) },
            { label: tr('Disconnect all', 'Desconectar todas'), disabled: !connections.items.some((x) => x.connected), action: () => void actions.disconnectAll() },
            SEP,
            {
              label: tr('Transaction mode', 'Modo de transação'),
              icon: 'tx',
              disabled: !live,
              children: [
                { label: 'Auto-commit', checked: auto !== false, action: () => c && void ui.guard(() => connections.setAutoCommit(c.id, true)) },
                { label: tr('Manual commit', 'Commit manual'), checked: auto === false, action: () => c && void ui.guard(() => connections.setAutoCommit(c.id, false)) },
                SEP,
                ...ISOLATION.map((l) => ({
                  label: l.label,
                  checked: level === l.id,
                  action: () => c && void ui.guard(() => connections.setIsolation(c.id, l.id)),
                })),
              ],
            },
            {
              label: 'Commit',
              icon: 'commit',
              disabled: !live || auto !== false,
              action: () => c && void ui.guard(() => connections.endTransaction(c.id, 'commit')).then(() => ui.info('Committed')),
            },
            {
              label: 'Rollback',
              icon: 'rollback',
              disabled: !live || auto !== false,
              action: () =>
                c &&
                void ui
                  .guard(() => connections.endTransaction(c.id, 'rollback'))
                  .then(() => {
                    connections.dataChanged(c.id)
                    ui.info('Rolled back')
                  }),
            },
            { label: tr('Read-only connection', 'Conexão somente leitura'), icon: 'lock', disabled: !c, checked: c?.readOnly === true, action: () => c && void actions.setReadOnly(c, !c.readOnly) },
            SEP,
            { label: tr('Compare/Migrate', 'Comparar/Migrar'), icon: 'diagram', children: compareMenu() },
            { label: tr('Tools', 'Ferramentas'), icon: 'activity', children: adminMenu(c) },
            SEP,
            { label: tr('Driver manager…', 'Gerenciador de drivers…'), icon: 'plug', action: () => shell.tool('drivers') },
            { label: tr('Scheduled tasks…', 'Tarefas agendadas…'), icon: 'history', action: () => shell.tool('tasks') },
          ])
        },
      },
      {
        id: 'window',
        label: tr('Window', 'Janela'),
        items: () => {
          const size = prefs.state.editorFontSize
          const tabs = workspace.tabs
          return cleanMenu([
            { label: tr('Next tab', 'Próxima aba'), disabled: tabs.length < 2, action: () => stepTab(1) },
            { label: tr('Previous tab', 'Aba anterior'), disabled: tabs.length < 2, action: () => stepTab(-1) },
            tabs.length > 0 && SEP,
            ...tabs.slice(0, 12).map(
              (tab): MenuItem => ({ label: tab.title, checked: tab.id === workspace.activeId, action: () => (workspace.activeId = tab.id) }),
            ),
            SEP,
            { label: tr('Show/hide navigator', 'Mostrar/ocultar navegador'), action: () => shell.toggleSidebar() },
            {
              label: tr('Theme', 'Tema'),
              children: (['system', 'light', 'dark'] as const).map((theme) => ({
                label: { system: tr('System', 'Sistema'), light: tr('Light', 'Claro'), dark: tr('Dark', 'Escuro') }[theme],
                checked: prefs.state.theme === theme,
                action: () => (prefs.state.theme = theme),
              })),
            },
            {
              label: tr('Language', 'Idioma'),
              children: (['auto', 'en', 'pt'] as const).map((language) => ({
                label: { auto: tr('Automatic (browser)', 'Automático (navegador)'), en: 'English', pt: 'Português' }[language],
                checked: prefs.state.language === language,
                action: () => (prefs.state.language = language),
              })),
            },
            { label: tr('Colors…', 'Cores…'), icon: 'settings', action: () => shell.tool('preferences') },
            {
              label: tr('Editor font size', 'Tamanho da fonte do editor'),
              children: [
                { label: tr('Larger', 'Aumentar'), disabled: size >= 32, action: () => (prefs.state.editorFontSize = Math.min(32, size + 1)) },
                { label: tr('Smaller', 'Diminuir'), disabled: size <= 8, action: () => (prefs.state.editorFontSize = Math.max(8, size - 1)) },
                { label: tr('Reset', 'Padrão'), action: () => (prefs.state.editorFontSize = 13) },
              ],
            },
            {
              label: tr('Full screen', 'Tela cheia'),
              checked: document.fullscreenElement !== null,
              shortcut: 'F11',
              action: () => void (document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen()).catch(() => undefined),
            },
          ])
        },
      },
      {
        id: 'help',
        label: tr('Help', 'Ajuda'),
        items: () => [
          { label: tr('Keyboard shortcuts', 'Atalhos de teclado'), shortcut: keys('/'), action: () => shell.shortcuts() },
          { label: tr('Command palette…', 'Paleta de comandos…'), shortcut: keys('Shift', 'P'), action: () => shell.palette() },
          SEP,
          {
            label: tr('Copy diagnostic info', 'Copiar informações de diagnóstico'),
            icon: 'copy',
            action: () =>
              void actions.copyText(
                [
                  `JustinBeaver ${app.info?.version ?? '?'}${app.info?.desktop ? ' (desktop)' : ''}`,
                  `Browser: ${navigator.userAgent}`,
                  `Connections: ${connections.items.length} (${connections.items.filter((c) => c.connected).length} connected)`,
                  `Drivers: ${connections.drivers.length}`,
                ].join('\n'),
                'Diagnostic info',
              ),
          },
          { label: tr('About JustinBeaver', 'Sobre o JustinBeaver'), action: () => shell.about() },
        ],
      },
    ]
  }

  function stepTab(by: number) {
    const tabs = workspace.tabs
    if (tabs.length === 0) return
    const at = tabs.findIndex((t) => t.id === workspace.activeId)
    workspace.activeId = tabs[(at + by + tabs.length) % tabs.length]!.id
  }

  return { menuBar, connectionMenu, nodeMenu, stepTab, target }
}
