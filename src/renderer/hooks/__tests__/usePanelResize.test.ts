// src/renderer/hooks/__tests__/usePanelResize.test.ts
import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import type { MouseEvent as ReactMouseEvent } from 'react'
import { usePanelResize } from '../usePanelResize'

/** Minimal stand-in for the React.MouseEvent the hook receives from onDragStart-style handlers. */
function dragStartEvent(clientX = 0, clientY = 0) {
  return {
    clientX,
    clientY,
    preventDefault: vi.fn(),
  } as unknown as ReactMouseEvent
}

function moveOnDocument(clientX: number, clientY = 0) {
  document.dispatchEvent(new MouseEvent('mousemove', { clientX, clientY }))
}

function releaseOnDocument() {
  document.dispatchEvent(new MouseEvent('mouseup'))
}

/**
 * Build the right-area DOM the horizontal resize expects
 * (.sql-viewer + .results-panel children) with measured heights stubbed,
 * since jsdom has no layout.
 */
function buildRightArea(viewerHeight: number, resultsHeight: number) {
  const rightArea = document.createElement('div')
  const viewer = document.createElement('div')
  viewer.className = 'sql-viewer'
  const results = document.createElement('div')
  results.className = 'results-panel'
  rightArea.append(viewer, results)
  document.body.appendChild(rightArea)
  vi.spyOn(viewer, 'getBoundingClientRect')
    .mockReturnValue({ height: viewerHeight } as unknown as DOMRect)
  vi.spyOn(results, 'getBoundingClientRect')
    .mockReturnValue({ height: resultsHeight } as unknown as DOMRect)
  return { rightArea, viewer, results }
}

// The hook declares rightAreaRef as React.RefObject (readonly current);
// in a renderHook test we assign it manually, like the real app would via a ref prop.
function attachRightArea(
  result: { current: ReturnType<typeof usePanelResize> },
  el: HTMLDivElement | null
) {
  ;(result.current.rightAreaRef as { current: HTMLDivElement | null }).current = el
}

describe('usePanelResize', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('exposes sensible defaults', () => {
    const { result } = renderHook(() => usePanelResize())

    expect(result.current.leftWidth).toBe(240)
    expect(result.current.resizingVertical).toBe(false)
    expect(result.current.resizingHorizontal).toBe(false)
  })

  it('vertical drag updates leftWidth by the mouse delta', () => {
    const { result } = renderHook(() => usePanelResize())
    const startEvent = dragStartEvent(300)

    act(() => {
      result.current.startVerticalResize(startEvent)
    })

    expect(result.current.resizingVertical).toBe(true)
    expect(startEvent.preventDefault).toHaveBeenCalledTimes(1)

    act(() => moveOnDocument(350))
    expect(result.current.leftWidth).toBe(290) // 240 + 50

    act(() => moveOnDocument(250))
    expect(result.current.leftWidth).toBe(190) // 240 - 50

    act(() => releaseOnDocument())
    expect(result.current.resizingVertical).toBe(false)
  })

  it('clamps leftWidth to the 160..600 range while dragging', () => {
    const { result } = renderHook(() => usePanelResize())

    act(() => {
      result.current.startVerticalResize(dragStartEvent(300))
    })

    act(() => moveOnDocument(1300)) // 240 + 1000 = 1240 → max 600
    expect(result.current.leftWidth).toBe(600)

    act(() => moveOnDocument(-1000)) // 240 - 1300 = -1060 → min 160
    expect(result.current.leftWidth).toBe(160)

    act(() => releaseOnDocument())
    expect(result.current.leftWidth).toBe(160)
  })

  it('stops tracking the mouse after mouseup', () => {
    const { result } = renderHook(() => usePanelResize())

    act(() => {
      result.current.startVerticalResize(dragStartEvent(300))
    })
    act(() => moveOnDocument(350))
    expect(result.current.leftWidth).toBe(290)

    act(() => releaseOnDocument())

    // Listeners were removed: further moves must be ignored.
    act(() => moveOnDocument(500))
    expect(result.current.leftWidth).toBe(290)
    expect(result.current.resizingVertical).toBe(false)
  })

  it('horizontal drag resizes .sql-viewer and .results-panel, keeping the total height', () => {
    const { result } = renderHook(() => usePanelResize())
    const { rightArea, viewer, results } = buildRightArea(400, 200)
    attachRightArea(result, rightArea)

    act(() => {
      result.current.startHorizontalResize(dragStartEvent(0, 100))
    })
    expect(result.current.resizingHorizontal).toBe(true)

    act(() => moveOnDocument(0, 150)) // delta +50

    // style.flex = 'none' is normalized by jsdom into its longhands.
    expect(viewer.style.flexGrow).toBe('0')
    expect(viewer.style.flexShrink).toBe('0')
    expect(viewer.style.flexBasis).toBe('auto')
    expect(viewer.style.height).toBe('450px') // 400 + 50
    expect(results.style.flexGrow).toBe('0')
    expect(results.style.height).toBe('150px') // 600 - 450

    act(() => releaseOnDocument())
    expect(result.current.resizingHorizontal).toBe(false)
  })

  it('clamps the horizontal split so both panels keep at least 80px', () => {
    const { result } = renderHook(() => usePanelResize())
    const { rightArea, viewer, results } = buildRightArea(400, 200)
    attachRightArea(result, rightArea)

    act(() => {
      result.current.startHorizontalResize(dragStartEvent(0, 100))
    })

    // Drag far down: viewer would be 400 + 900 = 1300 → clamped to total - 80 = 520
    act(() => moveOnDocument(0, 1000))
    expect(viewer.style.height).toBe('520px')
    expect(results.style.height).toBe('80px')

    // Drag far up: viewer would be 400 - 1100 = -700 → clamped to min 80
    act(() => moveOnDocument(0, -1000))
    expect(viewer.style.height).toBe('80px')
    expect(results.style.height).toBe('520px')

    act(() => releaseOnDocument())
  })

  it('horizontal resize does nothing while the mouse is idle before first move', () => {
    const { result } = renderHook(() => usePanelResize())
    const { rightArea, viewer, results } = buildRightArea(400, 200)
    attachRightArea(result, rightArea)

    act(() => {
      result.current.startHorizontalResize(dragStartEvent(0, 100))
    })

    // No mousemove yet → styles untouched.
    expect(viewer.style.height).toBe('')
    expect(results.style.height).toBe('')

    act(() => releaseOnDocument())
  })

  it('exposes setLeftWidth for direct programmatic updates', () => {
    const { result } = renderHook(() => usePanelResize())

    act(() => {
      result.current.setLeftWidth(420)
    })

    expect(result.current.leftWidth).toBe(420)
  })

  it('resizingHorizontal stays false when rightArea is missing (early-return guard)', () => {
    const { result } = renderHook(() => usePanelResize())
    attachRightArea(result, null)

    act(() => {
      result.current.startHorizontalResize(dragStartEvent(0, 100))
    })

    expect(result.current.resizingHorizontal).toBe(false)
  })

  it('resizingHorizontal stays false when split panels are missing (early-return guard)', () => {
    const { result } = renderHook(() => usePanelResize())
    const emptyArea = document.createElement('div') // no .sql-viewer / .results-panel
    document.body.appendChild(emptyArea)
    attachRightArea(result, emptyArea)

    act(() => {
      result.current.startHorizontalResize(dragStartEvent(0, 100))
    })

    expect(result.current.resizingHorizontal).toBe(false)
  })
})
