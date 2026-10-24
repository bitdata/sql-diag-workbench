// src/renderer/utils/addBackProjection.test.ts
import { describe, it, expect } from 'vitest'
import {
  ORIGIN_SUFFIX, UNRESOLVED_SUFFIX,
  buildAddBackProjection, computeAddBackCandidates, createAddBackState,
  hideParent, isRevealed, revealParent, walkToOrigin,
} from './addBackProjection'
import { buildParentIndex } from './dagReverseIndex'
import { createDAGState, indexTreeIntoDAG } from '../hooks/dagState'
import type { FragmentNode } from '../types/fragment'

function node(id: string, children: FragmentNode[] = []): FragmentNode {
  return {
    id, type: 'JOIN_SPLIT', label: id, sqlFragment: `select '${id}'`,
    executable: true, executionHint: null, nodeCount: children.length,
    ruleName: 'join-split', children,
  }
}

/** a ← a JOIN b ← a JOIN b JOIN c  (edges point complex → simple) */
function threeLevelDag() {
  const dag = createDAGState()
  const seed = node('a')
  const mid = node('a JOIN b')
  const top = node('a JOIN b JOIN c')
  for (const n of [seed, mid, top]) dag.nodeMap.set(n.id, n)
  dag.decomposedChildren.set('a JOIN b', [seed])
  dag.decomposedChildren.set('a JOIN b JOIN c', [mid])
  return { dag, seed, mid, top, index: buildParentIndex(dag, null) }
}

describe('revealParent / hideParent', () => {
  it('returns a new state and never mutates the input', () => {
    const s0 = createAddBackState('a')
    const s1 = revealParent(s0, 'a', 'a JOIN b')
    expect(s0.revealedParents.size).toBe(0)
    expect(isRevealed(s1, 'a', 'a JOIN b')).toBe(true)
    const s2 = hideParent(s1, 'a', 'a JOIN b')
    expect(isRevealed(s1, 'a', 'a JOIN b')).toBe(true)
    expect(isRevealed(s2, 'a', 'a JOIN b')).toBe(false)
  })

  it('hides one parent without disturbing the others', () => {
    let s = createAddBackState('a')
    s = revealParent(s, 'a', 'p1')
    s = revealParent(s, 'a', 'p2')
    s = revealParent(s, 'b', 'p3')
    const s2 = hideParent(s, 'a', 'p1')
    expect(isRevealed(s2, 'a', 'p1')).toBe(false)
    expect(isRevealed(s2, 'a', 'p2')).toBe(true)
    expect(isRevealed(s2, 'b', 'p3')).toBe(true)
    // Hiding the last parent of a child drops that child's entry entirely,
    // leaving the other child's reveals untouched.
    const s3 = hideParent(s2, 'a', 'p2')
    expect(s3.revealedParents.has('a')).toBe(false)
    expect(s3.revealedParents.get('b')?.has('p3')).toBe(true)
  })
})

describe('buildAddBackProjection — one hop at a time', () => {
  it('shows only the seed before anything is revealed (seed is both root and leaf)', () => {
    const { dag, index } = threeLevelDag()
    const p = buildAddBackProjection(createAddBackState('a'), dag, index)
    expect(p.tree?.id).toBe('a')
    expect(p.tree?.children).toEqual([])
    // The seed has an in-edge, so the display root is NOT the DAG root yet
    // (spec §4.3.4 #2): the UI must not call it 原 SQL.
    expect(p.isOriginRoot).toBe(false)
  })

  it('reveals exactly one hop with the real DAG node, casting the seed to a leaf', () => {
    const { dag, index, mid, seed } = threeLevelDag()
    const s = revealParent(createAddBackState('a'), 'a', 'a JOIN b')
    const p = buildAddBackProjection(s, dag, index)
    expect(p.tree?.id).toBe('a JOIN b')                      // the revealed ancestor is now the root
    expect(p.tree?.children.map((c) => c.id)).toEqual(['a']) // seed hangs below it
    expect(p.tree?.children[0].id).toBe(seed.id)             // real id, not `composition-…`
    expect(p.tree?.children[0].sqlFragment).toBe(seed.sqlFragment)
    expect(p.tree?.children[0].id.startsWith('composition-')).toBe(false)
    expect(p.tree?.id).toBe(mid.id)
  })

  it('shows both branches once their common ancestor is revealed', () => {
    const dag = createDAGState()
    const seed = node('a')
    dag.nodeMap.set('a', seed)
    for (const pid of ['mid-b', 'mid-c', 'top']) {
      dag.nodeMap.set(pid, node(pid))
    }
    dag.decomposedChildren.set('mid-b', [seed])
    dag.decomposedChildren.set('mid-c', [seed])
    dag.decomposedChildren.set('top', [seed, dag.nodeMap.get('mid-b')!, dag.nodeMap.get('mid-c')!])
    const index = buildParentIndex(dag, null)

    // Stage 1: two independent maximal ancestors — one root is all a tree can show.
    let s = createAddBackState('a')
    s = revealParent(s, 'a', 'mid-b')
    s = revealParent(s, 'a', 'mid-c')
    const p1 = buildAddBackProjection(s, dag, index)
    expect(p1.tree?.id).toBe('mid-b')
    expect(p1.reason).toBe('MULTIPLE_ORIGINS')
    expect(p1.degraded).toBe(true)
    expect(p1.skipped).toContain('mid-c')
    expect(p1.isOriginRoot).toBe(false)

    // Stage 2: reveal the shared ancestor — both branches now fit under one root.
    s = revealParent(s, 'mid-b', 'top')
    s = revealParent(s, 'mid-c', 'top')
    const p2 = buildAddBackProjection(s, dag, index)
    expect(p2.tree?.id).toBe('top')
    expect(p2.tree?.children.map((c) => c.id)).toEqual(['mid-b', 'mid-c'])
    expect(p2.reason).toBeNull()
    // 'top' is the DAG root: now the display root may be labelled 原 SQL.
    expect(p2.isOriginRoot).toBe(true)
  })

  it('repeats the seed under two ancestors (diamond) — real ids, duplicated', () => {
    const dag = createDAGState()
    const seed = node('a')
    dag.nodeMap.set('a', seed)
    dag.nodeMap.set('mid', node('mid'))
    dag.nodeMap.set('top', node('top'))
    dag.decomposedChildren.set('mid', [seed])
    dag.decomposedChildren.set('top', [seed, dag.nodeMap.get('mid')!])
    const index = buildParentIndex(dag, null)
    let s = createAddBackState('a')
    s = revealParent(s, 'a', 'mid')
    s = revealParent(s, 'a', 'top')
    s = revealParent(s, 'mid', 'top')
    const p = buildAddBackProjection(s, dag, index)
    expect(p.tree?.id).toBe('top')
    expect(p.tree?.children.map((c) => c.id)).toEqual(['a', 'mid'])
    expect(p.tree?.children[1].children.map((c) => c.id)).toEqual(['a'])
    expect(p.reason).toBeNull()
  })

  it('reports an unresolvable parent as skipped instead of silently dropping it', () => {
    const dag = createDAGState()
    dag.nodeMap.set('a', node('a'))
    dag.decomposedChildren.set('ghost', [dag.nodeMap.get('a')!])
    const index = buildParentIndex(dag, null)
    const s = revealParent(createAddBackState('a'), 'a', 'ghost')
    const p = buildAddBackProjection(s, dag, index)
    expect(p.tree?.id).toBe('a')
    expect(p.tree?.children).toEqual([])
    expect(p.skipped).toContain('ghost')
    expect(p.reason).toBe('PARENT_NOT_IN_DAG')
  })

  it('prefers the index-level degradation in the reported reason', () => {
    // Exactly the post-restore state (spec §7.0.1): indexTreeIntoDAG fills
    // nodeMap but leaves decomposedChildren empty, so edges come from nesting.
    const dag = createDAGState()
    const tree = node('root', [node('mid', [node('a')])])
    indexTreeIntoDAG(dag, tree)
    const index = buildParentIndex(dag, tree)
    const s = revealParent(createAddBackState('a'), 'a', 'mid')
    const p = buildAddBackProjection(s, dag, index)
    expect(p.reason).toBe('MULTI_PARENT_NOT_PERSISTED')
    expect(p.tree?.id).toBe('mid')
  })

  it('still marks the display root 原 SQL in the only reachable NO_EDGES state', () => {
    // NO_EDGES means both edge sources are empty, and tree nesting is empty only
    // for a null or childless tree — so the reachable shape is a single-node tree
    // whose node IS the DAG root. The label is therefore correct, and suppressing
    // it would make Task 9 render 未还原到原 SQL about the original SQL itself
    // (spec §4.3.4 #2). A null tree cannot reach this path: indexTreeIntoDAG is
    // the only thing that writes the node map, and it returns before its first
    // write on null — so a null tree leaves no resolvable seed and the projection
    // early-returns with `skipped` instead.
    const dag = createDAGState()
    const tree = node('solo')
    indexTreeIntoDAG(dag, tree)
    const index = buildParentIndex(dag, tree)
    expect(index.reason).toBe('NO_EDGES')
    const p = buildAddBackProjection(createAddBackState('solo'), dag, index)
    expect(p.tree?.id).toBe('solo')
    expect(p.isOriginRoot).toBe(true)
    expect(p.reason).toBe('NO_EDGES')
  })

  it('reports the index-level degradation even when several origins are revealed', () => {
    // Degraded (tree-source) index + two disjoint maximal ancestors. The
    // index-level reason wins the precedence chain — it is the session-restore
    // message Review Focus 1 needs most — and nothing is lost because the extra
    // root stays visible in `skipped`. Deliberate, not an oversight.
    const dag = createDAGState()
    const seed = node('a')
    const tree = node('root', [node('mid', [seed])])
    indexTreeIntoDAG(dag, tree)
    const index = buildParentIndex(dag, tree)
    expect(index.reason).toBe('MULTI_PARENT_NOT_PERSISTED')
    let s = createAddBackState('a')
    s = revealParent(s, 'a', 'mid')
    s = revealParent(s, 'a', 'root')
    const p = buildAddBackProjection(s, dag, index)
    expect(p.tree?.id).toBe('mid')
    expect(p.reason).toBe('MULTI_PARENT_NOT_PERSISTED')
    expect(p.skipped).toContain('root')
  })

  it('skips an unknown seed and says so', () => {
    const dag = createDAGState()
    const index = buildParentIndex(dag, null)
    const p = buildAddBackProjection(createAddBackState('nope'), dag, index)
    expect(p.tree).toBeNull()
    expect(p.skipped).toEqual(['nope'])
  })

  it('treats an invalid seed as a hard failure, not a degradation', () => {
    // The only return path with a non-empty `skipped` but no reason: `skipped`
    // means "invalid seed" (also signalled by `tree: null`), not a degraded
    // result. Pinned so the documented behaviour cannot drift silently.
    const dag = createDAGState()
    const index = buildParentIndex(dag, null)
    const p = buildAddBackProjection(createAddBackState('nope'), dag, index)
    expect(p.tree).toBeNull()
    expect(p.skipped).toEqual(['nope'])
    expect(p.reason).toBeNull()
    expect(p.degraded).toBe(false)
  })
})

describe('breadcrumb / origin', () => {
  it('walks seed-first to the DAG root and marks it 原 SQL', () => {
    const { dag, index } = threeLevelDag()
    const p = buildAddBackProjection(createAddBackState('a'), dag, index)
    expect(p.breadcrumb).toEqual(['a', `a JOIN b`, `a JOIN b JOIN c${ORIGIN_SUFFIX}`])
    expect(p.originId).toBe('a JOIN b JOIN c')
    expect(p.reachesOrigin).toBe(true)
  })

  it('suffixes the breadcrumb terminal with （原 SQL） when the walk reaches a facet with no in-edge', () => {
    const { dag, index } = threeLevelDag()
    const p = buildAddBackProjection(createAddBackState('a JOIN b'), dag, index)
    expect(p.breadcrumb).toEqual(['a JOIN b', `a JOIN b JOIN c${ORIGIN_SUFFIX}`])
    expect(p.reachesOrigin).toBe(true)
  })

  it('stops at a cycle without claiming the origin was reached', () => {
    const dag = createDAGState()
    dag.nodeMap.set('x', node('x'))
    dag.nodeMap.set('y', node('y'))
    dag.decomposedChildren.set('y', [dag.nodeMap.get('x')!])
    dag.decomposedChildren.set('x', [dag.nodeMap.get('y')!])
    const index = buildParentIndex(dag, null)
    const walk = walkToOrigin('x', index)
    expect(walk.reachesOrigin).toBe(false)
    const p = buildAddBackProjection(createAddBackState('x'), dag, index)
    expect(p.reachesOrigin).toBe(false)
    expect(p.breadcrumb[p.breadcrumb.length - 1]).toContain(UNRESOLVED_SUFFIX)
  })
})

describe('computeAddBackCandidates', () => {
  it('lists every parent facet with its real DAG node', () => {
    const { dag, index } = threeLevelDag()
    const c = computeAddBackCandidates('a', dag, index)
    expect(c.candidates.map((x) => x.parentId)).toEqual(['a JOIN b'])
    expect(c.candidates[0].node.label).toBe('a JOIN b')
    expect(c.degraded).toBe(false)
    expect(c.skipped).toEqual([])
  })

  it('carries the index degradation through unchanged (session-restored case)', () => {
    const dag = createDAGState()
    const index = buildParentIndex(dag, node('a', [node('b')]))
    const c = computeAddBackCandidates('b', dag, index)
    expect(c.degraded).toBe(true)
    expect(c.reason).toBe('MULTI_PARENT_NOT_PERSISTED')
  })

  it('exposes a dangling parent id via skipped + PARENT_NOT_IN_DAG', () => {
    const dag = createDAGState()
    dag.nodeMap.set('a', node('a'))
    dag.decomposedChildren.set('ghost', [dag.nodeMap.get('a')!])
    const index = buildParentIndex(dag, null)
    const c = computeAddBackCandidates('a', dag, index)
    expect(c.candidates).toEqual([])
    expect(c.skipped).toEqual(['ghost'])
    expect(c.reason).toBe('PARENT_NOT_IN_DAG')
  })

  it('reports NO_EDGES with no candidates when the index is empty', () => {
    const dag = createDAGState()
    const index = buildParentIndex(dag, null)
    const c = computeAddBackCandidates('a', dag, index)
    expect(c.candidates).toEqual([])
    expect(c.skipped).toEqual([])
    expect(c.degraded).toBe(true)
    expect(c.reason).toBe('NO_EDGES')
  })
})
