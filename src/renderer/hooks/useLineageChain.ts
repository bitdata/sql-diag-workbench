import { tr, useLocale, getLocale } from '../i18n'
import { useMemo } from 'react'
import type { FragmentNode } from '../types/fragment'

export interface LineageChainResult {
  /** Operation tree path from root to selectedNode (inclusive), spec §3.3 L1. */
  chain: FragmentNode[]
  /** Explains an incomplete path or the representative path through a DAG. */
  pathNotice: string | null
  /**
   * Diff-graph neighbors not on the operation path. Computed client-side as
   * a stub — full implementation needs /api/diff cross-product (spec §3.3 L2).
   * Returns [] for now; LineagePanel renders a "no virtual edges" placeholder.
   */
  diffNeighbors: FragmentNode[]
}

/**
 * Walks edit links when present, otherwise decomposition parentIds.
 * A DAG may have several derivations: show its first recorded parent and
 * disclose that this is one source path, not a chronological operation log.
 * Empty chain when selectedNodeId is null or not found.
 */
export function useLineageChain(
  allNodes: FragmentNode[],
  selectedNodeId: string | null
): LineageChainResult {
  useLocale()
  return useMemo(() => {
    if (!selectedNodeId || allNodes.length === 0) {
      return { chain: [], diffNeighbors: [], pathNotice: null }
    }
    const byId = new Map(allNodes.map((n) => [n.id, n]))
    const start = byId.get(selectedNodeId)
    if (!start) return { chain: [], diffNeighbors: [], pathNotice: tr("The selected fragment is unavailable.") }

    const chain: FragmentNode[] = [start]
    const seen = new Set<string>([start.id])
    let cursor: FragmentNode | undefined = start
    const notices = new Set<string>()
    while (cursor) {
      const parents = [...new Set(cursor.parentIds ?? [])]
      if (!cursor.editParentId && parents.length > 1) {
        notices.add(tr("Multiple sources exist; showing the first recorded source path, not an operation history."))
      }
      const pid: string | undefined = cursor.editParentId ?? parents[0]
      if (!pid) break
      if (seen.has(pid)) {
        notices.add(tr("Source path is incomplete: a circular reference was detected."))
        break
      }
      const parent = byId.get(pid)
      if (!parent) {
        notices.add(tr("Source path is incomplete: an ancestor is unavailable in this tab."))
        break
      }
      chain.push(parent)
      seen.add(pid)
      cursor = parent
    }
    chain.reverse() // root first
    return { chain, diffNeighbors: [], pathNotice: notices.size ? [...notices].join(' ') : null }
  }, [allNodes, selectedNodeId, getLocale()])
}
