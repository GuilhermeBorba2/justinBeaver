import { ref } from 'vue'
import type { Connection, ConnectionInput } from '@/api/types'
import type { IconName } from '@/components/AppIcon.vue'

/** One entry of a menu (menu bar, right-click menu, command palette). `children` makes it a submenu. */
export interface MenuItem {
  label?: string
  icon?: IconName
  /** Display only: the key combination that triggers the same action elsewhere. */
  shortcut?: string
  disabled?: boolean
  danger?: boolean
  /** Shows a check mark (toggles, the current choice of a radio group). */
  checked?: boolean
  separator?: boolean
  children?: MenuItem[]
  action?: () => unknown
}

export const SEP: MenuItem = { separator: true }

/** The language chosen in Preferences: 'auto' follows the browser. The preferences store keeps it in sync. */
export type UiLanguage = 'auto' | 'en' | 'pt'
export const uiLanguage = ref<UiLanguage>('auto')

/**
 * Text in the chosen language: Portuguese or English; with 'auto', Portuguese when the browser's language is pt-*.
 * Reads `uiLanguage`, so a menu, computed or template that calls it follows a language change.
 */
export function tr(en: string, pt: string, language?: string): string {
  const chosen = language ?? (uiLanguage.value === 'auto' ? (typeof navigator === 'undefined' ? 'en' : navigator.language) : uiLanguage.value)
  return chosen.toLowerCase().startsWith('pt') ? pt : en
}

/**
 * Drops what would render badly: submenus with nothing in them, and separators that lead, trail or repeat.
 * Works recursively so callers can build menus with conditional pieces without tracking separators.
 */
export function cleanMenu(items: (MenuItem | false | null | undefined)[]): MenuItem[] {
  const out: MenuItem[] = []
  for (const item of items) {
    if (!item) continue
    if (item.separator) {
      if (out.length === 0 || out[out.length - 1]!.separator) continue
      out.push(item)
      continue
    }
    if (item.children) {
      const children = cleanMenu(item.children)
      if (children.length === 0) continue
      out.push({ ...item, children })
      continue
    }
    out.push(item)
  }
  while (out.length && out[out.length - 1]!.separator) out.pop()
  return out
}

/** Flattens a menu to its runnable leaves, with the submenu path ("Tools › Sessions") as the label: the command palette's list. */
export function flattenMenu(items: MenuItem[], trail: string[] = []): { label: string; item: MenuItem }[] {
  const out: { label: string; item: MenuItem }[] = []
  for (const item of items) {
    if (item.separator || !item.label) continue
    if (item.children) out.push(...flattenMenu(item.children, [...trail, item.label]))
    else if (item.action) out.push({ label: [...trail, item.label].join(' › '), item })
  }
  return out
}

/** A saved connection as the update payload (without a password: the stored one stays), with some fields replaced. */
export function connectionInput(c: Connection, changes: Partial<ConnectionInput> = {}): ConnectionInput {
  return {
    name: c.name,
    driverId: c.driverId,
    host: c.host,
    port: c.port,
    database: c.database,
    user: c.user,
    url: c.url,
    properties: c.properties,
    autoCommit: c.autoCommit,
    ssh: c.ssh
      ? {
          enabled: c.ssh.enabled,
          host: c.ssh.host,
          port: c.ssh.port,
          user: c.ssh.user,
          auth: c.ssh.auth,
          privateKeyPath: c.ssh.privateKeyPath,
          acceptNewHostKeys: c.ssh.acceptNewHostKeys,
        }
      : null,
    ssl: c.ssl,
    connectionType: c.connectionType,
    readOnly: c.readOnly,
    showAllDatabases: c.showAllDatabases,
    folder: c.folder,
    ...changes,
  }
}
