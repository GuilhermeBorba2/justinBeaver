import { reactive } from 'vue'
import { cleanMenu, type MenuItem } from '@/lib/menu'

/** The one right-click menu of the window; `ContextMenuHost` draws it. */
export const contextMenu = reactive<{ open: boolean; x: number; y: number; items: MenuItem[] }>({ open: false, x: 0, y: 0, items: [] })

/** Shows `items` at the pointer (or at an explicit point); the page's own menu is suppressed. */
export function openContextMenu(where: MouseEvent | { x: number; y: number }, items: (MenuItem | false | null | undefined)[]) {
  if ('preventDefault' in where) {
    where.preventDefault()
    where.stopPropagation()
  }
  const x = 'clientX' in where ? where.clientX : where.x
  const y = 'clientY' in where ? where.clientY : where.y
  const clean = cleanMenu(items)
  if (clean.length === 0) return
  Object.assign(contextMenu, { open: true, x, y, items: clean })
}

export function closeContextMenu() {
  contextMenu.open = false
}
