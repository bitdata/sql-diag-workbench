import { EditorView } from '@codemirror/view'
import { Extension } from '@codemirror/state'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags } from '@lezer/highlight'

const workbenchLightTheme = EditorView.theme(
  {
    '&': {
      backgroundColor: '#ffffff',
      color: '#1a1a1a'
    },
    '.cm-content': {
      fontFamily: "'JetBrains Mono', 'Consolas', monospace",
      fontSize: '13px',
      lineHeight: '20px',
      caretColor: '#1a1a1a'
    },
    '.cm-cursor, .cm-dropCursor': {
      borderLeftColor: '#1a1a1a'
    },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
      backgroundColor: 'rgba(0, 120, 212, 0.3)'
    },
    '.cm-activeLine': {
      backgroundColor: '#f5f5f5'
    },
    '.cm-gutters': {
      backgroundColor: '#ffffff',
      color: '#8c8c8c',
      border: 'none',
      minWidth: '40px'
    },
    '.cm-gutterElement': {
      textAlign: 'right',
      paddingRight: '12px',
      fontSize: '12px'
    },
    '.cm-activeLineGutter': {
      backgroundColor: '#f5f5f5',
      color: '#5c5c5c'
    },
    '.cm-matchingBracket': {
      backgroundColor: 'rgba(0, 120, 212, 0.2)',
      outline: '1px solid #d0d0d0'
    },
    '.cm-foldPlaceholder': {
      backgroundColor: 'transparent',
      border: 'none',
      color: '#8c8c8c'
    },
    '.cm-tooltip': {
      backgroundColor: '#ffffff',
      border: '1px solid #d0d0d0',
      color: '#1a1a1a'
    }
  },
  { dark: false }
)

const workbenchLightHighlightStyle = HighlightStyle.define([
  { tag: tags.keyword, color: '#0000ff' },
  { tag: tags.string, color: '#a31515' },
  { tag: tags.number, color: '#098658' },
  { tag: tags.comment, color: '#008000', fontStyle: 'italic' },
  { tag: tags.function(tags.variableName), color: '#795e13' },
  { tag: tags.operator, color: '#1a1a1a' },
  { tag: tags.typeName, color: '#001080' },
  { tag: tags.name, color: '#001080' },
  { tag: tags.variableName, color: '#001080' },
  { tag: tags.labelName, color: '#001080' },
  { tag: tags.bool, color: '#0000ff' },
  { tag: tags.null, color: '#0000ff' },
  { tag: tags.paren, color: '#1a1a1a' },
  { tag: tags.separator, color: '#1a1a1a' },
  { tag: tags.bracket, color: '#1a1a1a' }
])

export function sqlWorkbenchLight(): Extension {
  return [workbenchLightTheme, syntaxHighlighting(workbenchLightHighlightStyle)]
}