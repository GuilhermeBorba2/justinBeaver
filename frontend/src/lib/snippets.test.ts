import { describe, expect, it } from 'vitest'
import { DEFAULT_SNIPPETS, snippetBefore } from './snippets'

describe('snippets', () => {
  it('finds the template name before the cursor', () => {
    expect(snippetBefore('  sf', DEFAULT_SNIPPETS)?.template).toBe('select * from ${table}')
    expect(snippetBefore('select sf', DEFAULT_SNIPPETS)?.name).toBe('sf')
    expect(snippetBefore('sfx', DEFAULT_SNIPPETS)).toBeUndefined()
    expect(snippetBefore('', DEFAULT_SNIPPETS)).toBeUndefined()
  })
})
