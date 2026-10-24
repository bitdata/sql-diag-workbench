import { sha256 } from './sha256'
import type { FragmentNode, RuleResult } from '../types/fragment'
import type { SessionWorkspaceState } from '../types/session'
import { createDAGState, appliedRuleKey, indexTreeIntoDAG, type DAGState } from '../hooks/dagState'

export interface WorkspaceSnapshot {
  version: 1 | 2
  parsedSql: string
  dialect: string
  graph: null | {
    rootNodeId: string
    nodes: FragmentNode[]
    edges: { sourceNodeId: string; targetNodeId: string; ruleName: string }[]
    ruleResults: RuleResult[]
    completeness: 'recorded' | 'legacy-tree'
  }
  treeProjection: FragmentNode | null
  selectedNodeId: string | null
  viewerMode: 'input' | 'browse'
  workspaceMode: 'data' | 'performance'
}

export function snapshotWorkspace(ws: SessionWorkspaceState, tree: FragmentNode | null, dag: DAGState,
                                  selectedNodeId: string | null, parsedSql: string, parsedDialect = ws.dialect): string {
  const records = [...dag.ruleResults.values()].map(r => r.status ? r : { ...r, status: 'LEGACY' as const, outputs: [], issues: [{ code: 'LEGACY_TRANSFORM_UNKNOWN', message: 'No verified experiment description' }] })
  const snapshot: WorkspaceSnapshot = {
    version: 2, parsedSql, dialect: parsedDialect,
    graph: tree ? {
      rootNodeId: tree.id,
      nodes: [...dag.nodeMap.values()].map(node => ({ ...node, children: [], parentIds: [] })),
      edges: records.flatMap(r => r.childIds.map(targetNodeId => ({ sourceNodeId: r.sourceNodeId, targetNodeId, ruleName: r.ruleName }))),
      ruleResults: records, completeness: dag.completeness,
    } : null,
    treeProjection: tree, selectedNodeId, viewerMode: ws.viewerMode, workspaceMode: ws.workspaceMode,
  }
  return JSON.stringify(snapshot)
}

function validNode(n: FragmentNode): boolean {
  return !!n && typeof n.id === 'string' && !!n.id && typeof n.sqlFragment === 'string'
    && typeof n.label === 'string' && typeof n.executable === 'boolean' && Array.isArray(n.children)
}

/** Throw before any state is applied; corrupt data never becomes an autosave source. */
export function readSnapshot(raw: string): { snapshot: WorkspaceSnapshot; dag: DAGState; selectionReason: string | null } {
  const s = JSON.parse(raw) as WorkspaceSnapshot
  if (!s || ![1, 2].includes(s.version)) throw new Error('Unsupported workspace snapshot version')
  if (!['input', 'browse'].includes(s.viewerMode) || !['data', 'performance'].includes(s.workspaceMode)
      || typeof s.parsedSql !== 'string' || !['postgresql', 'mysql', 'sqlserver', 'oracle'].includes(s.dialect)
      || (s.selectedNodeId !== null && typeof s.selectedNodeId !== 'string')) throw new Error('Invalid workspace context')
  const dag = createDAGState()
  let selectionReason: string | null = null
  if (s.graph === null) {
    if (s.treeProjection !== null || s.selectedNodeId !== null) throw new Error('Invalid empty workspace')
    return { snapshot: s, dag, selectionReason }
  }
  const g = s.graph
  if (!g || !Array.isArray(g.nodes) || !Array.isArray(g.edges) || !Array.isArray(g.ruleResults)
      || !['recorded', 'legacy-tree'].includes(g.completeness)) throw new Error('Invalid decomposition graph')
  dag.completeness = g.completeness
  for (const n of g.nodes) {
    if (!validNode(n) || n.children.length || dag.nodeMap.has(n.id)) throw new Error('Invalid or duplicate graph node')
    dag.nodeMap.set(n.id, n)
  }
  if (!dag.nodeMap.has(g.rootNodeId)) throw new Error('Missing graph root')
  const edgeKeys = new Set<string>()
  const outgoing = new Map<string, string[]>()
  for (const e of g.edges) {
    if (!dag.nodeMap.has(e.sourceNodeId) || !dag.nodeMap.has(e.targetNodeId) || typeof e.ruleName !== 'string') throw new Error('Invalid graph edge')
    const key = JSON.stringify([e.sourceNodeId, e.ruleName, e.targetNodeId])
    if (edgeKeys.has(key)) throw new Error('Duplicate graph edge')
    edgeKeys.add(key)
    outgoing.set(e.sourceNodeId, [...(outgoing.get(e.sourceNodeId) ?? []), e.targetNodeId])
  }
  const visiting = new Set<string>(), visited = new Set<string>()
  const visit = (id: string) => {
    if (visiting.has(id)) throw new Error('Cyclic decomposition graph')
    if (visited.has(id)) return
    visiting.add(id)
    for (const child of outgoing.get(id) ?? []) visit(child)
    visiting.delete(id); visited.add(id)
  }
  for (const id of dag.nodeMap.keys()) visit(id)
  const recordEdges = new Set<string>()
  for (const r of g.ruleResults) {
    if (!dag.nodeMap.has(r.sourceNodeId) || typeof r.ruleName !== 'string' || !Array.isArray(r.childIds)
      || new Set(r.childIds).size !== r.childIds.length) throw new Error('Invalid rule result')
    const key = appliedRuleKey(r.sourceNodeId, r.ruleName)
    if (dag.ruleResults.has(key)) throw new Error('Duplicate rule result')
    if (s.version === 2 && r.status !== 'LEGACY') {
      if (!['COMPLETE', 'PARTIAL', 'NOT_APPLICABLE', 'FAILED'].includes(r.status ?? '') || !Array.isArray(r.outputs) || !Array.isArray(r.issues)
        || r.sourceSqlHash !== sha256(dag.nodeMap.get(r.sourceNodeId)!.sqlFragment)) throw new Error('Invalid transform source or status')
      const positions = new Set<number>(), targets = new Set<string>()
      for (const o of r.outputs) {
        const target = dag.nodeMap.get(o.targetNodeId)
        if (!target || o.sourceNodeId !== r.sourceNodeId || o.ruleName !== r.ruleName || !Number.isInteger(o.ordinal) || o.ordinal < 1 || positions.has(o.ordinal)
          || !o.effect || o.effect.sourceSqlHash !== r.sourceSqlHash || o.effect.targetSqlHash !== sha256(target.sqlFragment)
          || !['PRESERVED', 'DETACHED', 'UNKNOWN'].includes(o.effect.context) || !Array.isArray(o.effect.changes) || !Array.isArray(o.effect.retained) || !Array.isArray(o.effect.limitations)) throw new Error('Invalid transform output')
        positions.add(o.ordinal); targets.add(o.targetNodeId)
      }
      if (r.childIds.length !== targets.size || r.childIds.some(id => !targets.has(id)) || r.status === 'FAILED' && r.childIds.length) throw new Error('Transform outputs disagree with graph')
    } else {
      r.status = 'LEGACY'; r.outputs = []; r.issues = [{ code: 'LEGACY_TRANSFORM_UNKNOWN', message: 'Saved exploration has no verified experiment description' }]
    }
    dag.ruleResults.set(key, r)
    // Unknown legacy rule labels express edges only, never hide a real menu option.
    if (r.ruleName && (r.status === 'COMPLETE' || r.status === 'NOT_APPLICABLE')) dag.appliedRules.add(key)
    const children = r.childIds.map(id => {
      const edge = JSON.stringify([r.sourceNodeId, r.ruleName, id])
      if (!edgeKeys.has(edge)) throw new Error('Rule result has no matching edge')
      recordEdges.add(edge)
      return dag.nodeMap.get(id)!
    })
    dag.decomposedChildren.set(r.sourceNodeId, [...(dag.decomposedChildren.get(r.sourceNodeId) ?? []), ...children])
  }
  if (recordEdges.size !== edgeKeys.size) throw new Error('Edge has no matching rule result')
  const checkTree = (n: FragmentNode, ancestors: Set<string>) => {
    if (!validNode(n) || !dag.nodeMap.has(n.id) || n.sqlFragment !== dag.nodeMap.get(n.id)?.sqlFragment || ancestors.has(n.id)) throw new Error('Invalid tree projection')
    const next = new Set(ancestors).add(n.id)
    for (const c of n.children) {
      if (!g.edges.some(e => e.sourceNodeId === n.id && e.targetNodeId === c.id)) throw new Error('Projection invents an edge')
      checkTree(c, next)
    }
  }
  if (!s.treeProjection || s.treeProjection.id !== g.rootNodeId) throw new Error('Invalid projection root')
  checkTree(s.treeProjection, new Set())
  if (s.selectedNodeId && !dag.nodeMap.has(s.selectedNodeId)) {
    s.selectedNodeId = g.rootNodeId
    selectionReason = 'Saved selection was missing; restored the root instead.'
  }
  // Reveal a selected DAG-only node through a real root-to-node path.
  if (s.selectedNodeId) {
    const contains = (n: FragmentNode): boolean => n.id === s.selectedNodeId || n.children.some(contains)
    if (!contains(s.treeProjection)) {
      const path = (id: string): string[] | null => {
        if (id === s.selectedNodeId) return [id]
        for (const child of outgoing.get(id) ?? []) { const p = path(child); if (p) return [id, ...p] }
        return null
      }
      const ids = path(g.rootNodeId)
      if (!ids) throw new Error('Selected node is unreachable from graph root')
      const reveal = (n: FragmentNode, depth: number): FragmentNode => {
        const childId = ids[depth + 1]
        if (!childId) return n
        const existing = n.children.find(c => c.id === childId)
        const child = reveal(existing ?? dag.nodeMap.get(childId)!, depth + 1)
        return { ...n, children: existing ? n.children.map(c => c === existing ? child : c) : [...n.children, child] }
      }
      s.treeProjection = reveal(s.treeProjection, 0)
    }
  }
  // Restore adjacency from authoritative records without overwriting SQL/metadata with projection copies.
  for (const [id, node] of dag.nodeMap) dag.nodeMap.set(id, { ...node, children: dag.decomposedChildren.get(id) ?? [],
    parentIds: g.edges.filter(e => e.targetNodeId === id).map(e => e.sourceNodeId) })
  return { snapshot: s, dag, selectionReason }
}

export function legacyDAG(tree: FragmentNode): DAGState {
  const dag = createDAGState()
  dag.completeness = 'legacy-tree'
  const walk = (n: FragmentNode, ancestors: Set<string>) => {
    if (!validNode(n) || ancestors.has(n.id)) throw new Error('Invalid saved fragment tree')
    dag.nodeMap.set(n.id, n)
    const next = new Set(ancestors).add(n.id)
    for (const c of n.children) {
      const ruleName = c.ruleName ?? ''
      const key = appliedRuleKey(n.id, ruleName)
      const r = dag.ruleResults.get(key) ?? { sourceNodeId: n.id, ruleName, childIds: [] }
      if (!r.childIds.includes(c.id)) r.childIds.push(c.id)
      dag.ruleResults.set(key, r)
      if (ruleName) dag.appliedRules.add(key)
      walk(c, next)
    }
    dag.decomposedChildren.set(n.id, [...new Map([...(dag.decomposedChildren.get(n.id) ?? []), ...n.children].map(c => [c.id, c])).values()])
  }
  walk(tree, new Set())
  return dag
}
