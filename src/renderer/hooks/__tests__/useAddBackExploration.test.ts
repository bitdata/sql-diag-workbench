// src/renderer/hooks/__tests__/useAddBackExploration.test.ts
import { describe, it, expect, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useAddBackExploration } from '../useAddBackExploration'
import { createDAGState, indexTreeIntoDAG } from '../dagState'
import type { FragmentNode } from '../../types/fragment'

function node(id: string, children: FragmentNode[] = []): FragmentNode {
  return {
    id, type: 'JOIN_SPLIT', label: id, sqlFragment: `select '${id}'`,
    executable: true, executionHint: null, nodeCount: children.length,
    ruleName: 'join-split', children,
  }
}

function dagWithThreeLevels() {
  const dag = createDAGState()
  // The tree mirrors the DAG chain, so every nesting edge it contributes is one
  // the DAG already knows and the index stays healthy — what the healthy-path
  // test below needs. The degraded path has its own fixture (session-restore test).
  const seed = node('a')
  const mid = node('a JOIN b', [seed])
  const top = node('a JOIN b JOIN c', [mid])
  for (const n of [seed, mid, top]) dag.nodeMap.set(n.id, n)
  dag.decomposedChildren.set('a JOIN b', [seed])
  dag.decomposedChildren.set('a JOIN b JOIN c', [mid])
  return { dag, top }
}

/** App's shape: `${activeSessionId}#${treeEpoch}` (see useFragmentTree.treeEpoch). */
const KEY = 'sess-A#0'

describe('useAddBackExploration', () => {
  it('is inactive until enter() and empty-tree-free', () => {
    const { dag, top } = dagWithThreeLevels()
    const { result } = renderHook(() =>
      useAddBackExploration({ dag, tree: top, treeInstanceKey: KEY, selectNode: vi.fn() }))
    expect(result.current.active).toBe(false)
    expect(result.current.tree).toBeNull()
    // The inactive-state defaults: every other assertion in this file runs after
    // enter(), where `projection` is non-null, so `?? true` on these two would
    // otherwise ship green (scoped re-review of R2, Finding 1).
    expect(result.current.isOriginRoot).toBe(false)
    expect(result.current.degraded).toBe(false)
  })

  it('enter() shows just the seed and selects it', () => {
    const selectNode = vi.fn()
    const { dag, top } = dagWithThreeLevels()
    const { result } = renderHook(() =>
      useAddBackExploration({ dag, tree: top, treeInstanceKey: KEY, selectNode }))
    act(() => result.current.enter('a'))
    expect(result.current.active).toBe(true)
    expect(result.current.seedNodeId).toBe('a')
    expect(result.current.tree?.id).toBe('a')
    expect(result.current.tree?.children).toEqual([])
    expect(selectNode).toHaveBeenCalledWith('a')
  })

  it('toggle() reveals one real parent hop, moving the root up and the seed down', () => {
    const { dag, top } = dagWithThreeLevels()
    const { result } = renderHook(() =>
      useAddBackExploration({ dag, tree: top, treeInstanceKey: KEY, selectNode: vi.fn() }))
    act(() => result.current.enter('a'))
    act(() => result.current.toggle('a', 'a JOIN b'))
    expect(result.current.isRevealed('a', 'a JOIN b')).toBe(true)
    expect(result.current.tree?.id).toBe('a JOIN b')
    expect(result.current.tree?.children.map((c) => c.id)).toEqual(['a'])
    expect(result.current.breadcrumb).toEqual(['a', 'a JOIN b', 'a JOIN b JOIN c (original SQL)'])
    act(() => result.current.toggle('a', 'a JOIN b'))
    expect(result.current.isRevealed('a', 'a JOIN b')).toBe(false)
    expect(result.current.tree?.id).toBe('a')          // back to the seed as root
    expect(result.current.tree?.children).toEqual([])
  })

  it('forwards the origin walk and the origin-root gate verbatim', () => {
    const { dag, top } = dagWithThreeLevels()
    const { result } = renderHook(() =>
      useAddBackExploration({ dag, tree: top, treeInstanceKey: KEY, selectNode: vi.fn() }))
    act(() => result.current.enter('a'))
    // The walk is index-only, so it reaches the DAG root before any hop is revealed.
    expect(result.current.originId).toBe('a JOIN b JOIN c')
    expect(result.current.reachesOrigin).toBe(true)
    expect(result.current.isOriginRoot).toBe(false)      // the seed still has an in-edge
    expect(result.current.skipped).toEqual([])
    expect(result.current.degraded).toBe(false)
    expect(result.current.reason).toBeNull()
    act(() => result.current.toggle('a', 'a JOIN b'))
    expect(result.current.isOriginRoot).toBe(false)      // 'a JOIN b' still has an in-edge
    act(() => result.current.toggle('a JOIN b', 'a JOIN b JOIN c'))
    expect(result.current.isOriginRoot).toBe(true)       // the DAG root: no in-edge
  })

  it('does not report degradation when tree edges match the authoritative source (Review Focus 1)', () => {
    const { dag, top } = dagWithThreeLevels()
    const { result } = renderHook(() =>
      useAddBackExploration({ dag, tree: top, treeInstanceKey: KEY, selectNode: vi.fn() }))
    act(() => result.current.enter('a'))
    expect(result.current.index.degraded).toBe(false)
    expect(result.current.index.reason).toBeNull()
    // The parent the panel offers for 'a' — the edge Task 9 recomputes per selection.
    expect(result.current.index.byChildId.get('a')).toEqual(['a JOIN b'])
  })

  it('reports the session-restore fallback as degraded (Review Focus 1)', () => {
    // The post-restore state (spec §7.0.1): nodeMap filled, decomposedChildren empty.
    const dag = createDAGState()
    const tree = node('root', [node('mid', [node('a')])])
    indexTreeIntoDAG(dag, tree)
    const { result } = renderHook(() =>
      useAddBackExploration({ dag, tree, treeInstanceKey: KEY, selectNode: vi.fn() }))
    act(() => result.current.enter('a'))
    expect(result.current.index.degraded).toBe(true)
    expect(result.current.index.reason).toBe('MULTI_PARENT_NOT_PERSISTED')
    // Still lists the one parent it does know about, and says it may be short.
    expect(result.current.index.byChildId.get('a')).toEqual(['mid'])
    // The warning must reach the UI through the hook's own forwarding, not the index field.
    expect(result.current.degraded).toBe(true)
    expect(result.current.reason).toBe('MULTI_PARENT_NOT_PERSISTED')
  })

  it('exit() returns to inactive', () => {
    const { dag, top } = dagWithThreeLevels()
    const { result } = renderHook(() =>
      useAddBackExploration({ dag, tree: top, treeInstanceKey: KEY, selectNode: vi.fn() }))
    act(() => result.current.enter('a'))
    act(() => result.current.exit())
    expect(result.current.active).toBe(false)
    expect(result.current.tree).toBeNull()
  })

  it('exits when the tree INSTANCE key changes — the content-derived id may be identical (I1)', () => {
    // THE vacuity this closes (whole-branch review I1): every other test in this
    // file feeds trees with DIFFERENT ids, so a guard keyed on `tree.id` passes all
    // of them. `FragmentNode.id` is content-derived (ADR-0001:
    // `FragmentIdentity.computeId(canonicalSql, dialect)`), so the real triggers
    // arrive here with an IDENTICAL id and a REPLACED DAG — the case the old key
    // could not see, which left `active: true` over a DAG that no longer holds the
    // seed (projection `tree: null` under a banner showing the raw 64-hex id).
    //
    // Both triggers, sharing ONE `tree` object so the id is provably unchanged:
    //   1. re-parse in the same tab   → the epoch part of the key bumps
    //   2. a second tab, same SQL     → the session part of the key changes
    const { dag, top } = dagWithThreeLevels()
    const { result, rerender } = renderHook(
      ({ key }: { key: string }) =>
        useAddBackExploration({ dag, tree: top, treeInstanceKey: key, selectNode: vi.fn() }),
      { initialProps: { key: 'sess-A#0' } })
    act(() => result.current.enter('a'))
    expect(result.current.active).toBe(true)

    rerender({ key: 'sess-A#1' })                       // trigger 1: same tab, re-parse
    expect(result.current.active).toBe(false)
    expect(result.current.tree).toBeNull()

    // The clearing half of the guard must leave a clean slate — a later enter()
    // records the NEW key, so the exploration is usable again immediately.
    act(() => result.current.enter('a'))
    expect(result.current.active).toBe(true)
    rerender({ key: 'sess-B#0' })                       // trigger 2: another tab, same SQL
    expect(result.current.active).toBe(false)
    expect(result.current.tree).toBeNull()
    // Same id throughout — that is the whole point (a `tree.id` guard stays green here).
    expect(top.id).toBe('a JOIN b JOIN c')
  })

  it('does not exit on a tree id change alone — the key is the discriminator', () => {
    // The mirror image of the test above, and the reason the key is the right
    // carrier: a guard that ALSO fired on `tree.id` would be indistinguishable
    // from the old one on this input. App's contract is the other half — it must
    // mint a new key whenever the tree instance is replaced (see useFragmentTree's
    // `treeEpoch`), so a caller that swaps the tree without bumping the key is the
    // bug this test localises to the caller.
    const { dag, top } = dagWithThreeLevels()
    const { result, rerender } = renderHook(
      ({ tree }: { tree: FragmentNode }) =>
        useAddBackExploration({ dag, tree, treeInstanceKey: KEY, selectNode: vi.fn() }),
      { initialProps: { tree: top } })
    act(() => result.current.enter('a'))
    expect(result.current.active).toBe(true)
    rerender({ tree: node('another-root') })
    expect(result.current.active).toBe(true)
  })

  it('exits when both the tree id and the instance key change (tab switch / re-parse)', () => {
    const { dag, top } = dagWithThreeLevels()
    const { result, rerender } = renderHook(
      ({ tree, key }: { tree: FragmentNode; key: string }) =>
        useAddBackExploration({ dag, tree, treeInstanceKey: key, selectNode: vi.fn() }),
      { initialProps: { tree: top, key: 'sess-A#0' } })
    act(() => result.current.enter('a'))
    expect(result.current.active).toBe(true)
    rerender({ tree: node('another-root'), key: 'sess-B#0' })
    expect(result.current.active).toBe(false)
  })
})