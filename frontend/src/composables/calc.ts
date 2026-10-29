import { ref, watch } from 'vue'

const KEY = 'justinbeaver.calc.open'

function read(): boolean {
  try {
    return localStorage.getItem(KEY) === 'true'
  } catch {
    return false
  }
}

/** Whether result grids show the Calc panel; one switch for every grid, remembered across sessions. */
export const calcOpen = ref(read())

watch(calcOpen, (open) => {
  try {
    localStorage.setItem(KEY, String(open))
  } catch {
    // best effort
  }
})
