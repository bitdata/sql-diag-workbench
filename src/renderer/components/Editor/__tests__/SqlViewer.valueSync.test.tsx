// src/renderer/components/Editor/__tests__/SqlViewer.valueSync.test.tsx
//
// .scratch/sqlviewer-buffer-overwrite-2026-10/followup-browse-mode-overwrites-tab-buffer.md
//
// The defect: SqlViewer's "update editor content when the value prop changes"
// effect dispatches a doc replacement, and the updateListener reported EVERY
// `docChanged` update — including that programmatic one — through `onChange`.
// The renderer wires `onChange` → `setCurrentSql` → workspace.sql + isDirty →
// debounced PUT, so merely BROWSEING a facet (viewerSql = node.sqlFragment)
// overwrote the tab's SQL buffer and persisted it over ~800ms. Measured
// 2026-10-11: the edit tab that held `SUM(O.AMOUNT) * 2` came back from the
// backend holding the root facet's canonical SQL, byte-identical to the source
// tab's session.
//
// The tests below pin the classification the fix introduces: a transaction we
// originate ourselves is not a user edit; a transaction the editor originates
// is — including undo, which a `userEvent`-allow-list implementation would drop.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useState } from 'react'
import { render, cleanup, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EditorView } from '@codemirror/view'
import { undo } from '@codemirror/commands'
import SqlViewer from '../SqlViewer'

/** The editor's own view object, read back out of the DOM it rendered. */
function viewOf(container: HTMLElement): EditorView {
  const dom = container.querySelector('.cm-editor')
  expect(dom).not.toBeNull()
  const view = EditorView.findFromDOM(dom as HTMLElement)
  expect(view).not.toBeNull()
  return view as EditorView
}

function docOf(container: HTMLElement): string {
  return viewOf(container).state.doc.toString()
}

describe('SqlViewer — value prop vs. user edits (browse write-back ticket)', () => {
  afterEach(cleanup)

  it('Ctrl+Enter calls the latest workspace callback after an editor rerender', () => {
    const oldOwner = vi.fn(), newOwner = vi.fn()
    const { container, rerender } = render(<SqlViewer value="SELECT 1" onExecute={oldOwner} />)
    rerender(<SqlViewer value="SELECT 2" onExecute={newOwner} />)
    viewOf(container).focus()
    fireEvent.keyDown(container.querySelector('.cm-content')!, { key: 'Enter', code: 'Enter', ctrlKey: true, keyCode: 13 })
    expect(newOwner).toHaveBeenCalledTimes(1)
    expect(oldOwner).not.toHaveBeenCalled()
  })

  it('replaces the doc on an external value change without reporting it as an edit', () => {
    const onChange = vi.fn()
    const { container, rerender } = render(
      <SqlViewer value="SELECT a" onChange={onChange} readOnly />
    )
    expect(docOf(container)).toBe('SELECT a')

    // What node selection does: the value prop becomes the facet's SQL.
    rerender(<SqlViewer value="SELECT b -- facet" onChange={onChange} readOnly />)

    // The sync itself must still happen — otherwise this test would pass for the
    // wrong reason (an editor that simply stops mirroring the prop).
    expect(docOf(container)).toBe('SELECT b -- facet')
    // ...and it must not be reported upstream: that call is what overwrote the
    // tab buffer and marked it dirty.
    expect(onChange).not.toHaveBeenCalled()
  })

  it('leaves the owner buffer alone when browse swaps the value for a facet', async () => {
    // Mirrors App.tsx exactly: value={viewerSql} onChange={handleSqlChange}, with
    // browse deriving viewerSql from the selected node instead of the buffer. The
    // buffer here is the owner's state — i.e. workspace.sql, whose isDirty drives
    // the 800ms PUT autosave. Reading a facet must not reach it.
    function Harness() {
      const [buffer, setBuffer] = useState('SELECT full_query -- owned')
      const [mode, setMode] = useState<'input' | 'browse'>('input')
      return (
        <>
          <button type="button" onClick={() => setMode('browse')}>browse</button>
          <span data-testid="buffer">{buffer}</span>
          <SqlViewer
            value={mode === 'browse' ? 'SELECT facet' : buffer}
            onChange={setBuffer}
            readOnly={mode === 'browse'}
          />
        </>
      )
    }
    const { container } = render(<Harness />)

    await userEvent.click(container.querySelector('button') as HTMLElement)

    // Browse still displays the facet — that part is the design, not the defect.
    expect(docOf(container)).toBe('SELECT facet')
    // ...and the buffer the autosave persists is untouched.
    expect(container.querySelector('[data-testid="buffer"]')?.textContent).toBe('SELECT full_query -- owned')
  })

  it('still reports a real user edit', async () => {
    const onChange = vi.fn()
    const { container } = render(<SqlViewer value="SELECT a" onChange={onChange} />)

    await userEvent.type(container.querySelector('.cm-content') as HTMLElement, 'X')

    expect(docOf(container)).not.toBe('SELECT a')
    expect(onChange).toHaveBeenCalled()
    expect(onChange).toHaveBeenLastCalledWith(docOf(container))
  })

  it('still reports undo (a transaction the editor originates, not our sync)', async () => {
    const onChange = vi.fn()
    const { container } = render(<SqlViewer value="SELECT a" onChange={onChange} />)
    const view = viewOf(container)

    await userEvent.type(container.querySelector('.cm-content') as HTMLElement, 'X')
    expect(onChange).toHaveBeenCalledTimes(1)
    const afterTyping = docOf(container)

    onChange.mockClear()
    undo(view)

    expect(docOf(container)).toBe('SELECT a')
    expect(docOf(container)).not.toBe(afterTyping)
    expect(onChange).toHaveBeenCalledWith('SELECT a')
  })
})
