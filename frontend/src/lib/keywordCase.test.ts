import { PostgreSQL, sql } from '@codemirror/lang-sql'
import { EditorState } from '@codemirror/state'
import { describe, expect, it } from 'vitest'
import { upperKeywords } from './keywordCase'

function stateOf(doc: string, enabled = true) {
  return EditorState.create({ doc, extensions: [sql({ dialect: PostgreSQL }), upperKeywords(() => enabled)] })
}

/** Types `text` at `at` as the user would (one input event). */
function type(state: EditorState, at: number, text: string, userEvent = 'input.type') {
  return state.update({ changes: { from: at, insert: text }, selection: { anchor: at + text.length }, userEvent }).state.doc.toString()
}

describe('upperKeywords', () => {
  it('upper-cases a keyword when a space ends it', () => {
    expect(type(stateOf('select'), 6, ' ')).toBe('SELECT ')
  })

  it('works after the word, mid-statement, on any ending character', () => {
    expect(type(stateOf('SELECT a from'), 13, ' ')).toBe('SELECT a FROM ')
    expect(type(stateOf('SELECT a\nfrom'), 13, '\n  ', 'input')).toBe('SELECT a\nFROM\n  ')
    expect(type(stateOf('select 1 WHERE x in'), 19, '(')).toBe('select 1 WHERE x IN(')
    expect(type(stateOf('commit'), 6, ';')).toBe('COMMIT;')
  })

  it('leaves identifiers, strings, comments and quoted names alone', () => {
    expect(type(stateOf('select customer'), 15, ' ')).toBe('select customer ')
    expect(type(stateOf("select 'from"), 12, ' ')).toBe("select 'from ")
    expect(type(stateOf('-- select'), 9, ' ')).toBe('-- select ')
    expect(type(stateOf('select "from'), 12, ' ')).toBe('select "from ')
  })

  it('does not touch part of a longer word', () => {
    expect(type(stateOf('selected'), 8, ' ')).toBe('selected ')
    expect(type(stateOf('a_from'), 6, ' ')).toBe('a_from ')
  })

  it('does nothing when switched off', () => {
    expect(type(stateOf('select', false), 6, ' ')).toBe('select ')
  })

  it('ignores paste, completion and other programmatic changes', () => {
    expect(type(stateOf('select'), 6, ' ', 'input.paste')).toBe('select ')
    expect(type(stateOf('select'), 6, ' ', 'input.complete')).toBe('select ')
    const plain = stateOf('select').update({ changes: { from: 6, insert: ' ' } }).state.doc.toString()
    expect(plain).toBe('select ')
  })

  it('keeps already upper-case words and ignores a character typed inside a word', () => {
    expect(type(stateOf('SELECT'), 6, ' ')).toBe('SELECT ')
    expect(type(stateOf('selct'), 3, 'x')).toBe('selxct')
  })
})
