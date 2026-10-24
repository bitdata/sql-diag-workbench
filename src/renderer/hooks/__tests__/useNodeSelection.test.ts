// src/renderer/hooks/__tests__/useNodeSelection.test.ts
import { describe, it, expect, vi, afterEach } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { useNodeSelection } from '../useNodeSelection'
import type { SqlNodePosition } from '../../types/fragment'

const BACKEND = 'http://localhost:8080'

function makePosition(overrides: Partial<SqlNodePosition> = {}): SqlNodePosition {
  return {
    nodeId: 'node-1',
    type: 'TABLE_REF',
    label: 'users',
    start: { line: 1, column: 15, offset: 14 },
    end: { line: 1, column: 19, offset: 18 },
    ...overrides,
  }
}

function mockPositionsResponse(nodes: SqlNodePosition[]) {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve({ nodes }),
  }
}

/** Fetch mock that only ever rejects — with AbortError — when its signal is aborted. */
function mockAbortablePendingFetch(): typeof fetch {
  // The mock never resolves; cast because vi.fn's inferred Promise<unknown>
  // return is not assignable to fetch's Promise<Response>.
  return vi.fn((_url: string, init?: RequestInit) =>
    new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () =>
        reject(new DOMException('Aborted', 'AbortError'))
      )
    })
  ) as unknown as typeof fetch
}

describe('useNodeSelection', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('has empty initial state before any fetch', () => {
    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    expect(result.current.positions).toEqual([])
    expect(result.current.hoveredPosition).toBeNull()
    expect(result.current.selectedPosition).toBeNull()
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('fetchPositions POSTs sql+dialect and stores returned nodes', async () => {
    const nodes = [makePosition()]
    const fetchMock = vi.fn().mockResolvedValue(mockPositionsResponse(nodes))
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    await act(async () => {
      await result.current.fetchPositions('SELECT * FROM users', 'postgresql')
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`${BACKEND}/api/sql/positions`)
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ 'Content-Type': 'application/json' })
    expect(JSON.parse(init.body)).toEqual({
      sql: 'SELECT * FROM users',
      dialect: 'postgresql',
    })
    expect(init.signal).toBeInstanceOf(AbortSignal)
    expect(result.current.positions).toEqual(nodes)
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('fetchPositions with empty or whitespace-only sql clears positions without fetching', async () => {
    const fetchMock = vi.fn()
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    await act(async () => {
      await result.current.fetchPositions('   ', 'postgresql')
    })
    await act(async () => {
      await result.current.fetchPositions('', 'postgresql')
    })

    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.current.positions).toEqual([])
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('sets error from response body on non-ok response', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: () => Promise.resolve({ message: 'Unparseable SQL' }),
    })

    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    await act(async () => {
      await result.current.fetchPositions('SELEC broken', 'postgresql')
    })

    expect(result.current.error).toBe('Unparseable SQL')
    expect(result.current.positions).toEqual([])
    expect(result.current.loading).toBe(false)
  })

  it('falls back to a default message when the error body is not JSON', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.reject(new Error('not json')),
    })

    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    await act(async () => {
      await result.current.fetchPositions('SELECT 1', 'postgresql')
    })

    expect(result.current.error).toBe('Failed to fetch positions')
    expect(result.current.positions).toEqual([])
    expect(result.current.loading).toBe(false)
  })

  it('sets error on network failure', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error('Network error'))

    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    await act(async () => {
      await result.current.fetchPositions('SELECT 1', 'postgresql')
    })

    expect(result.current.error).toBe('Network error')
    expect(result.current.positions).toEqual([])
    expect(result.current.loading).toBe(false)
  })

  it('selectNodeAtOffset returns the containing position with inclusive bounds', async () => {
    const nodes = [
      makePosition({
        nodeId: 'n1',
        start: { line: 1, column: 1, offset: 0 },
        end: { line: 1, column: 5, offset: 4 },
      }),
      makePosition({
        nodeId: 'n2',
        start: { line: 1, column: 10, offset: 9 },
        end: { line: 1, column: 14, offset: 13 },
      }),
    ]
    globalThis.fetch = vi.fn().mockResolvedValue(mockPositionsResponse(nodes))

    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    await act(async () => {
      await result.current.fetchPositions('SELECT ...', 'postgresql')
    })

    expect(result.current.selectNodeAtOffset(0)?.nodeId).toBe('n1') // start inclusive
    expect(result.current.selectNodeAtOffset(4)?.nodeId).toBe('n1') // end inclusive
    expect(result.current.selectNodeAtOffset(9)?.nodeId).toBe('n2')
    expect(result.current.selectNodeAtOffset(13)?.nodeId).toBe('n2')
    expect(result.current.selectNodeAtOffset(6)).toBeNull() // gap between nodes
    expect(result.current.selectNodeAtOffset(999)).toBeNull() // past the end
  })

  it('selectNodeAtOffset returns null when no positions are loaded', () => {
    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    expect(result.current.selectNodeAtOffset(0)).toBeNull()
  })

  it('invokes onSelectNode with the nodeId when a position is selected', () => {
    const onSelectNode = vi.fn()
    const { result } = renderHook(() =>
      useNodeSelection({ backendUrl: BACKEND, onSelectNode })
    )

    act(() => {
      result.current.setSelectedPosition(makePosition({ nodeId: 'node-42' }))
    })

    expect(onSelectNode).toHaveBeenCalledTimes(1)
    expect(onSelectNode).toHaveBeenCalledWith('node-42')
  })

  // SUSPECTED BUG: onSelectNode is typed (nodeId: string | null) => void, but the
  // effect guards on `selectedPosition` truthiness, so clearing the selection (or
  // reset()) never notifies the parent — deselection is silently dropped.
  it('does not invoke onSelectNode when the selection is cleared (current behavior)', () => {
    const onSelectNode = vi.fn()
    const { result } = renderHook(() =>
      useNodeSelection({ backendUrl: BACKEND, onSelectNode })
    )

    act(() => {
      result.current.setSelectedPosition(makePosition({ nodeId: 'node-42' }))
    })
    expect(onSelectNode).toHaveBeenCalledWith('node-42')

    act(() => {
      result.current.setSelectedPosition(null)
    })

    // No second call with null — the parent cannot observe deselection.
    expect(onSelectNode).toHaveBeenCalledTimes(1)
  })

  it('setHoveredPosition updates hoveredPosition', () => {
    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))
    const pos = makePosition()

    act(() => {
      result.current.setHoveredPosition(pos)
    })
    expect(result.current.hoveredPosition).toBe(pos)

    act(() => {
      result.current.setHoveredPosition(null)
    })
    expect(result.current.hoveredPosition).toBeNull()
  })

  it('reset clears positions, hover and selection', async () => {
    const nodes = [makePosition()]
    globalThis.fetch = vi.fn().mockResolvedValue(mockPositionsResponse(nodes))

    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    await act(async () => {
      await result.current.fetchPositions('SELECT 1', 'postgresql')
    })
    act(() => {
      result.current.setHoveredPosition(nodes[0])
      result.current.setSelectedPosition(nodes[0])
    })
    expect(result.current.positions).toHaveLength(1)
    expect(result.current.hoveredPosition).not.toBeNull()

    act(() => {
      result.current.reset()
    })

    expect(result.current.positions).toEqual([])
    expect(result.current.hoveredPosition).toBeNull()
    expect(result.current.selectedPosition).toBeNull()
    expect(result.current.error).toBeNull()
  })

  it('clearPositions is the same function as reset', () => {
    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    expect(result.current.clearPositions).toBe(result.current.reset)
  })

  it('reset aborts an in-flight request and settles loading to false', async () => {
    globalThis.fetch = mockAbortablePendingFetch()

    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    act(() => {
      void result.current.fetchPositions('SELECT slow', 'postgresql')
    })
    expect(result.current.loading).toBe(true)

    act(() => {
      result.current.reset()
    })

    // The aborted request's rejection is swallowed and its finally block
    // settles loading back to false.
    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })
    expect(result.current.positions).toEqual([])
    expect(result.current.error).toBeNull()
  })

  // SUSPECTED BUG: when a second fetchPositions aborts the first, the stale
  it('stale aborted request does NOT flip loading to false while newer request is in flight', async () => {
    let resolveSecond: (value: unknown) => void = () => {}
    let call = 0
    globalThis.fetch = vi.fn((_url: string, init?: RequestInit) => {
      call++
      if (call === 1) {
        return new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('Aborted', 'AbortError'))
          )
        })
      }
      return new Promise((resolve) => {
        resolveSecond = resolve
      })
    }) as unknown as typeof fetch

    const { result } = renderHook(() => useNodeSelection({ backendUrl: BACKEND }))

    act(() => {
      void result.current.fetchPositions('SELECT a', 'postgresql')
    })
    act(() => {
      void result.current.fetchPositions('SELECT b', 'postgresql')
    })

    // The second request is still pending — loading must stay true
    // (the first request's finally should NOT clear it)
    expect(result.current.loading).toBe(true)

    // Resolving the second request completes normally.
    await act(async () => {
      resolveSecond(mockPositionsResponse([makePosition({ nodeId: 'n-b' })]))
    })

    expect(result.current.positions.map((p) => p.nodeId)).toEqual(['n-b'])
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })
})
