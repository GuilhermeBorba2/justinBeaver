/**
 * What the menu bar and the command palette can ask of the SQL editor on screen. Each open editor registers its
 * commands under its tab id and removes them when it goes away; callers look up the active tab's.
 */
export interface EditorCommands {
  runStatement(newTab?: boolean): void
  runScript(fromCursor?: boolean): void
  cancel(): void
  explain(): void
  explainAnalyze(): void
  format(): void
  undo(): void
  redo(): void
  cut(): void
  copy(): void
  paste(): void
  selectAll(): void
  find(): void
  goToLine(): void
  toggleComment(): void
  changeCase(to: 'upper' | 'lower'): void
  trimSpaces(): void
  toggleWrap(): void
  isWrapping(): boolean
  /** Panel under the editor: 'results' (the grid), 'output' (server messages) or 'history' (this connection's statements). */
  showPanel(panel: 'results' | 'output' | 'history'): void
  panel(): 'results' | 'output' | 'history'
  importFile(): void
  exportFile(): void
  focus(): void
}

const registry = new Map<string, EditorCommands>()

export const editors = {
  register(tabId: string, commands: EditorCommands) {
    registry.set(tabId, commands)
  },
  unregister(tabId: string) {
    registry.delete(tabId)
  },
  get(tabId: string | null | undefined): EditorCommands | undefined {
    return tabId ? registry.get(tabId) : undefined
  },
}
