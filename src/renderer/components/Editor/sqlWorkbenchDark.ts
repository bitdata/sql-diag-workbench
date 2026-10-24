import { EditorView } from '@codemirror/view'
import { Extension } from '@codemirror/state'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags } from '@lezer/highlight'

const workbenchTheme = EditorView.theme(
  {
    '&': {
      backgroundColor: '#1a1a1a',
      color: '#cccccc'
    },
    '.cm-content': {
      fontFamily: "'JetBrains Mono', 'Consolas', monospace",
      fontSize: '13px',
      lineHeight: '20px',
      caretColor: '#cccccc'
    },
    '.cm-cursor, .cm-dropCursor': {
      borderLeftColor: '#cccccc'
    },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
      backgroundColor: 'rgba(0, 120, 212, 0.3)'
    },
    '.cm-activeLine': {
      backgroundColor: '#282828'
    },
    '.cm-gutters': {
      backgroundColor: '#1a1a1a',
      color: '#5c5c5c',
      border: 'none',
      minWidth: '40px'
    },
    '.cm-gutterElement': {
      textAlign: 'right',
      paddingRight: '12px',
      fontSize: '12px'
    },
    '.cm-activeLineGutter': {
      backgroundColor: '#282828',
      color: '#808080'
    },
    '.cm-matchingBracket': {
      backgroundColor: 'rgba(0, 120, 212, 0.2)',
      outline: '1px solid #5c5c5c'
    },
    '.cm-foldPlaceholder': {
      backgroundColor: 'transparent',
      border: 'none',
      color: '#5c5c5c'
    },
    '.cm-tooltip': {
      backgroundColor: '#2d2d2d',
      border: '1px solid #3c3c3c',
      color: '#cccccc'
    }
  },
  { dark: true }
)

const workbenchHighlightStyle = HighlightStyle.define([
  { tag: tags.keyword, color: '#569cd6' },
  { tag: tags.string, color: '#ce9178' },
  { tag: tags.number, color: '#b5cea8' },
  { tag: tags.comment, color: '#6a9955', fontStyle: 'italic' },
  { tag: tags.function(tags.variableName), color: '#dcdcaa' },
  { tag: tags.operator, color: '#d4d4d4' },
  { tag: tags.typeName, color: '#9cdcfe' },
  { tag: tags.name, color: '#9cdcfe' },
  { tag: tags.variableName, color: '#9cdcfe' },
  { tag: tags.labelName, color: '#9cdcfe' },
  { tag: tags.bool, color: '#569cd6' },
  { tag: tags.null, color: '#569cd6' },
  { tag: tags.paren, color: '#d4d4d4' },
  { tag: tags.separator, color: '#d4d4d4' },
  { tag: tags.bracket, color: '#d4d4d4' }
])

export function sqlWorkbenchDark(): Extension {
  return [workbenchTheme, syntaxHighlighting(workbenchHighlightStyle)]
}
