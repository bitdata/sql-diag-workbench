// src/renderer/utils/dagReverseIndex.test.ts
import { describe, it, expect } from 'vitest'
import { buildParentIndex, describeReverseIndexReason, treeNestingEdges } from './dagReverseIndex'
import { createDAGState } from '../hooks/dagState'
import { buildMockTree } from '../test/fixtures/fragments'
import type { FragmentNode } from '../types/fragment'

function node(id: string, children: FragmentNode[] = []): FragmentNode {
  return {
    id, type: 'JOIN_SPLIT', label: id, sqlFragment: `select '${id}'`,
    executable: true, executionHint: null, nodeCount: children.length,
    ruleName: 'join-split', children,
  }
}

describe('buildParentIndex', () => {
  it('aggregates multi-parent edges from decomposedChildren (X has both A and B)', () => {
    const dag = createDAGState()
    const x = node('X')
    dag.decomposedChildren.set('A', [x])
    dag.decomposedChildren.set('B', [x])
    const index = buildParentIndex(dag, null)
    expect(index.byChildId.get('X')).toEqual(['A', 'B'])
    expect(index.degraded).toBe(false)
    expect(index.reason).toBeNull()
  })

  it('falls back to tree nesting when decomposedChildren is empty, and reports the degradation', () => {
    const dag = createDAGState()
    const index = buildParentIndex(dag, buildMockTree())
    expect(index.byChildId.get('node-join')).toEqual(['node-root'])
    expect(index.degraded).toBe(true)
    expect(index.reason).toBe('MULTI_PARENT_NOT_PERSISTED')
  })

  it('reports NO_EDGES when neither source yields an edge', () => {
    const index = buildParentIndex(createDAGState(), null)
    expect(index.byChildId.size).toBe(0)
    expect(index.degraded).toBe(true)
    expect(index.reason).toBe('NO_EDGES')
  })

  it('merges tree edges for children the authoritative source does not know (restore, then decompose)', () => {
    const dag = createDAGState()
    // 本次会话只拆过一个节点；buildMockTree() 代表从会话恢复出来的树（其边不在 dag 里）
    dag.decomposedChildren.set('node-join', [node('fresh')])
    const index = buildParentIndex(dag, buildMockTree())
    expect(index.byChildId.get('fresh')).toEqual(['node-join'])
    expect(index.byChildId.get('node-join')).toEqual(['node-root'])
    expect(index.degraded).toBe(true)
    expect(index.reason).toBe('MULTI_PARENT_NOT_PERSISTED')
  })

  it('stays non-degraded when the tree edge agrees with the authoritative edge', () => {
    const dag = createDAGState()
    dag.decomposedChildren.set('A', [node('X')])
    const index = buildParentIndex(dag, node('A', [node('X')]))
    expect(index.byChildId.get('X')).toEqual(['A'])
    expect(index.degraded).toBe(false)
    expect(index.reason).toBeNull()
  })

  it('treeNestingEdges maps every non-root node to its single parent', () => {
    const edges = treeNestingEdges(buildMockTree())
    expect(edges.get('node-root')).toBeUndefined()
    expect(edges.get('node-join')).toEqual(['node-root'])
    expect(edges.get('node-where')).toEqual(['node-root'])
  })

  it('treeNestingEdges yields A and B for a child that hangs under two parents (diamond)', () => {
    const edges = treeNestingEdges(node('root', [node('A', [node('X')]), node('B', [node('X')])]))
    expect(edges.get('X')).toEqual(['A', 'B'])
  })

  it('does not duplicate an edge when the same parent lists the same child twice', () => {
    const edges = treeNestingEdges(node('root', [node('X'), node('X')]))
    expect(edges.get('X')).toEqual(['root'])
  })

  it('describeReverseIndexReason gives one observable text per reason', () => {
    expect(describeReverseIndexReason('MULTI_PARENT_NOT_PERSISTED')).toBe('Only tree parents shown (multiple parents were not persisted)')
    expect(describeReverseIndexReason('NO_EDGES')).toBe('No add-back paths available')
    expect(describeReverseIndexReason('PARENT_NOT_IN_DAG')).toBe('This parent is not in the current view')
    expect(describeReverseIndexReason('MULTIPLE_ORIGINS')).toBe('Multiple upward branches exist; only one is shown')
    expect(describeReverseIndexReason(null)).toBe('')
  })
})
