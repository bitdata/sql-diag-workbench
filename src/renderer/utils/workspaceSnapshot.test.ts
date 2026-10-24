import { describe, it, expect } from 'vitest'
import { snapshotWorkspace, readSnapshot, legacyDAG } from './workspaceSnapshot'
import { createDAGState, appliedRuleKey } from '../hooks/dagState'
import { buildParentIndex } from './dagReverseIndex'
import type { FragmentNode } from '../types/fragment'
import type { SessionWorkspaceState } from '../types/session'
const node = (id: string, children: FragmentNode[] = [], ruleName: string | null = null): FragmentNode =>
  ({ id, type:'ORIGINAL', label:id, sqlFragment:`SELECT '${id}'`, executable:true, executionHint:null,
    nodeCount:children.length, ruleName, children })
const ws: SessionWorkspaceState = { sessionId:'s', tabName:'s', sql:'unparsed draft', isDirty:false, lastSavedAt:null,
  dialect:'postgresql', connectionId:null, fragmentTreeJson:null, selectedNodeId:'shared', workspaceMode:'data', viewerMode:'browse' }
function fixture() {
  const shared = node('shared', [], 'split'), a=node('a',[shared],'root-split'), b=node('b',[shared],'other-split'), root=node('root',[a,b])
  const dag=createDAGState()
  for (const n of [root,a,b,shared]) dag.nodeMap.set(n.id,n)
  for (const [sourceNodeId,ruleName,childIds] of [['root','root-split',['a']],['root','other-split',['b']],['a','split',['shared']],['b','split',['shared']],['shared','empty',[]]] as const) {
    dag.ruleResults.set(appliedRuleKey(sourceNodeId,ruleName),{sourceNodeId,ruleName,childIds:[...childIds]})
  }
  return snapshotWorkspace(ws,root,dag,'shared','parsed SQL')
}
describe('versioned workspace snapshot', () => {
  it('keeps new completeness in graph and projection without upgrading old summaries', () => {
    const raw=JSON.parse(fixture())
    raw.graph.nodes[0].executionSummary={rows:2,durationMs:3,resultCompleteness:{state:'TRUNCATED',appliedLimit:2,errorReason:null}}
    raw.treeProjection.executionSummary=raw.graph.nodes[0].executionSummary
    raw.treeProjection.children[0].executionSummary={rows:1,durationMs:2}
    const decoded=readSnapshot(JSON.stringify(raw))
    expect(decoded.dag.nodeMap.get('root')?.executionSummary?.resultCompleteness?.state).toBe('TRUNCATED')
    expect(decoded.snapshot.treeProjection?.executionSummary?.resultCompleteness?.appliedLimit).toBe(2)
    expect(decoded.snapshot.treeProjection?.children[0].executionSummary?.resultCompleteness).toBeUndefined()
  })
  it('roundtrips a diamond, multiple rules and empty success separately from draft SQL', () => {
    const { snapshot,dag }=readSnapshot(fixture())
    expect(snapshot.parsedSql).toBe('parsed SQL')
    expect(ws.sql).toBe('unparsed draft')
    expect(dag.ruleResults.size).toBe(5)
    expect(dag.appliedRules.has(appliedRuleKey('shared','empty'))).toBe(false)
    expect(dag.decomposedChildren.get('root')?.map(n=>n.id)).toEqual(['a','b'])
    expect(buildParentIndex(dag,snapshot.treeProjection)).toMatchObject({degraded:false,byChildId:new Map([['a',['root']],['b',['root']],['shared',['a','b']]])})
  })
  it('falls back for missing selection with a visible reason', () => {
    const data=JSON.parse(fixture());data.selectedNodeId='missing'
    expect(readSnapshot(JSON.stringify(data))).toMatchObject({snapshot:{selectedNodeId:'root'},selectionReason:expect.stringMatching(/missing/)})
  })
  it('reveals a DAG-only selection through real edges', () => {
    const data=JSON.parse(fixture());data.treeProjection.children=[]
    const restored=readSnapshot(JSON.stringify(data)).snapshot.treeProjection!
    expect(restored.children[0].id).toBe('a')
    expect(restored.children[0].children[0].id).toBe('shared')
  })
  it.each(['version','cycle','endpoint','rule','projection','duplicate'] as const)('rejects invalid %s before hydration', kind => {
    const data=JSON.parse(fixture())
    if(kind==='version')data.version=99
    if(kind==='cycle')data.graph.edges.push({sourceNodeId:'shared',targetNodeId:'root',ruleName:'cycle'})
    if(kind==='endpoint')data.graph.edges[0].targetNodeId='missing'
    if(kind==='rule')data.graph.ruleResults[0].childIds=[]
    if(kind==='projection')data.treeProjection.children[0].id='missing'
    if(kind==='duplicate')data.graph.nodes.push(data.graph.nodes[0])
    expect(()=>readSnapshot(JSON.stringify(data))).toThrow()
  })
  it('retains every parent visible in a legacy tree and preserves incompleteness', () => {
    const shared=node('shared',[],'split'), tree=node('root',[node('a',[shared],'split'),node('b',[shared],'split')])
    const dag=legacyDAG(tree)
    const decoded=readSnapshot(snapshotWorkspace(ws,tree,dag,'shared','legacy'))
    expect(buildParentIndex(decoded.dag,decoded.snapshot.treeProjection).byChildId.get('shared')).toEqual(['a','b'])
    expect(buildParentIndex(decoded.dag,decoded.snapshot.treeProjection).reason).toBe('MULTI_PARENT_NOT_PERSISTED')
  })
})
