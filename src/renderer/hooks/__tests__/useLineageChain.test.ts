// src/renderer/hooks/__tests__/useLineageChain.test.ts
import { describe, it, expect } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useLineageChain } from '../useLineageChain'
import type { FragmentNode } from '../../types/fragment'

// ─── Fixtures ──────────────────────────────────────────────────────────────

function node(id: string, editParentId: string | null = null): FragmentNode {
  return {
    id,
    type: editParentId ? 'USER_EDITED' : 'ORIGINAL',
    label: `Node ${id}`,
    sqlFragment: `SELECT 1 /* ${id} */`,
    executable: true,
    executionHint: null,
    nodeCount: 0,
    ruleName: null,
    children: [],
    parentIds: editParentId ? [editParentId] : [],
    editParentId,
  }
}

// Edit chain: a ← b ← c  (c.editParentId = 'b', b.editParentId = 'a')
const a = node('a')
const b = node('b', 'a')
const c = node('c', 'b')
const linearNodes = [a, b, c]

// ─── Tests ─────────────────────────────────────────────────────────────────

describe('useLineageChain', () => {
  it('follows two automatic decompositions without editParentId', () => {
    const root = node('root')
    const join = { ...node('join'), parentIds: ['root'] }
    const stripped = { ...node('stripped'), parentIds: ['join'] }
    const { result } = renderHook(() => useLineageChain([root, join, stripped], 'stripped'))
    expect(result.current.chain.map(n => n.id)).toEqual(['root', 'join', 'stripped'])
    expect(result.current.pathNotice).toBeNull()
  })

  it('keeps explicit edit ancestry before continuing through decomposition links', () => {
    const root = node('root')
    const split = { ...node('split'), parentIds: ['root'] }
    const edited = { ...node('edited', 'split'), parentIds: ['other'] }
    const { result } = renderHook(() => useLineageChain([root, split, edited, node('other')], 'edited'))
    expect(result.current.chain.map(n => n.id)).toEqual(['root', 'split', 'edited'])
    expect(result.current.pathNotice).toBeNull()
  })

  it('discloses one representative path when a fragment has multiple sources', () => {
    const joined = { ...node('joined'), parentIds: ['a', 'b'] }
    const { result } = renderHook(() => useLineageChain([a, b, joined], 'joined'))
    expect(result.current.chain.map(n => n.id)).toEqual(['a', 'joined'])
    expect(result.current.pathNotice).toContain('Multiple sources')
  })

  it('marks a missing decomposition ancestor instead of treating the fragment as a root', () => {
    const orphan = { ...node('orphan'), parentIds: ['missing'] }
    const { result } = renderHook(() => useLineageChain([orphan], 'orphan'))
    expect(result.current.chain).toEqual([orphan])
    expect(result.current.pathNotice).toContain('ancestor is unavailable')
  })

  it('terminates and discloses cycles in decomposition links', () => {
    const x = { ...node('x'), parentIds: ['y'] }
    const y = { ...node('y'), parentIds: ['x'] }
    const { result } = renderHook(() => useLineageChain([x, y], 'y'))
    expect(result.current.chain.map(n => n.id)).toEqual(['x', 'y'])
    expect(result.current.pathNotice).toContain('circular reference')
  })

  // ── Empty / missing inputs ─────────────────────────────────────────────

  it('returns empty chain when selectedNodeId is null', () => {
    const { result } = renderHook(() => useLineageChain(linearNodes, null))

    expect(result.current.chain).toEqual([])
    expect(result.current.diffNeighbors).toEqual([])
  })

  it('returns empty chain when allNodes is empty', () => {
    const { result } = renderHook(() => useLineageChain([], 'a'))

    expect(result.current.chain).toEqual([])
    expect(result.current.diffNeighbors).toEqual([])
  })

  it('returns empty chain when selectedNodeId is not found', () => {
    const { result } = renderHook(() => useLineageChain(linearNodes, 'nonexistent'))

    expect(result.current.chain).toEqual([])
    expect(result.current.diffNeighbors).toEqual([])
  })

  // ── Chain construction ─────────────────────────────────────────────────

  it('returns the node itself when it has no editParentId', () => {
    const { result } = renderHook(() => useLineageChain(linearNodes, 'a'))

    expect(result.current.chain).toHaveLength(1)
    expect(result.current.chain[0]).toBe(a)
  })

  it('treats undefined editParentId like a root (chain of one)', () => {
    const legacy: FragmentNode = {
      id: 'legacy',
      type: 'ORIGINAL',
      label: 'Legacy node without editParentId field',
      sqlFragment: 'SELECT 1',
      executable: true,
      executionHint: null,
      nodeCount: 0,
      ruleName: null,
      children: [],
      parentIds: [],
      // editParentId omitted entirely
    }

    const { result } = renderHook(() => useLineageChain([legacy], 'legacy'))

    expect(result.current.chain).toEqual([legacy])
  })

  it('walks editParentId to root and returns the chain root-first', () => {
    const { result } = renderHook(() => useLineageChain(linearNodes, 'c'))

    expect(result.current.chain.map((n) => n.id)).toEqual(['a', 'b', 'c'])
    // Same object references as the input nodes
    expect(result.current.chain[0]).toBe(a)
    expect(result.current.chain[1]).toBe(b)
    expect(result.current.chain[2]).toBe(c)
  })

  it('stops at a missing parent, keeping the partial chain root-first', () => {
    const orphanedChild = node('x', 'missing-parent')
    const { result } = renderHook(() => useLineageChain([orphanedChild], 'x'))

    // Parent 'missing-parent' is not in allNodes → walk stops immediately
    expect(result.current.chain.map((n) => n.id)).toEqual(['x'])
  })

  it('keeps the partial chain when a link further up is missing', () => {
    const mid = node('mid', 'ghost-parent')
    const leaf = node('leaf', 'mid')
    const { result } = renderHook(() => useLineageChain([mid, leaf], 'leaf'))

    // leaf → mid → (ghost-parent missing, stop) → root-first [mid, leaf]
    expect(result.current.chain.map((n) => n.id)).toEqual(['mid', 'leaf'])
  })

  // ── Cycle guards ───────────────────────────────────────────────────────

  it('terminates on a self-referencing editParentId', () => {
    const selfLoop = node('loop', 'loop')
    const { result } = renderHook(() => useLineageChain([selfLoop], 'loop'))

    expect(result.current.chain.map((n) => n.id)).toEqual(['loop'])
  })

  it('terminates on a two-node cycle', () => {
    const x = node('x', 'y')
    const y = node('y', 'x')
    const { result } = renderHook(() => useLineageChain([x, y], 'y'))

    // Walk: y → x → (y already seen, stop) → reversed [x, y]
    const ids = result.current.chain.map((n) => n.id)
    expect(ids).toHaveLength(2)
    expect(ids).toContain('x')
    expect(ids).toContain('y')
  })

  it('terminates on a three-node cycle', () => {
    const n1 = node('n1', 'n3')
    const n2 = node('n2', 'n1')
    const n3 = node('n3', 'n2')
    const { result } = renderHook(() => useLineageChain([n1, n2, n3], 'n3'))

    // Walk: n3 → n2 → n1 → (n3 already seen, stop)
    const ids = result.current.chain.map((n) => n.id)
    expect(ids).toHaveLength(3)
    expect(new Set(ids)).toEqual(new Set(['n1', 'n2', 'n3']))
  })

  // ── diffNeighbors stub ─────────────────────────────────────────────────

  it('diffNeighbors is always [] (client-side stub, spec §3.3 L2 pending)', () => {
    const { result } = renderHook(() => useLineageChain(linearNodes, 'c'))

    expect(result.current.chain).toHaveLength(3)
    expect(result.current.diffNeighbors).toEqual([])
  })

  // ── Memoization ────────────────────────────────────────────────────────

  it('returns a stable result object while inputs are unchanged', () => {
    const { result, rerender } = renderHook(() => useLineageChain(linearNodes, 'c'))
    const first = result.current

    rerender()

    expect(result.current).toBe(first)
  })

  it('recomputes when selectedNodeId changes', () => {
    const { result, rerender } = renderHook(
      ({ selectedId }) => useLineageChain(linearNodes, selectedId),
      { initialProps: { selectedId: 'c' as string | null } }
    )

    expect(result.current.chain.map((n) => n.id)).toEqual(['a', 'b', 'c'])

    rerender({ selectedId: 'b' })
    expect(result.current.chain.map((n) => n.id)).toEqual(['a', 'b'])

    rerender({ selectedId: null })
    expect(result.current.chain).toEqual([])
  })

  it('recomputes when allNodes reference changes', () => {
    const { result, rerender } = renderHook(
      ({ nodes }) => useLineageChain(nodes, 'c'),
      { initialProps: { nodes: linearNodes } }
    )
    const first = result.current

    // New array reference, same selected node id — chain rebuilt from new nodes
    const newC = node('c', 'b')
    const newNodes = [a, b, newC]
    rerender({ nodes: newNodes })

    expect(result.current).not.toBe(first)
    expect(result.current.chain.map((n) => n.id)).toEqual(['a', 'b', 'c'])
    expect(result.current.chain[2]).toBe(newC)
  })
})
