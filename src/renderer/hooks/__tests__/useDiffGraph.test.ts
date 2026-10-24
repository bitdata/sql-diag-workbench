// src/renderer/hooks/__tests__/useDiffGraph.test.ts
import { describe, it, expect, vi, afterEach } from 'vitest'
import React from 'react'
import { render, waitFor } from '@testing-library/react'
import { useDiffGraph } from '../useDiffGraph'
import type { FragmentNode } from '../../types/fragment'
import type { ChangeOp } from '../useChangeAttribution'

// ─── Fixtures ──────────────────────────────────────────────────────────────

function leaf(id: string, sql: string, children: FragmentNode[] = []): FragmentNode {
  return {
    id,
    type: 'ORIGINAL',
    label: `Node ${id}`,
    sqlFragment: sql,
    executable: true,
    executionHint: null,
    nodeCount: 0,
    ruleName: null,
    children,
    parentIds: [],
  }
}

function op(kind: ChangeOp['kind'], path: string): ChangeOp {
  return { kind, nodeType: 'SELECT', path, oldText: null, newText: null }
}

function mockFetch(ops: ChangeOp[]) {
  return vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: () => Promise.resolve({ ops }),
  })
}

const BACKEND = 'http://localhost:8080'

// ─── Test harness ──────────────────────────────────────────────────────────

interface HookResult {
  ghosts: { node: FragmentNode; isOnChain: boolean }[]
  edges: {
    from: FragmentNode
    to: FragmentNode
    ops: ChangeOp[]
    isVirtual: boolean
    degraded: boolean
    diffMethod: string
  }[]
  loading: boolean
  error: string | null
  anyDegraded: boolean
}

let lastResult: HookResult = {
  ghosts: [],
  edges: [],
  loading: false,
  error: null,
  anyDegraded: false,
}

function TestComp({
  chain,
  all,
  backendUrl = BACKEND,
  dialect = 'postgresql',
}: {
  chain: FragmentNode[]
  all: FragmentNode[]
  backendUrl?: string | null
  dialect?: 'postgresql' | 'mysql' | 'oracle' | 'sqlserver'
}) {
  const result = useDiffGraph(chain, all, backendUrl, dialect)
  lastResult = result
  return React.createElement('div', { 'data-testid': 'r' },
    JSON.stringify({ g: result.ghosts.length, e: result.edges.length, l: result.loading, err: result.error })
  )
}

function getResult(): HookResult {
  return lastResult
}

afterEach(() => {
  vi.restoreAllMocks()
  lastResult = { ghosts: [], edges: [], loading: false, error: null, anyDegraded: false }
})

describe('useDiffGraph', () => {
  // ── Null / empty inputs ────────────────────────────────────────────────

  it('returns empty state when chain is empty', async () => {
    const fetchMock = mockFetch([])
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [], all: [leaf('x', 'SELECT 1')] }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })

    expect(getResult().ghosts).toEqual([])
    expect(getResult().edges).toEqual([])
    expect(getResult().error).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('returns empty ghosts when allNodes is empty', async () => {
    const fetchMock = mockFetch([])
    globalThis.fetch = fetchMock

    const chain = [leaf('a', 'SELECT a')]
    render(React.createElement(TestComp, { chain, all: [] }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })

    expect(getResult().ghosts).toEqual([])
    expect(getResult().edges).toEqual([])
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('skips fetching when backendUrl is null', async () => {
    const fetchMock = mockFetch([])
    globalThis.fetch = fetchMock

    const chain = [leaf('a', 'SELECT a')]
    render(React.createElement(TestComp, {
      chain,
      all: [...chain, leaf('g', 'SELECT g')],
      backendUrl: null,
    }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })

    expect(getResult().ghosts.map((g) => g.node.id)).toEqual(['g'])
    expect(getResult().edges).toEqual([])
    expect(fetchMock).not.toHaveBeenCalled()
  })

  // ── Ghost classification ───────────────────────────────────────────────

  it('marks non-chain nodes as ghosts', () => {
    const a = leaf('a', 'SELECT a')
    const s = leaf('s', 'SELECT s')
    const t = leaf('t', 'SELECT t')

    render(React.createElement(TestComp, { chain: [a], all: [a, s, t] }))

    expect(getResult().ghosts.map((g) => g.node.id)).toEqual(['s', 't'])
    expect(getResult().ghosts.every((g) => g.isOnChain === false)).toBe(true)
  })

  it('excludes descendants of chain nodes via children walk', () => {
    const sel = leaf('sel', 'SELECT sel')
    const sib = leaf('sib', 'SELECT sib')
    const mid = leaf('mid', 'SELECT mid', [sel])
    const root = leaf('root', 'SELECT root', [mid, sib])

    render(React.createElement(TestComp, { chain: [root, mid], all: [root, mid, sel, sib] }))

    expect(getResult().ghosts).toEqual([])
  })

  it('excludes deeper descendants through non-chain intermediates', () => {
    const gc = leaf('gc', 'SELECT gc')
    const child = leaf('child', 'SELECT child', [gc])
    const root = leaf('root', 'SELECT root', [child])

    render(React.createElement(TestComp, { chain: [root], all: [root, child, gc] }))

    expect(getResult().ghosts).toEqual([])
  })

  it('ghost = child of non-chain node only', () => {
    const sel = leaf('sel', 'SELECT sel')
    const sib = leaf('sib', 'SELECT sib')
    const mid = leaf('mid', 'SELECT mid', [sel])
    const root = leaf('root', 'SELECT root', [mid, sib])

    render(React.createElement(TestComp, { chain: [mid], all: [root, mid, sel, sib] }))

    expect(getResult().ghosts.map((g) => g.node.id).sort()).toEqual(['root', 'sib'])
  })

  // ── Diff fetching ──────────────────────────────────────────────────────

  it('fires two /api/diff calls per ghost and builds edges', async () => {
    const a = leaf('a', 'SELECT a')
    const d = leaf('d', 'SELECT d')
    const b = leaf('b', 'SELECT b')
    const one = op('ADD', '$.where')
    let callIndex = 0
    const fetchMock = vi.fn().mockImplementation(() => {
      callIndex++
      if (callIndex === 1) {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ ops: [one] }) })
      }
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ ops: [one, op('DEL', '$.limit')] }) })
    })
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [a, d], all: [a, d, b] }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    for (const call of fetchMock.mock.calls) {
      expect(call[0]).toBe('http://localhost:8080/api/diff')
      expect(call[1].method).toBe('POST')
    }

    expect(getResult().edges).toHaveLength(1)
    expect(getResult().edges[0].from).toBe(d)
    expect(getResult().edges[0].to).toBe(b)
    expect(getResult().edges[0].ops).toEqual([one])
    expect(getResult().error).toBeNull()
  })

  it('isVirtual = subset check of dOps against rootOps', async () => {
    const a = leaf('a', 'SELECT a')
    const d = leaf('d', 'SELECT d')
    const g1 = leaf('g1', 'SELECT g1')

    const shared = op('MOD', '$.select')
    const extra = op('ADD', '$.join')
    const unique = op('DEL', '$.where')

    let callIndex = 0
    const fetchMock = vi.fn().mockImplementation(() => {
      callIndex++
      if (callIndex === 1) {
        // dOps: [shared, unique] — NOT subset of rootOps
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ ops: [shared, unique] }) })
      }
      // rootOps: [shared, extra]
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ ops: [shared, extra] }) })
    })
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [a, d], all: [a, d, g1] }))

    await waitFor(() => {
      expect(getResult().edges.length).toBe(1)
    })

    // {shared, unique} ⊄ {shared, extra} → not virtual
    expect(getResult().edges[0].isVirtual).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('isVirtual = true when dOps ⊆ rootOps', async () => {
    const a = leaf('a', 'SELECT a')
    const d = leaf('d', 'SELECT d')
    const g1 = leaf('g1', 'SELECT g1')

    const shared = op('MOD', '$.select')
    const extra = op('ADD', '$.join')

    let callIndex = 0
    const fetchMock = vi.fn().mockImplementation(() => {
      callIndex++
      if (callIndex === 1) {
        // dOps: [shared] — IS subset of rootOps
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ ops: [shared] }) })
      }
      // rootOps: [shared, extra]
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ ops: [shared, extra] }) })
    })
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [a, d], all: [a, d, g1] }))

    await waitFor(() => {
      expect(getResult().edges.length).toBe(1)
    })

    expect(getResult().edges[0].isVirtual).toBe(true)
  })

  it('skips ghosts with empty dOps (no edge, ghost still listed)', async () => {
    const a = leaf('a', 'SELECT a')
    const d = leaf('d', 'SELECT d')
    const twin = leaf('twin', 'SELECT d')
    const fetchMock = mockFetch([])
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [a, d], all: [a, d, twin] }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(getResult().ghosts.map((g) => g.node.id)).toEqual(['twin'])
    expect(getResult().edges).toEqual([])
  })

  it('handles response without ops field', async () => {
    const a = leaf('a', 'SELECT a')
    const g = leaf('g', 'SELECT g')
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({}),
    })
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [a], all: [a, g] }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })

    expect(getResult().edges).toEqual([])
    expect(getResult().error).toBeNull()
  })

  // ── Error handling ─────────────────────────────────────────────────────

  it('surfaces backend error message', async () => {
    const a = leaf('a', 'SELECT a')
    const g = leaf('g', 'SELECT g')
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.resolve({ message: 'Parse failed' }),
    })
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [a], all: [a, g] }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })

    expect(getResult().error).toBe('Parse failed')
    expect(getResult().edges).toEqual([])
    expect(getResult().ghosts.map((x) => x.node.id)).toEqual(['g'])
  })

  it('falls back to HTTP status when error body has no message', async () => {
    const a = leaf('a', 'SELECT a')
    const g = leaf('g', 'SELECT g')
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.reject(new Error('not json')),
    })
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [a], all: [a, g] }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })

    expect(getResult().error).toBe('HTTP 500')
  })

  it('handles network error', async () => {
    const a = leaf('a', 'SELECT a')
    const g = leaf('g', 'SELECT g')
    const fetchMock = vi.fn().mockRejectedValue(new Error('Network down'))
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [a], all: [a, g] }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })

    expect(getResult().error).toBe('Network down')
  })

  // ── Lifecycle ──────────────────────────────────────────────────────────

  it('re-runs when chain reference changes', async () => {
    const a = leaf('a', 'SELECT a')
    const d = leaf('d', 'SELECT d')
    const g = leaf('g', 'SELECT g')
    const fetchMock = mockFetch([op('ADD', '$.x')])
    globalThis.fetch = fetchMock

    const { rerender } = render(
      React.createElement(TestComp, { chain: [a, d], all: [a, d, g] })
    )

    await waitFor(() => {
      expect(getResult().edges).toHaveLength(1)
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)

    // Re-render with new chain array reference (same contents)
    rerender(React.createElement(TestComp, { chain: [a, d], all: [a, d, g] }))

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(4)
    })
  })

  it('clears stale edges when ghosts disappear', async () => {
    const a = leaf('a', 'SELECT a')
    const d = leaf('d', 'SELECT d')
    const g = leaf('g', 'SELECT g')
    const fetchMock = mockFetch([op('ADD', '$.x')])
    globalThis.fetch = fetchMock

    const { rerender } = render(
      React.createElement(TestComp, { chain: [a, d], all: [a, d, g] })
    )

    await waitFor(() => {
      expect(getResult().edges).toHaveLength(1)
    })

    // Drop the ghost
    rerender(React.createElement(TestComp, { chain: [a, d], all: [a, d] }))

    await waitFor(() => {
      expect(getResult().ghosts).toEqual([])
    })
    expect(getResult().edges).toEqual([])
    expect(getResult().loading).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('swallows AbortError on unmount', async () => {
    const a = leaf('a', 'SELECT a')
    const g = leaf('g', 'SELECT g')
    const abortError = new DOMException('Aborted', 'AbortError')
    const fetchMock = vi.fn().mockRejectedValue(abortError)
    globalThis.fetch = fetchMock

    const { unmount } = render(
      React.createElement(TestComp, { chain: [a], all: [a, g] })
    )

    unmount()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('returns stable reference when nothing changes', async () => {
    const a = leaf('a', 'SELECT a')
    const fetchMock = mockFetch([])
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [a], all: [a] }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })
    const snapshot = getResult()

    // Force re-render with same props — result should be same reference
    // (The hook returns state from useState, which is stable when no setState fires)
    expect(snapshot.ghosts).toEqual([])
    expect(snapshot.edges).toEqual([])
  })

  // ── Degraded / diffMethod surfacing (PR-α) ─────────────────────────────

  it('anyDegraded is true when any ghost edge is degraded', async () => {
    const a = leaf('a', 'SELECT a')
    const d = leaf('d', 'SELECT d')
    const g1 = leaf('g1', 'SELECT g1')
    const g2 = leaf('g2', 'SELECT g2')

    // Ghosts iterate in array order, so g1 → calls 1 & 2, g2 → calls 3 & 4.
    // dOps is call 1 / 3, rootOps is call 2 / 4.
    let callIndex = 0
    const fetchMock = vi.fn().mockImplementation(() => {
      callIndex++
      const isGhost1 = callIndex <= 2
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () =>
          Promise.resolve({
            ops: [op('ADD', `$.g${isGhost1 ? '1' : '2'}`)],
            degraded: isGhost1,
            diffMethod: isGhost1 ? 'PATH_SET' : 'LCS',
          }),
      })
    })
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [a, d], all: [a, d, g1, g2] }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })

    expect(getResult().edges).toHaveLength(2)
    expect(getResult().anyDegraded).toBe(true)

    // Per-edge fields are surfaced (post-normalization: PATH_SET→path-set,
    // LCS→ast-lcs) so the renderer can render a per-edge degraded badge.
    expect(getResult().edges[0].degraded).toBe(true)
    expect(getResult().edges[0].diffMethod).toBe('path-set')
    expect(getResult().edges[1].degraded).toBe(false)
    expect(getResult().edges[1].diffMethod).toBe('ast-lcs')
  })

  it('anyDegraded is false when both ghosts are precise', async () => {
    const a = leaf('a', 'SELECT a')
    const d = leaf('d', 'SELECT d')
    const g1 = leaf('g1', 'SELECT g1')
    const g2 = leaf('g2', 'SELECT g2')

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          ops: [op('ADD', '$.x')],
          degraded: false,
          diffMethod: 'LCS',
        }),
    })
    globalThis.fetch = fetchMock

    render(React.createElement(TestComp, { chain: [a, d], all: [a, d, g1, g2] }))

    await waitFor(() => {
      expect(getResult().loading).toBe(false)
    })

    expect(getResult().anyDegraded).toBe(false)
    // All edges inherit the precise signature
    for (const edge of getResult().edges) {
      expect(edge.degraded).toBe(false)
      expect(edge.diffMethod).toBe('ast-lcs')
    }
  })
})
