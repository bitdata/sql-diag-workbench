import { tr, useLocale } from '../../i18n'
import { useEffect, useRef, useCallback } from 'react'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, Decoration, DecorationSet, ViewPlugin } from '@codemirror/view'
import { EditorState, StateField, StateEffect, Compartment, Annotation } from '@codemirror/state'
import { sql, PostgreSQL, MySQL, MSSQL, PLSQL } from '@codemirror/lang-sql'
import type { SqlDialect } from '../../types/session'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { searchKeymap } from '@codemirror/search'
import { syntaxHighlighting, indentOnInput } from '@codemirror/language'
import { sqlWorkbenchDark } from './sqlWorkbenchDark'
import { sqlWorkbenchLight } from './sqlWorkbenchLight'
import { SqlNodePosition } from '../../types/fragment'
import './SqlViewer.css'

interface SqlViewerProps {
  dialect?: SqlDialect
  value: string
  onChange?: (value: string) => void
  readOnly?: boolean
  onExecute?: () => void
  placeholder?: string
  theme?: 'dark' | 'light' | 'high-contrast'
  highlights?: SqlNodePosition[]
  onSelectNodeAtOffset?: (offset: number) => void
  selectedNodeId?: string | null
  /**
   * PR-2 (spec §6.1 item 2): non-null while the boot session POST is in
   * flight. Renders the "视觉提示" the spec requires alongside the read-only
   * gate — `readOnly` alone tells the user the editor is locked but not why.
   */
  bootHint?: string | null
}

/**
 * Module-level stable empty array for the {@link SqlViewer} `highlights`
 * default. Using a fresh `[]` inline would re-create an array on every render
 * and re-fire the highlights effect unnecessarily (the value flows into
 * useEffect deps at line ~251). CompareView relies on this default. Mirror of
 * LineagePanel.EMPTY_NODES — see CLAUDE.md rule #1.
 */
const EMPTY_HIGHLIGHTS: SqlNodePosition[] = []

/**
 * Marks a transaction that THIS component originates — the `value`-prop mirror below.
 * The update listener must not report such a transaction as an edit: `onChange` feeds
 * `setCurrentSql` → workspace.sql + isDirty → the debounced PUT autosave, so reporting
 * a programmatic sync rewrites the tab's SQL buffer. That is exactly how browsing a
 * facet (browse sets `value` to `selectedNode.sqlFragment`) used to overwrite the tab
 * buffer with the facet text and persist it — see
 * .scratch/sqlviewer-buffer-overwrite-2026-10/followup-browse-mode-overwrites-tab-buffer.md.
 *
 * An annotation rather than a `userEvent` allow-list: real undo/redo transactions carry
 * `userEvent: "undo"` / `"redo"` and no `input.*` name, so an allow-list would swallow
 * them. Pinned by SqlViewer.valueSync.test.tsx.
 */
const externalDocSync = Annotation.define<boolean>()

// Effects and state for managing highlights
const setHighlights = StateEffect.define<DecorationSet>()

const highlightField = StateField.define<DecorationSet>({
  create() {
    return Decoration.none
  },
  update(highlights, tr) {
    for (const e of tr.effects) {
      if (e.is(setHighlights)) {
        return e.value
      }
    }
    return highlights.map(tr.changes)
  },
  provide: f => EditorView.decorations.from(f)
})

function createHighlightDecorations(
  positions: SqlNodePosition[],
  selectedId: string | null | undefined,
  _darkTheme: boolean
): DecorationSet {
  const specs: any[] = []

  if (!positions || positions.length === 0) {
    return Decoration.none
  }

  for (const pos of positions) {
    if (!pos || !pos.start || !pos.end || typeof pos.start.offset !== 'number' || typeof pos.end.offset !== 'number') {
      console.warn('[SqlViewer] Skipping invalid position:', pos)
      continue
    }

    if (pos.start.offset < 0 || pos.end.offset < 0) {
      console.warn('[SqlViewer] Skipping negative offset position:', pos)
      continue
    }

    const isSelected = pos.nodeId === selectedId
    const className = isSelected
      ? 'cm-sql-node cm-sql-node--selected'
      : 'cm-sql-node'

    let deco: Decoration | null = null
    try {
      deco = Decoration.mark({
        class: className,
      })
    } catch (e) {
      console.error('[SqlViewer] Failed to create decoration:', e)
      continue
    }

    if (!deco) continue

    specs.push({
      from: pos.start.offset,
      to: pos.end.offset,
      value: deco,
    })
  }

  if (specs.length === 0) {
    return Decoration.none
  }

  try {
    const sortedSpecs = specs.sort((a, b) => {
      const aFrom = a.from ?? 0
      const bFrom = b.from ?? 0
      return aFrom - bFrom
    })

    const result = Decoration.set(sortedSpecs, true)
    return result
  } catch (e) {
    console.error('[SqlViewer] Decoration.set failed:', e)
    console.error('[SqlViewer] Specs:', JSON.stringify(specs.map(s => ({from: s.from, to: s.to, type: s.value?.spec?.class}))))
    return Decoration.none
  }
}

export default function SqlViewer({
  value,
  onChange,
  readOnly = false,
  onExecute,
  placeholder = tr("Paste SQL · Ctrl+Enter to execute · Ctrl+Shift+Enter to parse"),
  theme = 'dark',
  highlights = EMPTY_HIGHLIGHTS,
  onSelectNodeAtOffset,
  selectedNodeId,
  bootHint = null,
  dialect = 'postgresql',
}: SqlViewerProps) {
  useLocale()
  const containerRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  const onSelectNodeAtOffsetRef = useRef(onSelectNodeAtOffset)
  onSelectNodeAtOffsetRef.current = onSelectNodeAtOffset
  const readOnlyCompartment = useRef(new Compartment())
  const dialectCompartment = useRef(new Compartment())

  const onExecuteRef = useRef(onExecute)
  onExecuteRef.current = onExecute
  // CodeMirror installs its keymap once; commands must call the current workspace handler.
  const handleExecute = useCallback(() => {
    onExecuteRef.current?.()
  }, [])

  // Create/update editor when theme changes
  useEffect(() => {
    if (!containerRef.current) return

    // Destroy existing editor
    if (viewRef.current) {
      viewRef.current.destroy()
      viewRef.current = null
    }

    const executeKeymap = keymap.of([
      {
        key: 'Ctrl-Enter',
        run: () => {
          handleExecute()
          return true
        }
      },
      {
        key: 'Mod-Enter',
        run: () => {
          handleExecute()
          return true
        }
      }
    ])

    const updateListener = EditorView.updateListener.of((update) => {
      if (!update.docChanged) return
      // Our own `value` mirror is not a user edit — see `externalDocSync`.
      if (update.transactions.some((tr) => tr.annotation(externalDocSync))) return
      onChangeRef.current?.(update.state.doc.toString())
    })

    const clickHandler = EditorView.domEventHandlers({
      click: (event: MouseEvent, view: EditorView) => {
        const target = event.target as HTMLElement
        const nodeElement = target.closest('[data-node-id]') as HTMLElement | null
        if (nodeElement && onSelectNodeAtOffsetRef.current) {
          const nodeId = nodeElement.getAttribute('data-node-id')
          if (nodeId) {
            event.preventDefault()
            event.stopPropagation()
            try {
              const pos = view.posAtCoords({ x: event.clientX, y: event.clientY })
              if (pos != null) {
                onSelectNodeAtOffsetRef.current(pos)
              }
            } catch (e) {
              console.error('[SqlViewer] posAtCoords failed:', e)
            }
          }
        }
        return false
      }
    })

    const extensions = [
      lineNumbers(),
      highlightActiveLine(),
      highlightActiveLineGutter(),
      history(),
      indentOnInput(),
      dialectCompartment.current.of(sql({ dialect: { postgresql: PostgreSQL, mysql: MySQL, sqlserver: MSSQL, oracle: PLSQL }[dialect] })),
      theme === 'light' ? sqlWorkbenchLight() : sqlWorkbenchDark(),
      readOnlyCompartment.current.of([EditorState.readOnly.of(readOnly), EditorView.editable.of(!readOnly)]),
      executeKeymap,
      keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap]),
      updateListener,
      highlightField,
      clickHandler,
    ]

    const state = EditorState.create({
      doc: value,
      extensions
    })

    const view = new EditorView({
      state,
      parent: containerRef.current
    })

    viewRef.current = view

    return () => {
      view.destroy()
      viewRef.current = null
    }
  }, [theme]) // Recreate when theme changes

  useEffect(() => {
    viewRef.current?.dispatch({ effects: dialectCompartment.current.reconfigure(
      sql({ dialect: { postgresql: PostgreSQL, mysql: MySQL, sqlserver: MSSQL, oracle: PLSQL }[dialect] })
    ) })
  }, [dialect])

  // Update highlights when positions change
  useEffect(() => {
    try {
      const view = viewRef.current
      if (!view || !view.dom.isConnected) return

      if (!highlights || highlights.length === 0) {
        view.dispatch({ effects: setHighlights.of(Decoration.none) })
        return
      }

      const docLength = view.state.doc.length
      const clamped = highlights
        .filter(pos => pos && pos.start && pos.end
          && typeof pos.start.offset === 'number'
          && typeof pos.end.offset === 'number'
          && pos.start.offset >= 0
          && pos.end.offset >= 0)
        .map(pos => ({
          ...pos,
          start: { ...pos.start, offset: Math.min(pos.start.offset, docLength) },
          end: { ...pos.end, offset: Math.min(pos.end.offset, docLength) },
        }))

      const decos = createHighlightDecorations(clamped, selectedNodeId, theme !== 'light')
      view.dispatch({ effects: setHighlights.of(decos) })
    } catch (e) {
      console.error('[SqlViewer] Error in highlights effect:', e)
    }
  }, [highlights, selectedNodeId, theme])

  // Update readOnly state via Compartment reconfigure (no editor re-creation needed)
  useEffect(() => {
    const view = viewRef.current
    if (!view || !view.dom.isConnected) return
    view.dispatch({
      effects: readOnlyCompartment.current.reconfigure(
        [EditorState.readOnly.of(readOnly), EditorView.editable.of(!readOnly)]
      )
    })
  }, [readOnly])

  // Update editor content when value prop changes externally
  useEffect(() => {
    const view = viewRef.current
    if (!view || !view.dom.isConnected) return

    const currentDoc = view.state.doc.toString()
    if (value !== currentDoc) {
      try {
        view.dispatch({
          changes: {
            from: 0,
            to: currentDoc.length,
            insert: value
          },
          annotations: externalDocSync.of(true)
        })
      } catch (e) {
        console.error('[SqlViewer] Error updating editor content:', e)
      }
    }
  }, [value])

  return (
    // The shell exists only to hold the boot hint as an absolutely-positioned
    // overlay. It stays a flex child with `flex: 1` so the container's layout
    // is unchanged (the container itself is untouched by CSS below).
    <div className="sql-viewer-shell">
      <div className="sql-viewer-container" ref={containerRef} />
      {bootHint && (
        <div className="sql-viewer-boot-hint" role="status" aria-live="polite">
          {bootHint}
        </div>
      )}
    </div>
  )
}
