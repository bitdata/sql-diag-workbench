import { tr } from '../i18n'
// src/renderer/utils/dagReverseIndex.ts
import type { FragmentNode } from '../types/fragment'
import type { DAGState } from '../hooks/dagState'

/**
 * Why a parent-edge index is incomplete. Every degradation is observable by
 * the caller (CLAUDE.md rule #2) — the add-back panel and banner render the
 * text from `describeReverseIndexReason`, never a bare log line.
 *
 * - MULTI_PARENT_NOT_PERSISTED: legacy tree recovery cannot prove that all
 *   explored edges were saved. Repeated node IDs can recover several parents,
 *   but missing edges remain unknown. New workspace snapshots restore all
 *   recorded rule results and their edges into `decomposedChildren`.
 * - NO_EDGES: neither source produced an edge — there is nothing to add back.
 * - PARENT_NOT_IN_DAG: the edge exists but its parent id is not in the node map.
 * - MULTIPLE_ORIGINS: the reveal state exposes two or more disjoint maximal
 *   ancestors — one tree can show only one root (produced by the projection
 *   layer, see Task 3; the index itself never returns it).
 */
export type ReverseIndexDegradedReason =
  | 'MULTI_PARENT_NOT_PERSISTED'
  | 'NO_EDGES'
  | 'PARENT_NOT_IN_DAG'
  | 'MULTIPLE_ORIGINS'

/** Single source of truth for user-facing degradation copy. */
const REASON_TEXT: Record<ReverseIndexDegradedReason, string> = {
  MULTI_PARENT_NOT_PERSISTED: "Only tree parents shown (multiple parents were not persisted)",
  NO_EDGES: "No add-back paths available",
  PARENT_NOT_IN_DAG: "This parent is not in the current view",
  MULTIPLE_ORIGINS: "Multiple upward branches exist; only one is shown",
}

export function describeReverseIndexReason(reason: ReverseIndexDegradedReason | null): string {
  return reason ? tr(REASON_TEXT[reason]) : ''
}

export interface ParentIndex {
  /** childId → the ids of every facet that decomposes into it. */
  byChildId: Map<string, string[]>
  /** True when the edge set is known to be incomplete. */
  degraded: boolean
  reason: ReverseIndexDegradedReason | null
}

/** Read/dedupe/push-or-set for one childId → parentId edge. Shared by both sources. */
function addEdge(map: Map<string, string[]>, childId: string, parentId: string): void {
  const existing = map.get(childId)
  if (existing) {
    if (!existing.includes(parentId)) existing.push(parentId)
  } else {
    map.set(childId, [parentId])
  }
}

/**
 * Walk a tree's `children` arrays into childId → [parentId] edges. This is the
 * FALLBACK source: one parent per occurrence; repeated IDs can have several.
 */
export function treeNestingEdges(tree: FragmentNode | null): Map<string, string[]> {
  const edges = new Map<string, string[]>()
  if (!tree) return edges
  const walk = (parent: FragmentNode): void => {
    for (const child of parent.children) {
      addEdge(edges, child.id, parent.id)
      walk(child)
    }
  }
  walk(tree)
  return edges
}

/**
 * Derive the parent-edge index for add-back exploration.
 *
 * Source 1 (authoritative, multi-parent aware): reverse-scan
 * `dag.decomposedChildren` — the map is source → children, so inverting it
 * groups every facet that produced the same child (X → [A, B]).
 * Source 2 (fallback): tree nesting, merged in PER CHILD for any child the
 * authoritative source does not already cover.
 */
export function buildParentIndex(dag: DAGState, tree: FragmentNode | null): ParentIndex {
  const byChildId = new Map<string, string[]>()
  for (const [sourceId, children] of dag.decomposedChildren) {
    for (const child of children) addEdge(byChildId, child.id, sourceId)
  }

  // Merge per CHILD, not per source: a non-empty decomposedChildren does not
  // imply it covers every child in a legacy or partial tree projection.
  let treeContributed = false
  for (const [childId, parentIds] of treeNestingEdges(tree)) {
    const existing = byChildId.get(childId)
    if (!existing) {
      byChildId.set(childId, [...parentIds])
      treeContributed = true
      continue
    }
    for (const parentId of parentIds) {
      if (!existing.includes(parentId)) {
        existing.push(parentId)
        treeContributed = true
      }
    }
  }

  if (byChildId.size === 0) {
    return { byChildId, degraded: true, reason: 'NO_EDGES' }
  }
  if (treeContributed || dag.completeness === 'legacy-tree') {
    return { byChildId, degraded: true, reason: 'MULTI_PARENT_NOT_PERSISTED' }
  }
  return { byChildId, degraded: false, reason: null }
}

