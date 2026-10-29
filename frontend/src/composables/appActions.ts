import { inject, type InjectionKey } from 'vue'
import type { Connection } from '@/api/types'
import type { ToolId } from '@/components/MainToolbar.vue'

/** What the menus can ask of the app shell, whose dialogs live in `App.vue`. */
export interface AppActions {
  newConnection(): void
  editConnection(connection: Connection): void
  deleteConnection(connection: Connection): void
  findObject(): void
  compareSchema(): void
  tool(id: ToolId): void
  googleSync(): void
  shortcuts(): void
  about(): void
  palette(): void
  toggleSidebar(): void
}

export const APP_ACTIONS: InjectionKey<AppActions> = Symbol('app-actions')

export function useAppActions(): AppActions {
  const actions = inject(APP_ACTIONS)
  if (!actions) throw new Error('App actions not provided')
  return actions
}
