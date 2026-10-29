import { reactive } from 'vue'

/** An in-app text prompt: Electron has no `window.prompt`. `PromptDialog` draws it. */
export const promptState = reactive<{ open: boolean; title: string; label: string; value: string; confirmText: string }>({
  open: false,
  title: '',
  label: '',
  value: '',
  confirmText: 'OK',
})

let pending: ((value: string | null) => void) | undefined

/** Asks for one line of text; resolves null when cancelled. */
export function askText(title: string, value = '', label = '', confirmText = 'OK'): Promise<string | null> {
  pending?.(null)
  Object.assign(promptState, { open: true, title, value, label, confirmText })
  return new Promise((resolve) => {
    pending = resolve
  })
}

export function answerPrompt(value: string | null) {
  promptState.open = false
  pending?.(value)
  pending = undefined
}
