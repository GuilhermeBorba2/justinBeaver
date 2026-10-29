import { ref } from 'vue'
import type { Connection, ConnectionType } from '@/api/types'
import { connectionInput } from '@/lib/menu'
import { useConnections } from '@/stores/connections'
import { useUi } from '@/stores/ui'
import { useWorkspace } from '@/stores/workspace'
import { askText } from './prompt'

/** The connection last copied in the navigator; "Paste" makes a copy of it (DBeaver's Ctrl+C / Ctrl+V on a connection). */
const copied = ref<string | null>(null)

/** Operations on one saved connection, shared by the navigator's menu, the menu bar and the toolbar. */
export function useConnectionActions() {
  const connections = useConnections()
  const workspace = useWorkspace()
  const ui = useUi()

  function hasPending(id: string): boolean {
    return Object.entries(connections.txDirty).some(([key, dirty]) => dirty && connections.baseId(key) === id)
  }

  async function connect(c: Connection) {
    await ui.guard(() => connections.connect(c.id))
  }

  async function disconnect(c: Connection) {
    if (hasPending(c.id) && !window.confirm(`"${c.name}" has uncommitted changes. Disconnecting rolls them back. Disconnect anyway?`)) return
    await ui.guard(() => connections.disconnect(c.id))
  }

  /** "Invalidate/Reconnect": a fresh connection with the saved settings. */
  async function reconnect(c: Connection) {
    if (hasPending(c.id) && !window.confirm(`"${c.name}" has uncommitted changes. Reconnecting rolls them back. Reconnect anyway?`)) return
    const done = await ui.guard(async () => {
      await connections.disconnect(c.id)
      await connections.connect(c.id)
      return true
    })
    if (done) ui.info(`Reconnected ${c.name}`)
  }

  async function disconnectAll() {
    const live = connections.items.filter((c) => c.connected)
    if (live.some((c) => hasPending(c.id)) && !window.confirm('Some connections have uncommitted changes. Disconnecting rolls them back. Disconnect all anyway?')) return
    for (const c of live) await ui.guard(() => connections.disconnect(c.id))
  }

  async function newScript(c: Connection, sql = '') {
    if (!c.connected) {
      await connect(c)
      if (!connections.connectionOf(c.id)?.connected) return
    }
    workspace.openSql(c.id, c.name, sql)
  }

  async function rename(c: Connection) {
    const name = await askText('Rename connection', c.name, 'Name', 'Rename')
    if (name === null || !name.trim() || name.trim() === c.name) return
    const done = await ui.guard(() => connections.save(connectionInput(c, { name: name.trim() }), c.id))
    if (done) ui.info(`Renamed to ${name.trim()}`)
  }

  /** Changing the type or read-only flag reconnects an open connection so that it takes effect. */
  async function update(c: Connection, changes: Parameters<typeof connectionInput>[1], message: string) {
    if (c.connected && hasPending(c.id) && !window.confirm(`${message} reconnects and rolls back the uncommitted changes. Continue?`)) return
    const done = await ui.guard(async () => {
      await connections.save(connectionInput(c, changes), c.id)
      if (c.connected) {
        await connections.disconnect(c.id)
        await connections.connect(c.id)
      }
      return true
    })
    if (done) ui.info(`${c.name} updated`)
  }

  const setType = (c: Connection, type: ConnectionType) => (c.connectionType === type ? Promise.resolve() : update(c, { connectionType: type }, 'Changing the type'))
  const setReadOnly = (c: Connection, readOnly: boolean) => update(c, { readOnly }, 'Changing read-only')

  function copy(c: Connection) {
    copied.value = c.id
    ui.info(`Copied ${c.name}: use Paste to duplicate it`)
  }

  /** Duplicate the copied connection (settings only: a password is never copied). */
  async function paste() {
    const source = copied.value ? connections.byId.get(copied.value) : undefined
    if (!source) return
    const names = new Set(connections.items.map((c) => c.name))
    let name = `${source.name} (copy)`
    for (let n = 2; names.has(name); n++) name = `${source.name} (copy ${n})`
    const done = await ui.guard(() => connections.save(connectionInput(source, { name }), undefined))
    if (done) ui.info(`Created ${name}: enter its password in Edit connection`)
  }

  async function copyText(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text)
      ui.info(`${what} copied`)
    } catch {
      ui.error('The clipboard is not available')
    }
  }

  /** Connection settings as plain text for a ticket or a chat: never the password. */
  function details(c: Connection): string {
    const lines = [
      `Name: ${c.name}`,
      `Driver: ${connections.drivers.find((d) => d.id === c.driverId)?.label ?? c.driverId}`,
      c.url ? `URL: ${c.url}` : `Host: ${c.host ?? ''}:${c.port ?? ''}`,
      c.database ? `Database: ${c.database}` : '',
      c.user ? `User: ${c.user}` : '',
      `Type: ${c.connectionType}${c.readOnly ? ' (read-only)' : ''}`,
      c.ssh?.enabled ? `SSH tunnel: ${c.ssh.user ?? ''}@${c.ssh.host}:${c.ssh.port}` : '',
    ]
    return lines.filter(Boolean).join('\n')
  }

  return {
    copied,
    hasPending,
    connect,
    disconnect,
    reconnect,
    disconnectAll,
    newScript,
    rename,
    setType,
    setReadOnly,
    copy,
    paste,
    copyText,
    details,
  }
}
