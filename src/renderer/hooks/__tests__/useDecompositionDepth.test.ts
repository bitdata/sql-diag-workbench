// src/renderer/hooks/__tests__/useDecompositionDepth.test.ts
import { describe, it, expect } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useDecompositionDepth } from '../useDecompositionDepth'
import type { FragmentNode, FragmentType } from '../../types/fragment'

/** Minimal factory — only fields the hook touches are required. */
function makeNode(id: string, children: FragmentNode[] = []): FragmentNode {
  return {
    id,
    type: 'ORIGINAL' as FragmentType,
    label: id,
    sqlFragment: `SELECT ${id}`,
    executable: false,
    executionHint: null,
    nodeCount: 0,
    ruleName: null,
    children,
  }
}

describe('useDecompositionDepth', () => {
  // ─── Null / empty inputs ───────────────────────────────────────────

  it('returns null when tree is null', () => {
    const { result } = renderHook(() => useDecompositionDepth(null, null))
    expect(result.current).toBeNull()
  })

  it('returns null when tree is null regardless of selectedNodeId', () => {
    const { result } = renderHook(() => useDecompositionDepth(null, 'some-id'))
    expect(result.current).toBeNull()
  })

  // ─── Single leaf node ──────────────────────────────────────────────

  it('computes maxDepth 0 and globalDepth 0 for a single leaf node', () => {
    const leaf = makeNode('root')
    const { result } = renderHook(() => useDecompositionDepth(leaf, null))

    expect(result.current).not.toBeNull()
    expect(result.current!.maxDepth).toBe(0)
    expect(result.current!.globalDepth).toBe(0)
    expect(result.current!.nodeMaxDepths).toEqual({ root: 0 })
    expect(result.current!.nodeGlobalDepths).toEqual({ root: 0 })
  })

  // ─── Linear chain ──────────────────────────────────────────────────

  it('computes correct depths for a linear chain (root → a → b)', () => {
    const b = makeNode('b')
    const a = makeNode('a', [b])
    const root = makeNode('root', [a])

    const { result } = renderHook(() => useDecompositionDepth(root, null))

    expect(result.current!.maxDepth).toBe(2)
    expect(result.current!.globalDepth).toBe(0)

    // root sees the deepest subtree (2 levels below)
    expect(result.current!.nodeMaxDepths['root']).toBe(2)
    expect(result.current!.nodeMaxDepths['a']).toBe(1)
    expect(result.current!.nodeMaxDepths['b']).toBe(0)

    // globalDepth = distance from root
    expect(result.current!.nodeGlobalDepths['root']).toBe(0)
    expect(result.current!.nodeGlobalDepths['a']).toBe(1)
    expect(result.current!.nodeGlobalDepths['b']).toBe(2)
  })

  // ─── Branching tree ────────────────────────────────────────────────

  it('maxDepth follows the deepest branch in a wide tree', () => {
    //       root
    //      /    \
    //    left   right
    //           /
    //         deep
    const deep = makeNode('deep')
    const right = makeNode('right', [deep])
    const left = makeNode('left')
    const root = makeNode('root', [left, right])

    const { result } = renderHook(() => useDecompositionDepth(root, null))

    // Deepest path: root → right → deep = 2
    expect(result.current!.maxDepth).toBe(2)

    expect(result.current!.nodeMaxDepths['root']).toBe(2)
    expect(result.current!.nodeMaxDepths['left']).toBe(0)
    expect(result.current!.nodeMaxDepths['right']).toBe(1)
    expect(result.current!.nodeMaxDepths['deep']).toBe(0)

    expect(result.current!.nodeGlobalDepths['root']).toBe(0)
    expect(result.current!.nodeGlobalDepths['left']).toBe(1)
    expect(result.current!.nodeGlobalDepths['right']).toBe(1)
    expect(result.current!.nodeGlobalDepths['deep']).toBe(2)
  })

  it('handles a node with multiple children at the same depth', () => {
    const c1 = makeNode('c1')
    const c2 = makeNode('c2')
    const c3 = makeNode('c3')
    const root = makeNode('root', [c1, c2, c3])

    const { result } = renderHook(() => useDecompositionDepth(root, null))

    expect(result.current!.maxDepth).toBe(1)
    expect(result.current!.nodeMaxDepths['root']).toBe(1)
    expect(result.current!.nodeMaxDepths['c1']).toBe(0)
    expect(result.current!.nodeMaxDepths['c2']).toBe(0)
    expect(result.current!.nodeMaxDepths['c3']).toBe(0)
  })

  // ─── Memoization & referential stability ───────────────────────────

  it('returns the same object reference across rerenders when tree is unchanged', () => {
    const tree = makeNode('root', [makeNode('child')])
    const { result, rerender } = renderHook(
      ({ t, sel }) => useDecompositionDepth(t, sel),
      { initialProps: { t: tree, sel: null as string | null } }
    )

    const first = result.current
    rerender({ t: tree, sel: null })
    expect(result.current).toBe(first)
  })

  it('recomputes when tree changes to a different reference', () => {
    const tree1 = makeNode('root', [makeNode('a')])
    const tree2 = makeNode('root', [makeNode('a'), makeNode('b')])

    const { result, rerender } = renderHook(
      ({ t }) => useDecompositionDepth(t, null),
      { initialProps: { t: tree1 } }
    )

    const first = result.current
    expect(first!.maxDepth).toBe(1)

    rerender({ t: tree2 })

    // New reference — memo should have recomputed
    expect(result.current).not.toBe(first)
    expect(result.current!.maxDepth).toBe(1)
    // tree2 has one extra leaf
    expect(Object.keys(result.current!.nodeMaxDepths)).toContain('b')
  })

  // ─── Fixed bugs ──────────────────────────────────────────────────

  it('returns stable reference when only selectedNodeId changes (no recomputation)', () => {
    const tree = makeNode('root', [makeNode('child', [makeNode('grandchild')])])

    const { result, rerender } = renderHook(
      ({ t, sel }) => useDecompositionDepth(t, sel),
      { initialProps: { t: tree, sel: null as string | null } }
    )

    const withNull = result.current
    rerender({ t: tree, sel: 'grandchild' })
    const withSelection = result.current

    // Same tree → same memo reference, regardless of selectedNodeId
    expect(withSelection).toBe(withNull)
  })

  it('duplicate node ids use first-visited-wins (no silent overwrite)', () => {
    // Two nodes share id "dup" — one at globalDepth 1, one at globalDepth 2
    const dup1 = makeNode('dup')
    const dup2 = makeNode('dup')
    const mid = makeNode('mid', [dup2])
    const root = makeNode('root', [dup1, mid])

    const { result } = renderHook(() => useDecompositionDepth(root, null))

    // First-visited "dup" is at globalDepth 1; second at 2 is ignored
    const depths = result.current!.nodeGlobalDepths
    expect(depths['dup']).toBe(1)
  })

  // ─── Edge cases ────────────────────────────────────────────────────

  it('handles a deeply nested single-child chain without error', () => {
    // Build a chain 50 nodes deep
    let node = makeNode('n50')
    for (let i = 49; i >= 0; i--) {
      node = makeNode(`n${i}`, [node])
    }

    const { result } = renderHook(() => useDecompositionDepth(node, null))

    expect(result.current!.maxDepth).toBe(50)
    expect(result.current!.nodeGlobalDepths['n0']).toBe(0)
    expect(result.current!.nodeGlobalDepths['n50']).toBe(50)
    expect(result.current!.nodeMaxDepths['n50']).toBe(0)
    expect(result.current!.nodeMaxDepths['n0']).toBe(50)
  })

  it('handles an asymmetric tree where one branch is much deeper', () => {
    //       root
    //      /    \
    //   shallow  deep1
    //              |
    //            deep2
    //              |
    //            deep3
    const shallow = makeNode('shallow')
    const deep3 = makeNode('deep3')
    const deep2 = makeNode('deep2', [deep3])
    const deep1 = makeNode('deep1', [deep2])
    const root = makeNode('root', [shallow, deep1])

    const { result } = renderHook(() => useDecompositionDepth(root, null))

    expect(result.current!.maxDepth).toBe(3)
    expect(result.current!.nodeMaxDepths['root']).toBe(3)
    expect(result.current!.nodeMaxDepths['shallow']).toBe(0)
    expect(result.current!.nodeMaxDepths['deep1']).toBe(2)
    expect(result.current!.nodeMaxDepths['deep2']).toBe(1)
    expect(result.current!.nodeMaxDepths['deep3']).toBe(0)
  })

  it('selectedNodeId does not affect the computed output (documents current behavior)', () => {
    const tree = makeNode('root', [makeNode('child')])

    const { result: r1 } = renderHook(() => useDecompositionDepth(tree, null))
    const { result: r2 } = renderHook(() => useDecompositionDepth(tree, 'child'))
    const { result: r3 } = renderHook(() => useDecompositionDepth(tree, 'nonexistent'))

    // All three produce identical depth info — selectedNodeId is inert.
    expect(r1.current).toEqual(r2.current)
    expect(r1.current).toEqual(r3.current)
  })
})
