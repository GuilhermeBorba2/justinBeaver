import { inject, type InjectionKey } from 'vue'
import type { CellContent, CellValue } from '@/api/types'

export interface ValueViewerRequest {
  column: string
  typeName: string
  value: CellValue
  /** fetch the full content (table cells addressable by primary key) */
  load?: () => Promise<CellContent>
}

export const VALUE_VIEWER: InjectionKey<(request: ValueViewerRequest) => void> = Symbol('value-viewer')

export function useValueViewer(): (request: ValueViewerRequest) => void {
  const open = inject(VALUE_VIEWER)
  if (!open) throw new Error('Value viewer not provided')
  return open
}
