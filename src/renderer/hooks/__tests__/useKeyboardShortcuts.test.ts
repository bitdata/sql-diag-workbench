// src/renderer/hooks/__tests__/useKeyboardShortcuts.test.ts
import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook, fireEvent } from '@testing-library/react'
import { useKeyboardShortcuts } from '../useKeyboardShortcuts'

function makeHandlers() {
  return {
    onParse: vi.fn(),
    onExecute: vi.fn(),
    onCancel: vi.fn(),
    onNewSession: vi.fn(),
  }
}

// The hook listens on window; key events dispatched on body bubble up to it,
// and body is an HTMLElement so the hook's target.closest() guard works.
function pressKey(target: HTMLElement, init: KeyboardEventInit): boolean {
  return fireEvent.keyDown(target, init)
}

describe('useKeyboardShortcuts', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('F5 triggers onExecute and prevents default', () => {
    const handlers = makeHandlers()
    renderHook(() => useKeyboardShortcuts(handlers))

    const notPrevented = pressKey(document.body, { key: 'F5' })

    expect(handlers.onExecute).toHaveBeenCalledTimes(1)
    expect(notPrevented).toBe(false) // e.preventDefault() was called
    expect(handlers.onParse).not.toHaveBeenCalled()
    expect(handlers.onCancel).not.toHaveBeenCalled()
    expect(handlers.onNewSession).not.toHaveBeenCalled()
  })

  it('Ctrl+Shift+Enter triggers onParse', () => {
    const handlers = makeHandlers()
    renderHook(() => useKeyboardShortcuts(handlers))

    const notPrevented = pressKey(document.body, { key: 'Enter', ctrlKey: true, shiftKey: true })

    expect(handlers.onParse).toHaveBeenCalledTimes(1)
    expect(notPrevented).toBe(false)
    expect(handlers.onExecute).not.toHaveBeenCalled()
  })

  it('Cmd+Shift+Enter (metaKey) triggers onParse', () => {
    const handlers = makeHandlers()
    renderHook(() => useKeyboardShortcuts(handlers))

    pressKey(document.body, { key: 'Enter', metaKey: true, shiftKey: true })

    expect(handlers.onParse).toHaveBeenCalledTimes(1)
  })

  it('Escape triggers onCancel', () => {
    const handlers = makeHandlers()
    renderHook(() => useKeyboardShortcuts(handlers))

    const notPrevented = pressKey(document.body, { key: 'Escape' })

    expect(handlers.onCancel).toHaveBeenCalledTimes(1)
    expect(notPrevented).toBe(false)
  })

  it('Ctrl+n triggers onNewSession', () => {
    const handlers = makeHandlers()
    renderHook(() => useKeyboardShortcuts(handlers))

    const notPrevented = pressKey(document.body, { key: 'n', ctrlKey: true })

    expect(handlers.onNewSession).toHaveBeenCalledTimes(1)
    expect(notPrevented).toBe(false)
  })

  it('Cmd+n (metaKey) triggers onNewSession', () => {
    const handlers = makeHandlers()
    renderHook(() => useKeyboardShortcuts(handlers))

    pressKey(document.body, { key: 'n', metaKey: true })

    expect(handlers.onNewSession).toHaveBeenCalledTimes(1)
  })

  it('does not fire for unrelated keys', () => {
    const handlers = makeHandlers()
    renderHook(() => useKeyboardShortcuts(handlers))

    pressKey(document.body, { key: 'a' })
    pressKey(document.body, { key: 'n' }) // 'n' without Ctrl/Cmd
    pressKey(document.body, { key: 'Enter' }) // Enter without Ctrl/Cmd

    expect(handlers.onParse).not.toHaveBeenCalled()
    expect(handlers.onExecute).not.toHaveBeenCalled()
    expect(handlers.onCancel).not.toHaveBeenCalled()
    expect(handlers.onNewSession).not.toHaveBeenCalled()
  })

  it('Ctrl+F5 does not trigger onExecute (modifier guard)', () => {
    const handlers = makeHandlers()
    renderHook(() => useKeyboardShortcuts(handlers))

    pressKey(document.body, { key: 'F5', ctrlKey: true })

    expect(handlers.onExecute).not.toHaveBeenCalled()
  })

  it('does not fire when enabled is false, and fires after re-enabling', () => {
    const handlers = makeHandlers()
    const { rerender } = renderHook(
      ({ enabled }: { enabled: boolean }) => useKeyboardShortcuts(handlers, enabled),
      { initialProps: { enabled: false } }
    )

    pressKey(document.body, { key: 'F5' })
    expect(handlers.onExecute).not.toHaveBeenCalled()

    rerender({ enabled: true })
    pressKey(document.body, { key: 'F5' })
    expect(handlers.onExecute).toHaveBeenCalledTimes(1)
  })

  it('ignores key events originating in a plain INPUT or TEXTAREA', () => {
    const handlers = makeHandlers()
    renderHook(() => useKeyboardShortcuts(handlers))

    const input = document.createElement('input')
    const textarea = document.createElement('textarea')
    document.body.append(input, textarea)

    pressKey(input, { key: 'F5' })
    pressKey(textarea, { key: 'Escape' })
    pressKey(input, { key: 'Enter', ctrlKey: true, shiftKey: true })

    expect(handlers.onExecute).not.toHaveBeenCalled()
    expect(handlers.onCancel).not.toHaveBeenCalled()
    expect(handlers.onParse).not.toHaveBeenCalled()
  })

  it('still fires when the target is inside a .cm-editor (CodeMirror exception)', () => {
    const handlers = makeHandlers()
    renderHook(() => useKeyboardShortcuts(handlers))

    const cmEditor = document.createElement('div')
    cmEditor.className = 'cm-editor'
    const textarea = document.createElement('textarea')
    cmEditor.appendChild(textarea)
    document.body.appendChild(cmEditor)

    pressKey(textarea, { key: 'Enter', ctrlKey: true, shiftKey: true })
    pressKey(textarea, { key: 'F5' })

    expect(handlers.onParse).toHaveBeenCalledTimes(1)
    expect(handlers.onExecute).toHaveBeenCalledTimes(1)
  })

  it('removes the window listener on unmount', () => {
    const handlers = makeHandlers()
    const { unmount } = renderHook(() => useKeyboardShortcuts(handlers))

    unmount()
    pressKey(document.body, { key: 'F5' })
    pressKey(document.body, { key: 'Escape' })

    expect(handlers.onExecute).not.toHaveBeenCalled()
    expect(handlers.onCancel).not.toHaveBeenCalled()
  })

  it('always invokes the latest handlers after props change', () => {
    const handlersA = makeHandlers()
    const handlersB = makeHandlers()
    const { rerender } = renderHook(
      ({ handlers }: { handlers: ReturnType<typeof makeHandlers> }) =>
        useKeyboardShortcuts(handlers),
      { initialProps: { handlers: handlersA } }
    )

    rerender({ handlers: handlersB })
    pressKey(document.body, { key: 'F5' })

    expect(handlersA.onExecute).not.toHaveBeenCalled()
    expect(handlersB.onExecute).toHaveBeenCalledTimes(1)
  })
})
