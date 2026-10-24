import { tr } from '../i18n'
// src/renderer/utils/addBackProjection.ts
import type { FragmentNode } from '../types/fragment'
import type { DAGState } from '../hooks/dagState'
import type { ParentIndex, ReverseIndexDegradedReason } from './dagReverseIndex'

export const ORIGIN_SUFFIX = ' (original SQL)'
export const UNRESOLVED_SUFFIX = ' (not restored to original SQL)'

/** Add-back exploration state: the seed plus the parent edges the user has
 *  expanded so far. Immutable — every mutation returns a new object. */
export interface AddBackState {
  seedNodeId: string | null
  /** childId → the parent ids revealed for it (one hop at a time). */
  revealedParents: Map<string, Set<string>>
}

export function createAddBackState(seedNodeId: string): AddBackState {
  return { seedNodeId, revealedParents: new Map() }
}

export function isRevealed(state: AddBackState, childId: string, parentId: string): boolean {
  return state.revealedParents.get(childId)?.has(parentId) ?? false
}

export function revealParent(state: AddBackState, childId: string, parentId: string): AddBackState {
  const next = new Map(state.revealedParents)
  const set = new Set(next.get(childId) ?? [])
  set.add(parentId)
  next.set(childId, set)
  return { ...state, revealedParents: next }
}

export function hideParent(state: AddBackState, childId: string, parentId: string): AddBackState {
  const next = new Map(state.revealedParents)
  const set = new Set(next.get(childId) ?? [])
  set.delete(parentId)
  if (set.size === 0) next.delete(childId)
  else next.set(childId, set)
  return { ...state, revealedParents: next }
}

export interface AddBackCandidate {
  parentId: string
  node: FragmentNode
}

export interface AddBackCandidates {
  candidates: AddBackCandidate[]
  /** Parent ids the index knows about but the DAG cannot resolve. Rendered as
   *  DISABLED rows with a reason — never silently dropped (spec §5.3). */
  skipped: string[]
  degraded: boolean
  reason: ReverseIndexDegradedReason | null
}

/**
 * All parent facets of `childId` — every DAG in-edge, not just the tree parent.
 * `degraded`/`reason` combine the index-level degradation (incomplete edge set)
 * with the resolution-level one (dangling id), so the caller always has one
 * flag to render (rule #2).
 */
export function computeAddBackCandidates(
  childId: string,
  dag: DAGState,
  index: ParentIndex
): AddBackCandidates {
  const parentIds = index.byChildId.get(childId) ?? []
  const candidates: AddBackCandidate[] = []
  const skipped: string[] = []
  for (const parentId of parentIds) {
    const node = dag.nodeMap.get(parentId)
    if (!node) {
      skipped.push(parentId)
      continue
    }
    candidates.push({ parentId, node })
  }
  const reason: ReverseIndexDegradedReason | null = index.degraded
    ? index.reason
    : skipped.length > 0 ? 'PARENT_NOT_IN_DAG' : null
  return { candidates, skipped, degraded: reason !== null, reason }
}

export interface OriginWalk {
  /** seed first, terminal facet last. */
  path: string[]
  /** False when the walk stopped on a cycle instead of a facet with no in-edge. */
  reachesOrigin: boolean
}

/**
 * Index-only walk from the seed to the topmost reachable facet, taking the
 * FIRST parent at each hop. Terminal = a facet the index has no in-edge for
 * (in a complete index that is the DAG root / the original SQL).
 */
export function walkToOrigin(seedNodeId: string, index: ParentIndex): OriginWalk {
  const path: string[] = [seedNodeId]
  const seen = new Set<string>([seedNodeId])
  let cursor = seedNodeId
  for (;;) {
    const parents = index.byChildId.get(cursor)
    if (!parents || parents.length === 0) return { path, reachesOrigin: true }
    const next = parents[0]
    if (seen.has(next)) return { path, reachesOrigin: false }
    seen.add(next)
    path.push(next)
    cursor = next
  }
}

export interface AddBackProjection {
  /** Display root = the topmost revealed ancestor; the seed is the deepest leaf. */
  tree: FragmentNode | null
  /** Labels from seed to origin, last one suffixed. */
  breadcrumb: string[]
  originId: string | null
  reachesOrigin: boolean
  /** True only when the display root IS the DAG root (no in-edge in the index);
   *  the sole case the UI may label the root 原 SQL (spec §4.3.4 #2). */
  isOriginRoot: boolean
  skipped: string[]
  degraded: boolean
  reason: ReverseIndexDegradedReason | null
}

function dedupe(ids: string[]): string[] {
  return Array.from(new Set(ids))
}

/**
 * Project the add-back exploration state into a renderable tree.
 *
 * Direction (spec §4.3.1): the ROOT is the topmost revealed ancestor and the
 * SEED is the deepest leaf, so the user always sees the original SQL above the
 * facet they started from. Inverting the parent edges also means a facet with
 * two revealed parents legitimately appears TWICE — real ids, no synthesis.
 *
 * Invariant (spec §4.3.7): PURE function of (state, dag, index). It never
 * touches `result.tree`; the caller renders its output without writing it
 * back, so a session can never persist a projection.
 */
export function buildAddBackProjection(
  state: AddBackState,
  dag: DAGState,
  index: ParentIndex
): AddBackProjection {
  const skipped: string[] = []
  const seedId = state.seedNodeId
  if (!seedId) {
    return { tree: null, breadcrumb: [], originId: null, reachesOrigin: false, isOriginRoot: false, skipped, degraded: false, reason: null }
  }
  const seed = dag.nodeMap.get(seedId)
  if (!seed) {
    // `skipped` here means "invalid seed" — a hard failure (also signalled by
    // `tree: null`), NOT a degradation. That is why this is the one return path
    // with a non-empty `skipped` but `degraded: false` / `reason: null`: no
    // ParentIndex reason is true of a seed that is not even a parent.
    return { tree: null, breadcrumb: [], originId: null, reachesOrigin: false, isOriginRoot: false, skipped: [seedId], degraded: false, reason: null }
  }

  // 1. Resolve the revealed parent edges into real nodes (childId → parents).
  const parentsOf = new Map<string, FragmentNode[]>()
  const collect = (nodeId: string, visited: Set<string>): void => {
    const nextVisited = new Set(visited)
    nextVisited.add(nodeId)
    const resolved: FragmentNode[] = []
    for (const parentId of state.revealedParents.get(nodeId) ?? []) {
      if (nextVisited.has(parentId)) continue            // cycle guard
      const parent = dag.nodeMap.get(parentId)
      if (!parent) {
        skipped.push(parentId)
        continue
      }
      resolved.push(parent)
      collect(parentId, nextVisited)
    }
    resolved.sort((a, b) => a.id.localeCompare(b.id))
    parentsOf.set(nodeId, resolved)
  }
  collect(seedId, new Set())

  // 2. The display root is the topmost revealed ancestor. Several disjoint
  //    maximal ancestors cannot share one root — show the first, report the rest.
  const roots = Array.from(parentsOf.keys())
    .filter((id) => (parentsOf.get(id) ?? []).length === 0)
    .sort()
  const rootId = roots[0] ?? seedId
  const extraRoots = roots.slice(1)
  skipped.push(...extraRoots)

  // The display root may only be called 原 SQL when it is itself the DAG root
  // (spec §4.3.4 #2) — i.e. the index knows no in-edge for it. NO_EDGES needs no
  // extra guard here: it means BOTH edge sources are empty, and tree nesting is
  // empty only for a null or childless tree — so a reachable NO_EDGES index
  // implies a single-node tree whose node IS the DAG root. Suppressing the label
  // there would make Task 9 render 未还原到原 SQL about the original SQL itself.
  const isOriginRoot = (index.byChildId.get(rootId) ?? []).length === 0

  // 3. Invert the edges and emit downward from the root. Duplicated ids under
  //    different ancestors are expected (a DAG is not a tree) — see Task 9's
  //    flattenTree key fix.
  const childrenOf = new Map<string, string[]>()
  for (const [childId, parents] of parentsOf) {
    for (const parent of parents) {
      const list = childrenOf.get(parent.id)
      if (list) list.push(childId)
      else childrenOf.set(parent.id, [childId])
    }
  }
  const buildDown = (nodeId: string, visited: Set<string>): FragmentNode | null => {
    const node = dag.nodeMap.get(nodeId)
    if (!node) return null
    const nextVisited = new Set(visited)
    nextVisited.add(nodeId)
    const children: FragmentNode[] = []
    for (const childId of (childrenOf.get(nodeId) ?? []).slice().sort()) {
      if (nextVisited.has(childId)) continue             // cycle guard
      const child = buildDown(childId, nextVisited)
      if (child) children.push(child)
    }
    return { ...node, children, nodeCount: children.length }
  }
  const tree = buildDown(rootId, new Set())

  // 4. Breadcrumb + origin, from the index (not from the reveal state) so the
  //    user can see the whole remaining path to the original SQL.
  const walk = walkToOrigin(seedId, index)
  const originId = walk.path[walk.path.length - 1] ?? null
  const originResolved = originId !== null && dag.nodeMap.has(originId)
  const reachesOrigin = walk.reachesOrigin && originResolved
  if (!originResolved && originId) skipped.push(originId)

  const breadcrumb = walk.path.map((id) => dag.nodeMap.get(id)?.label ?? id)
  if (breadcrumb.length > 0) {
    const last = breadcrumb.length - 1
    breadcrumb[last] = `${breadcrumb[last]}${tr(reachesOrigin ? ORIGIN_SUFFIX : UNRESOLVED_SUFFIX)}`
  }

  // Precedence is deliberate: an incomplete edge set outranks everything else
  // (it explains why the candidates themselves may be short).
  const reason: ReverseIndexDegradedReason | null = index.degraded
    ? index.reason
    : extraRoots.length > 0
      ? 'MULTIPLE_ORIGINS'
      : skipped.length > 0 ? 'PARENT_NOT_IN_DAG' : null

  return {
    tree, breadcrumb, originId, reachesOrigin, isOriginRoot,
    skipped: dedupe(skipped), degraded: reason !== null, reason,
  }
}
