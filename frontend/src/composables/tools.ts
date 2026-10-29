import { inject, type InjectionKey } from 'vue'

/** App-level dialogs other components can open. */
export interface Tools {
  copyData(source: { connectionId: string; catalog: string | null; schema: string | null; table: string; where: string }): void
}

export const TOOLS: InjectionKey<Tools> = Symbol('tools')

export function useTools(): Tools {
  const tools = inject(TOOLS)
  if (!tools) throw new Error('Tools not provided')
  return tools
}
