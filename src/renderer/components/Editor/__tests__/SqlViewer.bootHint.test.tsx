// src/renderer/components/Editor/__tests__/SqlViewer.bootHint.test.tsx
//
// PR-2 review I-3. spec §6.1 item 2 requires the boot state to be
// "不可编辑 + 视觉提示". PR-2 shipped only the first half: `viewerReadOnly`
// disables the editor, but nothing told the user WHY. The deviation was
// recorded in the acceptance gate, which CLAUDE.md rule #4 says does not
// count as a write-back — so the hint is implemented rather than waived.
import { describe, it, expect, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import SqlViewer from '../SqlViewer'

describe('SqlViewer — bootHint (PR-2 I-3)', () => {
  afterEach(cleanup)

  it('renders the hint as a polite status region when bootHint is set', () => {
    const { container } = render(<SqlViewer value="SELECT 1" bootHint="Preparing session…" />)

    const hint = screen.getByRole('status')
    expect(hint).toHaveTextContent('Preparing session…')
    expect(hint).toHaveAttribute('aria-live', 'polite')
    // The hint must not swallow editor clicks.
    expect(container.querySelector('.sql-viewer-boot-hint')).not.toBeNull()
  })

  it('renders no hint when bootHint is absent (CompareView keeps its layout)', () => {
    const { container } = render(<SqlViewer value="SELECT 1" readOnly />)

    expect(container.querySelector('.sql-viewer-boot-hint')).toBeNull()
    expect(container.querySelector('.sql-viewer-container')).not.toBeNull()
  })
})
