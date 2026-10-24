import { tr } from '../i18n'
import type { Tab } from '../types/tab'
import type { FragmentNode } from '../types/fragment'
import type { DAGState } from '../hooks/dagState'
import { legacyDAG, readSnapshot } from './workspaceSnapshot'

export interface CompareTarget {
  sessionId: string; tabName: string; node: FragmentNode; dialect: string; version: string
}
export interface CompareSource {
  sessionId: string; tabName: string; dialect: string; version: string
  nodes: FragmentNode[]; errorReason: string | null; degraded: boolean
}
export interface ActiveCompareGraph { sessionId: string | null; dag: DAGState; parsedSql: string; dialect: string; epoch: number }

/** Decode once per unchanged saved snapshot; active sources always use the live DAG. */
export function createCompareSourceReader() {
  const cache = new Map<string, { raw: string; source: CompareSource }>()
  return (tabs: Tab[], active: ActiveCompareGraph): CompareSource[] => {
    const ids = new Set(tabs.map(t => t.sessionId))
    for (const id of cache.keys()) if (!ids.has(id)) cache.delete(id)
    return tabs.map(tab => {
      const ws = tab.workspace
      const base: CompareSource = { sessionId: tab.sessionId, tabName: tab.name, dialect: ws.dialect,
        version: '', nodes: [], errorReason: null, degraded: false }
      if (ws.hydration !== 'ready' || ws.recoveryBlocked) return { ...base,
        errorReason: ws.hydration === 'loading' ? 'Workspace is loading' : ws.recoveryError ?? 'Workspace is unavailable' }
      if (tab.sessionId === active.sessionId) return { ...base, dialect: active.dialect,
        // Only replacement, not newly revealed facets, changes the source revision.
        version: JSON.stringify([ws.loadEpoch, active.parsedSql, active.dialect, active.epoch]),
        nodes: [...active.dag.nodeMap.values()], degraded: active.dag.completeness === 'legacy-tree',
        errorReason: active.dag.nodeMap.size ? null : 'Parse SQL first' }
      const raw = JSON.stringify([ws.loadEpoch, ws.workspaceSnapshotJson, ws.fragmentTreeJson])
      const saved = cache.get(tab.sessionId)
      if (saved?.raw === raw) return { ...saved.source, tabName: tab.name }
      let source = base
      try {
        if (ws.workspaceSnapshotJson) {
          const { snapshot, dag } = readSnapshot(ws.workspaceSnapshotJson)
          source = { ...base, version: JSON.stringify([ws.loadEpoch, snapshot.parsedSql, snapshot.dialect]),
            dialect: snapshot.dialect, nodes: [...dag.nodeMap.values()], degraded: dag.completeness === 'legacy-tree' }
        } else if (ws.fragmentTreeJson) {
          const dag = legacyDAG(JSON.parse(ws.fragmentTreeJson))
          source = { ...base, version: JSON.stringify([ws.loadEpoch, ws.sql, ws.dialect]), nodes: [...dag.nodeMap.values()], degraded: true }
        }
        if (!source.nodes.length) source = { ...source, errorReason: 'Parse SQL first' }
      } catch (e) {
        source = { ...base, errorReason: `Workspace cannot be read: ${e instanceof Error ? e.message : String(e)}` }
      }
      cache.set(tab.sessionId, { raw, source })
      return source
    })
  }
}
export function compareTarget(source: CompareSource, node: FragmentNode): CompareTarget {
  return { sessionId: source.sessionId, tabName: source.tabName, dialect: source.dialect, version: source.version,
    node: { ...node, children: [] } }
}
export function targetIssue(target: CompareTarget | null, sources: CompareSource[]): string | null {
  if (!target) return tr("Choose a fragment")
  const source = sources.find(s => s.sessionId === target.sessionId)
  if (!source) return 'Source tab is closed. Select a fragment again to run.'
  if (source.errorReason) return source.errorReason
  const node = source.nodes.find(n => n.id === target.node.id)
  if (source.version !== target.version || !node || node.sqlFragment !== target.node.sqlFragment || source.dialect !== target.dialect)
    return 'Source has changed. Select a fragment again to run.'
  return null
}
