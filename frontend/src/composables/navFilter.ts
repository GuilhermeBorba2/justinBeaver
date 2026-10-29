import { inject, ref, type InjectionKey, type Ref } from 'vue'

/** Text typed in the navigator filter box; matching is case-insensitive "contains". */
export const NAV_FILTER: InjectionKey<Ref<string>> = Symbol('nav-filter')

export function useNavFilter(): Ref<string> {
  return inject(NAV_FILTER, ref(''))
}
