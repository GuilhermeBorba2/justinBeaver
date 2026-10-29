import { computed } from 'vue'
import type { TransferKinds, TransferResult } from '@/api/types'
import { tr } from '@/lib/menu'
import { useGoogle } from '@/stores/google'
import { useUi } from '@/stores/ui'
import { useAppActions } from './appActions'

export const ALL_KINDS: TransferKinds = { connections: true, scripts: true, preferences: true }
export const CONNECTIONS: TransferKinds = { connections: true, scripts: false, preferences: false }
export const SCRIPTS: TransferKinds = { connections: false, scripts: true, preferences: false }

/** "3 connections, 5 scripts, preferences" in the chosen language. */
export function describeKinds(kinds: TransferKinds, counts?: Pick<TransferResult, 'connections' | 'scripts'>): string {
  const parts = [
    kinds.connections ? (counts ? tr(`${counts.connections} connection(s)`, `${counts.connections} conexão(ões)`) : tr('connections', 'conexões')) : '',
    kinds.scripts ? (counts ? tr(`${counts.scripts} script(s)`, `${counts.scripts} script(s)`) : tr('scripts', 'scripts')) : '',
    kinds.preferences ? tr('preferences', 'preferências') : '',
  ].filter(Boolean)
  return parts.join(', ')
}

/**
 * Save to / import from Google Drive, shared by the menus, the navigator, the scripts list and the sync dialog.
 * When nobody is signed in or sync is off, the account dialog opens instead: that is where it is set up.
 */
export function useDrive() {
  const google = useGoogle()
  const ui = useUi()
  const shell = useAppActions()

  const ready = computed(() => google.status?.signedIn === true && google.status.sync.enabled)
  const busy = computed(() => google.syncing)

  async function save(kinds: TransferKinds): Promise<boolean> {
    if (!ready.value) {
      shell.googleSync()
      return false
    }
    try {
      const result = await google.exportToDrive(kinds)
      ui.info(tr(`Saved to Google Drive: ${describeKinds(kinds, result)}`, `Salvo no Google Drive: ${describeKinds(kinds, result)}`))
      return true
    } catch (e) {
      ui.error(e instanceof Error ? e.message : String(e))
      return false
    }
  }

  async function load(kinds: TransferKinds): Promise<boolean> {
    if (!ready.value) {
      shell.googleSync()
      return false
    }
    const what = describeKinds(kinds)
    const ask = tr(
      `Import ${what} from Google Drive? Items with the same name here are replaced by the Drive version. Nothing is deleted.`,
      `Importar ${what} do Google Drive? Itens com o mesmo nome aqui são substituídos pela versão do Drive. Nada é apagado.`,
    )
    if (!window.confirm(ask)) return false
    try {
      const result = await google.importFromDrive(kinds)
      ui.info(tr(`Imported from Google Drive: ${describeKinds(kinds, result)}`, `Importado do Google Drive: ${describeKinds(kinds, result)}`))
      return true
    } catch (e) {
      ui.error(e instanceof Error ? e.message : String(e))
      return false
    }
  }

  return { ready, busy, save, load }
}
