import { describe, it, expect } from 'vitest'
import { sha256 } from './sha256'
import { readSnapshot, snapshotWorkspace } from './workspaceSnapshot'
import { createDAGState } from '../hooks/dagState'
import type { FragmentNode, RuleResult } from '../types/fragment'
import type { SessionWorkspaceState } from '../types/session'

const node = (id: string): FragmentNode => ({ id, sqlFragment: `SELECT '${id}'`, type: 'ORIGINAL', label: id, executable: true, executionHint: null, nodeCount: 0, ruleName: null, children: [] })
const ws = { dialect: 'postgresql', viewerMode: 'browse', workspaceMode: 'data' } as SessionWorkspaceState
function fixture() {
  const root = node('root'), shared = node('shared'); root.children = [shared]
  const dag = createDAGState(); dag.nodeMap.set(root.id, root); dag.nodeMap.set(shared.id, shared)
  const r: RuleResult = { sourceNodeId: root.id, ruleName: 'set-op-split', status: 'COMPLETE', sourceSqlHash: sha256(root.sqlFragment), childIds: [shared.id], issues: [], outputs: [1, 2].map(ordinal => ({
    sourceNodeId: root.id, ruleName: 'set-op-split', targetNodeId: shared.id, ordinal, effect: { sourceSqlHash: sha256(root.sqlFragment), targetSqlHash: sha256(shared.sqlFragment), context: 'PRESERVED', changes: [], retained: [], limitations: [] },
  })) }
  dag.ruleResults.set('root::set-op-split', r)
  return JSON.parse(snapshotWorkspace(ws, root, dag, shared.id, root.sqlFragment))
}
describe('experiment evidence snapshots', () => {
  it('hashes exact UTF-8 SQL', () => {
    expect(sha256('')).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    expect(sha256('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    expect(sha256('SQL')).not.toBe(sha256('sql'))
  })
  it('preserves duplicate positions and authoritative node metadata on cold read', () => {
    const s = fixture(); s.treeProjection.children[0].executionHint = 'stale projection'
    const restored = readSnapshot(JSON.stringify(s))
    expect(restored.snapshot.version).toBe(2)
    expect(restored.dag.ruleResults.get('root::set-op-split')?.outputs?.map(o => o.ordinal)).toEqual([1, 2])
    expect(restored.dag.nodeMap.get('shared')?.executionHint).toBeNull()
    expect(restored.dag.appliedRules.has('root::set-op-split')).toBe(true)
  })
  it.each(['source', 'target', 'ordinal', 'edge', 'status'])('rejects corrupt %s before hydration', kind => {
    const s = fixture(), r = s.graph.ruleResults[0]
    if (kind === 'source') r.sourceSqlHash = 'bad'
    if (kind === 'target') s.graph.nodes[1].sqlFragment = 'SELECT 99'
    if (kind === 'ordinal') r.outputs[1].ordinal = 1
    if (kind === 'edge') r.childIds = []
    if (kind === 'status') r.status = 'FAILED'
    expect(() => readSnapshot(JSON.stringify(s))).toThrow()
  })
  it('restores v1 as unknown and leaves verification available', () => {
    const s = fixture(); s.version = 1
    const restored = readSnapshot(JSON.stringify(s)).dag
    expect(restored.appliedRules.size).toBe(0)
    expect(restored.ruleResults.get('root::set-op-split')?.issues?.[0].code).toBe('LEGACY_TRANSFORM_UNKNOWN')
  })
})
