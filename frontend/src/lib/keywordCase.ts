import { ensureSyntaxTree } from '@codemirror/language'
import { EditorState, type Extension } from '@codemirror/state'

/**
 * "Convert keyword case" (DBeaver's SQL editor option): when a typed space, newline or punctuation finishes a word
 * that the SQL parser knows as a keyword, the word becomes upper case. Strings, comments, quoted names and plain
 * identifiers are left alone because only parser keywords count; pasted and auto-completed text is not touched.
 */
const WORD = /\w/

export function upperKeywords(enabled: () => boolean): Extension {
  return EditorState.transactionFilter.of((tr) => {
    if (!tr.docChanged || !enabled()) return tr
    // typing and Enter, not paste, completion, undo or programmatic changes
    if (!tr.isUserEvent('input') || tr.isUserEvent('input.paste') || tr.isUserEvent('input.complete') || tr.isUserEvent('input.drop')) return tr

    let at = -1
    let count = 0
    tr.changes.iterChanges((_fromA, _toA, fromB, _toB, inserted) => {
      count++
      if (inserted.length > 0) at = fromB
    })
    // one insertion whose first character ends a word (a space, a newline, ";", "," …)
    if (count !== 1 || at <= 0) return tr
    const doc = tr.newDoc
    if (WORD.test(doc.sliceString(at, at + 1)) || !WORD.test(doc.sliceString(at - 1, at))) return tr

    let start = at
    while (start > 0 && WORD.test(doc.sliceString(start - 1, start))) start--
    const word = doc.sliceString(start, at)
    if (word === word.toUpperCase() || /^\d/.test(word)) return tr

    const tree = ensureSyntaxTree(tr.state, at, 50)
    if (!tree) return tr
    const node = tree.resolveInner(at, -1)
    if (node.name !== 'Keyword' || node.from !== start || node.to !== at) return tr
    // `sequential`: the position is in the document the typed character produced
    return [tr, { changes: { from: start, to: at, insert: word.toUpperCase() }, sequential: true }]
  })
}
