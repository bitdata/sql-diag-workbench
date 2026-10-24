import { apiFetch } from '../utils/api'
import { useEffect, useState, useRef } from 'react'
import type { FragmentNode } from '../types/fragment'
import type { SqlDialect } from '../types/session'
import type { ChangeOp } from './useChangeAttribution'
import type { DiffMethod } from '../utils/diff'
import { normalizeDiffMethod } from '../utils/diff'

export interface DiffGraphNode {
  node: FragmentNode
  /** True if this node is on the operation chain (solid line). False = ghost / dead-end. */
  isOnChain: boolean
}

export interface VirtualEdge {
  /** Source: typically the current/selected node (the "from" of the operation). */
  from: FragmentNode
  /** Target: a ghost (dead-end) node. */
  to: FragmentNode
  /** Δ(from, to) — the diff the user "didn't take". */
  ops: ChangeOp[]
  /**
   * True if the diff is a subset of a containing diff (spec §3.3 L2 / §5.2.3).
   * This is the diagnostic case: it shows that the dead-end is "explainable
   * by what we already changed", which is the value the spec's diagnosis
   * scenario (D→B closing ΔAB \ ΔAD) is built around.
   */
  isVirtual: boolean
  /**
   * Per-edge degraded flag (from backend `DiffResponse.degraded`). True when
   * the backend fell back to path-set diff because AST-LCS gave up. The
   * diagnostic value of a virtual edge collapses when this is true (the
   * subset check is no longer trustworthy), so the renderer should badge it.
   */
  degraded: boolean
  /**
   * Per-edge diff method (normalized from the backend's wire string).
   * Empty when no fetch succeeded; `'unknown'` for unrecognized values.
   */
  diffMethod: DiffMethod | ''
}

interface State {
  ghosts: DiffGraphNode[]
  edges: VirtualEdge[]
  loading: boolean
  error: string | null
  /**
   * Aggregate degraded flag — true if ANY virtual edge is degraded. Lets the
   * renderer show a top-level "diff-graph is approximate" banner without
   * having to scan every edge. Matches the spec §3.3 L2 degraded-observability
   * requirement.
   */
  anyDegraded: boolean
}

/**
 * Find dead-end / ghost nodes: nodes in the tree that are NOT on the
 * current operation chain and NOT descendants of any chain node.
 * These are siblings of chain nodes (e.g. B in the A→C→D scenario, where
 * A also has child B but the user went A→C→D).
 */
function computeGhosts(chain: FragmentNode[], allNodes: FragmentNode[]): DiffGraphNode[] {
  if (chain.length === 0) return []
  const chainIds = new Set(chain.map((n) => n.id))
  const descendantIds = new Set<string>()
  for (const node of chain) {
    walkDescendants(node, descendantIds)
  }
  return allNodes
    .filter((n) => !chainIds.has(n.id) && !descendantIds.has(n.id))
    .map((node) => ({ node, isOnChain: false }))
}

function walkDescendants(node: FragmentNode, acc: Set<string>): void {
  for (const child of node.children) {
    if (acc.has(child.id)) continue
    acc.add(child.id)
    walkDescendants(child, acc)
  }
}

function isOpSubset(child: ChangeOp[], parent: ChangeOp[]): boolean {
  // Equality on ChangeOp is structural (kind+nodeType+path+old+new),
  // so we can use a Set for the parent and check each child op.
  const parentSet = new Set(parent.map((op) => opKey(op)))
  return child.every((op) => parentSet.has(opKey(op)))
}

function opKey(op: ChangeOp): string {
  return `${op.kind}|${op.nodeType}|${op.path}|${op.oldText ?? ''}|${op.newText ?? ''}`
}

interface FetchDiffResult {
  ops: ChangeOp[]
  degraded: boolean
  diffMethod: DiffMethod | ''
}

async function fetchDiff(
  backendUrl: string,
  before: string,
  after: string,
  dialect: SqlDialect,
  signal: AbortSignal
): Promise<FetchDiffResult> {
  const res = await apiFetch(`${backendUrl}/api/diff`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ before, after, dialect }),
    signal,
  })
  if (!res.ok) {
    const e = await res.json().catch(() => ({}))
    throw new Error(e?.message ?? `HTTP ${res.status}`)
  }
  const data = (await res.json()) as { ops?: ChangeOp[]; degraded?: boolean; diffMethod?: string }
  return {
    ops: data.ops ?? [],
    degraded: data.degraded ?? false,
    diffMethod: normalizeDiffMethod(data.diffMethod),
  }
}

/**
 * Spec §3.3 L2 / §5.2.3 — derive the diff-graph (chain + virtual edges to
 * dead-end siblings). For each ghost, computes:
 *   - Δ(currentNode, ghost)            — the "missed turn" diff
 *   - Δ(root, ghost)                   — the "containing" diff
 * and emits a virtual edge when the missed-turn diff is a subset of the
 * containing one (i.e. the dead-end is structurally explainable).
 */
export function useDiffGraph(
  chain: FragmentNode[],
  allNodes: FragmentNode[],
  backendUrl: string | null,
  dialect: SqlDialect
): State {
  const [state, setState] = useState<State>({
    ghosts: [],
    edges: [],
    loading: false,
    error: null,
    anyDegraded: false,
  })
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    const ghosts = computeGhosts(chain, allNodes)
    setState((s) => ({ ...s, ghosts, edges: [], error: null }))

    if (!backendUrl || chain.length < 1 || ghosts.length === 0) {
      setState((s) => ({ ...s, edges: [], loading: false, anyDegraded: false }))
      return
    }

    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    const root = chain[0]
    const current = chain[chain.length - 1]
    setState((s) => ({ ...s, loading: true }))

    ;(async () => {
      try {
        const results = await Promise.all(
          ghosts.map(async (g) => {
            const [dResult, rootResult] = await Promise.all([
              fetchDiff(backendUrl, current.sqlFragment, g.node.sqlFragment, dialect, controller.signal),
              fetchDiff(backendUrl, root.sqlFragment, g.node.sqlFragment, dialect, controller.signal),
            ])
            return {
              ghost: g,
              dOps: dResult.ops,
              degraded: dResult.degraded,
              diffMethod: dResult.diffMethod,
              isVirtual: isOpSubset(dResult.ops, rootResult.ops),
            }
          })
        )
        if (controller.signal.aborted) return
        const edges: VirtualEdge[] = results
          .filter((r) => r.dOps.length > 0)
          .map((r) => ({
            from: current,
            to: r.ghost.node,
            ops: r.dOps,
            isVirtual: r.isVirtual,
            degraded: r.degraded,
            diffMethod: r.diffMethod,
          }))
        const anyDegraded = edges.some((e) => e.degraded)
        setState({ ghosts, edges, loading: false, error: null, anyDegraded })
      } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') return
        setState({
          ghosts,
          edges: [],
          loading: false,
          error: e instanceof Error ? e.message : 'Diff graph failed',
          anyDegraded: false,
        })
      }
    })()

    return () => controller.abort()
  }, [chain, allNodes, backendUrl, dialect])

  return state
}
