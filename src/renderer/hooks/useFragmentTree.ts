import { sha256 } from '../utils/sha256'
import { apiFetch } from '../utils/api'
import { isWorkspaceExitLocked } from '../utils/workspaceExitGate'
import { useState, useCallback, useRef, useEffect, useLayoutEffect, useMemo } from 'react'
import type { SessionWorkspaceState } from '../types/session'
import { readSnapshot, legacyDAG } from '../utils/workspaceSnapshot'
import {
  FragmentNode,
  DecompositionBatch,
  DecompositionScan,
  DecompositionIssue,
  DecomposeOption,
  ParseResult,
  ParseRequest,
  DecomposeRequest,
  DecomposeOptionsRequest,
  isDegraded,
  getDegradedReason
} from '../types/fragment'
import {
  DAGState,
  createDAGState,
  appliedRuleKey,
  indexTreeIntoDAG,
  resetDAGState
} from './dagState'

interface FragmentTreeState {
  parsedSql: string
  parsedDialect: string
  result: ParseResult | null
  loading: boolean
  error: string | null
  warning: string | null
  selectedNodeId: string | null
  optionsCache: Record<string, DecomposeOption[]>
  optionsLoading: Record<string, boolean>
  decomposing: Record<string, boolean>
  /** Cross-projection DAG state. Persists across mode switches and
   *  re-decompositions; reset on session reset / re-parse. */
  dag: DAGState
  /**
   * Monotonic counter identifying the CURRENT tree INSTANCE — bumped whenever
   * `result.tree` + `dag` are replaced wholesale (a successful `parse`, or
   * `reset`).
   *
   * Why it exists: `FragmentNode.id` is CONTENT-derived
   * (`FragmentIdentity.computeId(canonicalSql, dialect)`), so `tree.id` is equal
   * for two parses of the same SQL — in the same tab (re-parse) and across tabs.
   * Anything that must react to "the tree was replaced" (not "the SQL changed")
   * has to key on this counter, not on an id. Consumers: App builds a
   * `treeInstanceKey` from `activeSessionId` + this value and hands it to
   * `useAddBackExploration` (rule #1 sweep: content-derived keying).
   *
   * Deliberately NOT bumped by `decomposeNode` (it extends the current tree; the
   * existing contract is that an in-flight exploration is not invalidated by a
   * decomposition) nor by `setResult` (session restore / execution-summary
   * writes are not new trees).
   */
  treeEpoch: number
}

/**
 * Immutably apply a rule result to a node in the tree.
 * Only clones nodes on the path from root to target.
 * Replaces existing children that came from the same ruleName.
 */
function applyRuleToNode(
  tree: FragmentNode,
  nodeId: string,
  newChildren: FragmentNode[],
  ruleName: string
): FragmentNode {
  if (tree.id === nodeId) {
    const otherChildren = tree.children.filter((c) => c.ruleName !== ruleName)
    const merged = [...otherChildren, ...newChildren]
    return { ...tree, children: merged, nodeCount: merged.length }
  }

  // Only recurse — don't clone if nothing changed below
  let changed = false
  const nextChildren = tree.children.map((c) => {
    const result = applyRuleToNode(c, nodeId, newChildren, ruleName)
    if (result !== c) changed = true
    return result
  })

  if (!changed) return tree
  return { ...tree, children: nextChildren }
}

/**
 * Immutably set the children of a node (used when a node already has children).
 */
function setChildrenForNode(
  tree: FragmentNode,
  nodeId: string,
  children: FragmentNode[]
): FragmentNode {
  if (tree.id === nodeId) {
    return { ...tree, children, nodeCount: children.length }
  }

  let changed = false
  const nextChildren = tree.children.map((c) => {
    const result = setChildrenForNode(c, nodeId, children)
    if (result !== c) changed = true
    return result
  })

  if (!changed) return tree
  return { ...tree, children: nextChildren }
}

/**
 * Result of POST /api/fragment/create-user-edit, made observable (rule #2).
 * EDIT means "another SQL was produced" — the caller opens it somewhere else.
 * NAVIGATE means "that text already existed here" — an access to another facet,
 * no new surface.
 *
 * NO_CHANGE is the NAVIGATE degeneracy where the "existing" facet IS the one
 * being edited (`data.node.id === parentId`): there is no other facet to
 * navigate to and nothing was produced. Backend-wise this is the pre-existing
 * canonicalizer behaviour — `SqlCanonicalizer`/`AstSerializer` fall back to
 * `UNKNOWN@root:<node type>` for shapes they cannot serialise, so the whole-SQL
 * root facet of the CTE sample hashes to the SAME id even when the text visibly
 * changed. The backend is deliberately untouched by this branch (spec §4.2
 * keeps ADR-0002 unchanged); what was missing is the frontend signal: the caller
 * used to treat it as a silent success. Split out here so the caller can render
 * a stated notice instead (rule #2). Live repro: editing the root facet of the
 * CTE sample → response `{"classification":"NAVIGATE","navigatedToExisting":true}`
 * whose `node.id` equals the request's `parentFragmentId` (and its own
 * `editParentId`).
 *
 * UNSUPPORTED is the backend refusing to give the edit an identity at all
 * (spec 2026-10-01 §4.2 / D3): canonicalization degraded (an un-dispatched node
 * type, or a parse failure), so the request carries `classification:
 * "UNSUPPORTED"`, an absent `node` (a null `node` is stripped by the backend's
 * `non_null` serialization), and a non-null `errorReason`. It is the
 * replacement for the old silent NAVIGATE-into-itself: the user must be told
 * the shape is not supported, not shown a successful save that produced nothing.
 */
export type UserEditOutcome =
  | {
      status: 'EDIT'
      nodeId: string
      sql: string
      /** Spec §5.3 — present only when the SOURCE facet's identity is
       *  degraded. `string` = the reason (already truncated to 120); `null` =
       *  degraded with no reason text (caller states the degradation without a
       *  reason tail). Absent key = clean source — no notice, behaviour as
       *  before. Never `undefined` as a value: clean sources omit the key. */
      sourceDegradedReason?: string | null
    }
  | { status: 'NAVIGATE'; nodeId: string }
  | { status: 'NO_CHANGE'; nodeId: string }
  | { status: 'UNSUPPORTED'; reason: string }
  | { status: 'FAILED'; reason: string }

function issueSummary(issues: DecompositionIssue[] = []): string | null {
  return [...new Set(issues.map(i => `${i.code}: ${i.message}`))].join('; ') || null
}

function emptyTreeState(treeEpoch = 0): FragmentTreeState {
  return {
    parsedSql: '',
    parsedDialect: 'postgresql',
    result: null,
    loading: false,
    error: null,
    warning: null,
    selectedNodeId: null,
    optionsCache: {},
    optionsLoading: {},
    decomposing: {},
    dag: createDAGState(),
    treeEpoch,
  }
}

function restoreTreeState(workspace: SessionWorkspaceState | null | undefined, treeEpoch: number): FragmentTreeState {
  const next = emptyTreeState(treeEpoch)
  if (workspace?.workspaceSnapshotJson) {
    try {
      const { snapshot, dag, selectionReason } = readSnapshot(workspace.workspaceSnapshotJson)
      next.dag = dag
      next.parsedSql = snapshot.parsedSql
      next.parsedDialect = snapshot.dialect
      next.result = snapshot.treeProjection ? { tree: snapshot.treeProjection, options: [], errors: [] } : null
      next.selectedNodeId = snapshot.selectedNodeId
      next.error = selectionReason
    } catch (e) { next.error = `Could not restore workspace: ${e instanceof Error ? e.message : String(e)}` }
    return next
  }
  if (!workspace?.fragmentTreeJson) return next
  try {
    const tree = JSON.parse(workspace.fragmentTreeJson) as FragmentNode
    if (!tree?.id || !Array.isArray(tree.children)) throw new Error('Invalid saved fragment tree')
    next.dag = legacyDAG(tree)
    next.parsedSql = workspace.sql
    next.parsedDialect = workspace.dialect
    next.result = { tree, options: [], errors: [] }
    next.selectedNodeId = workspace.selectedNodeId && next.dag.nodeMap.has(workspace.selectedNodeId)
      ? workspace.selectedNodeId : tree.id
    for (const node of next.dag.nodeMap.values()) {
      for (const child of node.children) {
        if (child.ruleName) next.dag.appliedRules.add(appliedRuleKey(node.id, child.ruleName))
      }
    }
  } catch (e) {
    next.error = `Could not restore saved tree: ${e instanceof Error ? e.message : String(e)}`
  }
  return next
}

export function useFragmentTree(backendUrl: string | null, workspace?: SessionWorkspaceState | null) {
  const [state, setState] = useState<FragmentTreeState>(() => restoreTreeState(workspace, 0))
  const workspaceKey = workspace?.sessionId ?? null
  const loadEpoch = workspace?.loadEpoch ?? 0
  const connectionId = workspace?.connectionId ?? null
  const connectionRef = useRef(connectionId)
  connectionRef.current = connectionId
  // Ownership must commit with the tree. StrictMode can discard render-phase
  // state updates, but mutations to refs survive the discarded render.
  const [ownership, setOwnership] = useState(() => ({
    key: workspaceKey, loadEpoch, snapshots: new Map<string, FragmentTreeState>(),
  }))
  const requestEpochRef = useRef(0)

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  // Switch before children render or persistence effects run: old facets must
  // never appear under a new SQL buffer. Runtime snapshots preserve the full
  // DAG; persisted trees are the boot source when no runtime snapshot exists.
  if (ownership.key !== workspaceKey || ownership.loadEpoch !== loadEpoch) {
    const reloaded = ownership.key === workspaceKey
    const snapshots = new Map(ownership.snapshots)
    if (ownership.key) snapshots.set(ownership.key, {
      ...state, loading: false, optionsLoading: {}, decomposing: {},
    })
    const saved = workspaceKey && !reloaded ? snapshots.get(workspaceKey) : undefined
    setOwnership({ key: workspaceKey, loadEpoch, snapshots })
    setState(saved ? { ...saved, treeEpoch: state.treeEpoch + 1 }
      : restoreTreeState(workspace, state.treeEpoch + 1))
  }
  // Invalidate requests before passive parsing/persistence effects, without
  // mutating request ownership during a render React may discard.
  useLayoutEffect(() => {
    requestEpochRef.current++
    abortRef.current?.abort()
    if (debounceRef.current) clearTimeout(debounceRef.current)
    // Restored tab snapshots can carry scans from a different connection too.
    setState(s => ({ ...s, optionsCache: {}, optionsLoading: {}, decomposing: {}, loading: false, warning: null }))
  }, [workspaceKey, loadEpoch, connectionId])
  const stateRef = useRef(state)
  stateRef.current = state
  const optionsCacheRef = useRef(state.optionsCache)
  optionsCacheRef.current = state.optionsCache

  // Keep refs in sync with state
  useEffect(() => {
    stateRef.current = state
    optionsCacheRef.current = state.optionsCache
  }, [state])

  const findNodeById = useCallback(
    (tree: FragmentNode, id: string): FragmentNode | null => {
      if (tree.id === id) return tree
      for (const child of tree.children) {
        const found = findNodeById(child, id)
        if (found) return found
      }
      return null
    },
    []
  )

  // The selection can point at a facet the current tree does not contain: within
  // one parse, the DAG keeps every node it has ever indexed (`decomposeNode`
  // clones `nodeMap` before adding the new children, so the entries survive a
  // later `setResult` that drops them from the projection) — a re-parse resets
  // the whole DAG (see `resetDAGState`). Fall back to the DAG node map,
  // otherwise the right panel blanks out on a legitimately selected facet.
  // (Was cited as "spec §7.1"; no spec section states this invariant — the
  // citation is dropped rather than requalified, per the M-4 sweep.)
  const selectedNode = useMemo(() => {
    if (!state.selectedNodeId) return null
    const fromTree = state.result?.tree
      ? findNodeById(state.result.tree, state.selectedNodeId)
      : null
    return fromTree ?? state.dag.nodeMap.get(state.selectedNodeId) ?? null
  }, [state.result?.tree, state.selectedNodeId, state.dag, findNodeById])

  const parse = useCallback(
    async (sql: string, dialect = 'postgresql', internal = false) => {
      if (isWorkspaceExitLocked() && !internal) return
      if (!backendUrl) {
        setState((s) => ({ ...s, error: 'Backend not connected', loading: false }))
        return
      }

      // Cancel any in-flight request
      const requestEpoch = ++requestEpochRef.current
      abortRef.current?.abort()
      const controller = new AbortController()
      abortRef.current = controller

      setState((s) => ({ ...s, loading: true, error: null, warning: null }))

      try {
        const request: ParseRequest = { sql, dialect, connectionId: connectionRef.current ?? undefined }
        const res = await apiFetch(`${backendUrl}/api/sql/parse`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(request),
          signal: controller.signal,
        })

        const data: ParseResult = await res.json()
        if (controller.signal.aborted || requestEpoch !== requestEpochRef.current) return

        const rootId = data.tree?.id
        const optionsCache: Record<string, DecomposeOption[]> = {}
        if (rootId && data.options.length > 0) {
          optionsCache[rootId] = data.options
        }

        // Re-parse: reset the DAG to a fresh state and index the new tree
        // into it. Spec §3.3.2: re-parsing defines a new DAG; previous
        // exploration history does not carry over.
        const newDag = resetDAGState()
        indexTreeIntoDAG(newDag, data.tree)

        setState((s) => ({
          ...s,
          result: data,
          parsedSql: sql,
          parsedDialect: dialect,
          loading: false,
          error: data.errors[0]?.message ?? (data.scan?.status === 'FAILED' ? issueSummary(data.scan.issues) : null),
          warning: data.scan?.status !== 'FAILED' ? issueSummary(data.scan?.issues) : null,
          selectedNodeId: rootId ?? null,
          optionsCache,
          decomposing: {},
          dag: newDag,
          // New tree INSTANCE — see FragmentTreeState.treeEpoch. Bumped even when
          // the new root id equals the old one (identical SQL re-parsed).
          treeEpoch: s.treeEpoch + 1,
        }))
      } catch (e) {
        if (requestEpoch !== requestEpochRef.current) return
        if (e instanceof DOMException && e.name === 'AbortError') return
        setState((s) => ({
          ...s,
          loading: false,
          error: e instanceof Error ? e.message : 'Failed to parse SQL'
        }))
      }
    },
    [backendUrl]
  )

  const fetchOptions = useCallback(
    async (nodeId: string, sqlFragment: string) => {
      if (!backendUrl) return
      // Don't re-fetch if already cached (even empty array means we checked)
      if (nodeId in optionsCacheRef.current) return
      const requestEpoch = requestEpochRef.current

      setState((s) => ({
        ...s,
        optionsLoading: { ...s.optionsLoading, [nodeId]: true }
      }))

      try {
        const request: DecomposeOptionsRequest = { sqlFragment, dialect: stateRef.current.parsedDialect, connectionId: connectionRef.current ?? undefined }
        const res = await apiFetch(`${backendUrl}/api/sql/decompose-options`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(request)
        })

        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const envelope: { options: DecomposeOption[]; scan: DecompositionScan } = await res.json()
        const options = envelope.options
        if (!Array.isArray(options) || !envelope.scan || !Array.isArray(envelope.scan.issues)) throw new Error('Invalid decomposition options response')
        if (requestEpoch !== requestEpochRef.current) return

        setState((s) => {
          // Filter out rules that have already produced children for this
          // node — the right-click menu should never offer a rule whose
          // output is already in the tree (spec 2026-06-17 §3.3.2 "右键
          // 菜单读 DAG 层").
          const applied = s.dag.appliedRules
          const visible = options.filter(
            (o) => !applied.has(appliedRuleKey(nodeId, o.ruleName))
          )
          return {
            ...s,
            optionsCache: { ...s.optionsCache, [nodeId]: visible },
            optionsLoading: { ...s.optionsLoading, [nodeId]: false },
            error: envelope.scan.status === 'FAILED' ? issueSummary(envelope.scan.issues) : s.error,
            warning: envelope.scan.status !== 'FAILED' ? issueSummary(envelope.scan.issues) : null,
          }
        })
      } catch (e) {
        if (requestEpoch !== requestEpochRef.current) return
        setState((s) => ({
          ...s,
          optionsLoading: { ...s.optionsLoading, [nodeId]: false },
          error: `Could not load decomposition options: ${e instanceof Error ? e.message : String(e)}`
        }))
      }
    },
    [backendUrl]
  )

  const decomposeNode = useCallback(
    async (nodeId: string, ruleName: string) => {
      if (isWorkspaceExitLocked()) return
      if (!backendUrl || !stateRef.current.result?.tree) {
        return
      }

      const node = stateRef.current.dag.nodeMap.get(nodeId) ?? findNodeById(stateRef.current.result.tree, nodeId)

      if (!node) {
        return
      }
      const requestEpoch = requestEpochRef.current

      setState((s) => ({
        ...s,
        error: null,
        warning: null,
        decomposing: { ...s.decomposing, [nodeId]: true }
      }))

      try {
        const request: DecomposeRequest = {
          sqlFragment: node.sqlFragment,
          dialect: stateRef.current.parsedDialect,
          ruleName,
          sourceNodeId: nodeId,
          connectionId: connectionRef.current ?? undefined
        }

        const res = await apiFetch(`${backendUrl}/api/sql/decompose`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(request)
        })

        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const batch: DecompositionBatch = await res.json()
        if (!['COMPLETE', 'PARTIAL', 'NOT_APPLICABLE', 'FAILED'].includes(batch.status) || !Array.isArray(batch.children) || !Array.isArray(batch.outputs) || !Array.isArray(batch.issues)) throw new Error('Invalid decomposition result')
        const children = batch.children
        const nodes = new Map(stateRef.current.dag.nodeMap)
        const collect = (n: FragmentNode, ancestors: Set<string>) => {
          if (!n || typeof n.id !== 'string' || typeof n.sqlFragment !== 'string' || !Array.isArray(n.children) || ancestors.has(n.id)
            || nodes.has(n.id) && nodes.get(n.id)!.sqlFragment !== n.sqlFragment) throw new Error('Invalid decomposition node or cycle')
          nodes.set(n.id, n)
          const path = new Set(ancestors).add(n.id)
          n.children.forEach(c => collect(c, path))
        }
        children.forEach(c => collect(c, new Set([nodeId])))
        const positions = new Set<string>()
        for (const o of batch.outputs) {
          const source = nodes.get(o.sourceNodeId), target = nodes.get(o.targetNodeId)
          const key = `${o.sourceNodeId}::${o.ruleName}::${o.ordinal}`
          if (!source || !target || !Number.isInteger(o.ordinal) || o.ordinal < 1 || positions.has(key) || !o.effect
            || o.effect.sourceSqlHash !== sha256(source.sqlFragment) || o.effect.targetSqlHash !== sha256(target.sqlFragment)
            || !['PRESERVED', 'DETACHED', 'UNKNOWN'].includes(o.effect.context) || !Array.isArray(o.effect.changes) || !Array.isArray(o.effect.limitations)) throw new Error('Invalid decomposition evidence')
          positions.add(key)
        }
        if (children.some(c => !batch.outputs.some(o => o.sourceNodeId === nodeId && o.ruleName === ruleName && o.targetNodeId === c.id))) throw new Error('Missing decomposition evidence')
        const complete = batch.status === 'COMPLETE' || batch.status === 'NOT_APPLICABLE'
        if (requestEpoch !== requestEpochRef.current) return
        if (batch.status === 'FAILED') {
          setState(s => {
            const key = appliedRuleKey(nodeId, ruleName)
            const ruleResults = new Map(s.dag.ruleResults)
            const previous = ruleResults.get(key)
            // Retain previously observed edges on a failed retry; no new graph
            // output is committed. A first failure is itself cold-restorable.
            ruleResults.set(key, { sourceNodeId: nodeId, ruleName,
              childIds: previous?.childIds ?? [], outputs: previous?.outputs ?? [],
              sourceSqlHash: sha256(node.sqlFragment), status: previous?.childIds.length ? 'PARTIAL' : 'FAILED', issues: batch.issues })
            const appliedRules = new Set(s.dag.appliedRules); appliedRules.delete(key)
            return { ...s, dag: { ...s.dag, ruleResults, appliedRules },
              decomposing: { ...s.decomposing, [nodeId]: false },
              error: batch.issues.map(i => `${i.code}: ${i.message}`).join('; ') || 'Rule failed' }
          })
          return
        }

        setState((s) => {
          if (!s.result?.tree) return s

          let newTree = applyRuleToNode(s.result.tree, nodeId, children, ruleName)

          // Remove applied rule from this node's options
          const newOptionsCache = { ...s.optionsCache }
          const remaining = (newOptionsCache[nodeId] ?? [])
            .filter((o) => o.ruleName !== ruleName)
          if (complete) newOptionsCache[nodeId] = remaining

          // Update DAG: record the applied rule, cache the children, and
          // index the freshly created child nodes into the global node map.
          // The maps are cloned (shallow) so the immutability contract holds.
          const newDag: DAGState = {
            nodeMap: new Map(s.dag.nodeMap),
            appliedRules: new Set(s.dag.appliedRules),
            decomposedChildren: new Map(s.dag.decomposedChildren),
            ruleResults: new Map(s.dag.ruleResults),
            completeness: s.dag.completeness,
          }
          const recordKey = appliedRuleKey(nodeId, ruleName)
          if (complete) newDag.appliedRules.add(recordKey)
          else newDag.appliedRules.delete(recordKey)
          newDag.ruleResults.set(recordKey, { sourceNodeId: nodeId, ruleName, childIds: [...new Set(children.map(c => c.id))], status: batch.status,
            sourceSqlHash: sha256(node.sqlFragment), outputs: batch.outputs.filter(o => o.sourceNodeId === nodeId && o.ruleName === ruleName), issues: batch.issues })
          for (const output of batch.outputs.filter(o => o.sourceNodeId !== nodeId || o.ruleName !== ruleName)) {
            const key = appliedRuleKey(output.sourceNodeId, output.ruleName)
            const existing = newDag.ruleResults.get(key)
            const outputs = [...(existing?.outputs ?? []).filter(o => o.ordinal !== output.ordinal), output]
            newDag.ruleResults.set(key, { sourceNodeId: output.sourceNodeId, ruleName: output.ruleName, childIds: [...new Set(outputs.map(o => o.targetNodeId))],
              status: output.effect.context === 'UNKNOWN' ? 'PARTIAL' : 'COMPLETE', sourceSqlHash: output.effect.sourceSqlHash, outputs, issues: output.effect.limitations })
          }
          // Index each child into the global node map. Re-index the new
          // tree from the root so the map reflects the current projection
          // (overrides stale entries for nodes that have been re-decomposed).
          indexTreeIntoDAG(newDag, newTree)
          newDag.decomposedChildren.set(nodeId, [...new Map([...newDag.ruleResults.values()]
            .filter(r => r.sourceNodeId === nodeId).flatMap(r => r.childIds.map(id => newDag.nodeMap.get(id)!))
            .map(c => [c.id, c])).values()])
          newTree = setChildrenForNode(newTree, nodeId, newDag.decomposedChildren.get(nodeId) ?? [])
          indexTreeIntoDAG(newDag, newTree)

          // Auto-select the first child so the right panel refreshes
          const nextSelectedId = children.length > 0 ? children[0].id : nodeId

          return {
            ...s,
            result: { ...s.result, tree: newTree },
            selectedNodeId: nextSelectedId,
            decomposing: { ...s.decomposing, [nodeId]: false },
            optionsCache: newOptionsCache,
            dag: newDag,
            error: null,
            warning: issueSummary(batch.issues),
          }
        })
      } catch (e) {
        if (requestEpoch !== requestEpochRef.current) return
        setState((s) => ({
          ...s,
          decomposing: { ...s.decomposing, [nodeId]: false },
          error: `Could not decompose SQL: ${e instanceof Error ? e.message : String(e)}`
        }))
      }
    },
    [backendUrl, findNodeById]
  )

  const selectNode = useCallback(
    (nodeId: string | null) => {
      if (isWorkspaceExitLocked()) return
      // Side effect: fetch options if node not yet cached — outside setState
      if (nodeId && stateRef.current.result?.tree) {
        const node = stateRef.current.dag.nodeMap.get(nodeId) ?? findNodeById(stateRef.current.result.tree, nodeId)
        if (node && !(nodeId in optionsCacheRef.current)) {
          fetchOptions(nodeId, node.sqlFragment)
        }
      }
      setState((s) => ({ ...s, selectedNodeId: nodeId }))
    },
    [findNodeById, fetchOptions]
  )

  const reset = useCallback(() => {
    if (isWorkspaceExitLocked()) return
    requestEpochRef.current++
    if (debounceRef.current) clearTimeout(debounceRef.current)
    abortRef.current?.abort()
    abortRef.current = null
    setState((s) => ({
      parsedSql: '',
      parsedDialect: 'postgresql',
      result: null,
      loading: false,
      error: null,
      warning: null,
      selectedNodeId: null,
      optionsCache: {},
      optionsLoading: {},
      decomposing: {},
      dag: createDAGState(),
      // The DAG is dropped here too (see the parser's `resetDAGState`), so this
      // is a new tree instance as well. Kept monotonic (never reset to 0) so a
      // key built from it can never collide with an earlier instance.
      treeEpoch: s.treeEpoch + 1,
    }))
  }, [])

  const clearResult = reset // Alias for backward compat

  const submitUserEdit = useCallback(
    async (
      parentId: string,
      newSql: string,
      description: string,
      dialect: string
    ): Promise<UserEditOutcome> => {
      if (isWorkspaceExitLocked()) return { status: 'FAILED', reason: 'Application exit is in progress.' }
      const tree = stateRef.current.result?.tree
      if (!tree || !backendUrl) {
        return { status: 'FAILED', reason: 'Backend not connected or no parsed SQL' }
      }

      // Collect all node IDs from the current DAG for the NAVIGATE check
      const dagContextIds = Array.from(stateRef.current.dag.nodeMap.keys())
      const requestEpoch = requestEpochRef.current

      try {
        const res = await apiFetch(`${backendUrl}/api/fragment/create-user-edit`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            sql: newSql,
            parentFragmentId: parentId,
            dagContextIds,
            dialect,
            description,
          }),
        })

        if (!res.ok) {
          // Rule #2: this branch used to console.warn and return nothing.
          return { status: 'FAILED', reason: `HTTP ${res.status}` }
        }

        const data = await res.json() as {
          // `spring.jackson.default-property-inclusion: non_null` (application.yml)
          // REMOVES a key whose value is null, so on the wire these are absent,
          // not `null`. Hence optional, and read with a key-absence-tolerant guard.
          node?: (FragmentNode & { id: string }) | null
          classification: 'EDIT' | 'NAVIGATE' | 'UNSUPPORTED'
          navigatedToExisting: boolean
          errorReason?: string | null
        }
        if (requestEpoch !== requestEpochRef.current) {
          return { status: 'FAILED', reason: 'The active SQL changed while the edit was being saved. Return to the source tab and try again.' }
        }

        if (data.classification === 'UNSUPPORTED' || !data.node) {
          // Backend refused an identity (spec §4.2): surface the reason it gave,
          // never invent a node id. Rule #2 — the degradation must be observable.
          // `classification` is the stable discriminator: every degradation path
          // sets it, while `errorReason` has three different prefixes (blank SQL,
          // parse failure, unsupported node types) and must never be matched on.
          return {
            status: 'UNSUPPORTED',
            reason: data.errorReason || 'This SQL shape cannot be canonicalized, so it has no stable identity',
          }
        }

        // The guard above proved `node` is present for every remaining branch.
        // Bound to a local because TS does not carry property narrowing into the
        // `setState` callback below.
        const node = data.node

        if (data.classification === 'NAVIGATE') {
          // The edited text resolves to the facet being edited: nothing to
          // navigate TO, nothing produced. Selecting it would be a no-op (the
          // caller is already there), so this must not be reported as a
          // successful navigation — see UserEditOutcome's NO_CHANGE note.
          if (node.id === parentId) {
            return { status: 'NO_CHANGE', nodeId: node.id }
          }
          // ADR-0002: the edited text already exists in the DAG — this is an
          // ACCESS to an existing facet, so navigate to it and open nothing.
          setState((s) => ({ ...s, selectedNodeId: node.id }))
          return { status: 'NAVIGATE', nodeId: node.id }
        }

        // EDIT: the result is ANOTHER SQL. It is deliberately NOT inserted into
        // this tree (spec §4.1/§4.2) and deliberately not indexed into this
        // tab's DAG — it belongs to the tab the caller is about to open.
        //
        // Spec §5.3 — degrade-aware outcome: if the SOURCE facet carries a
        // degraded identity (its id came from raw text), an equivalent rewrite
        // cannot match it and this EDIT is the conservative fallback. Carry the
        // reason so the caller can say so; absent key = clean source.
        //
        // Precedence note (load-bearing): `a ? b : null ?? c` parses as
        // `a ? b : (null ?? c)` and would drop the tree lookup entirely, so the
        // conditional is parenthesized before the `??`.
        const sourceNode =
          (tree ? findNodeById(tree, parentId) : null) ??
          stateRef.current.dag.nodeMap.get(parentId)
        const sourceDegraded = sourceNode ? isDegraded(sourceNode) : false
        return {
          status: 'EDIT',
          nodeId: node.id,
          sql: newSql,
          ...(sourceDegraded
            ? { sourceDegradedReason: sourceNode ? getDegradedReason(sourceNode) : null }
            : {}),
        }
      } catch (e) {
        // Rule #2: was `console.warn` with no signal to the caller.
        return { status: 'FAILED', reason: e instanceof Error ? e.message : String(e) }
      }
    },
    [backendUrl]
  )

  /**
   * Replace the current parse result with a new tree. Used to restore a
   * persisted tree when the active tab switches (useSessionLifecycle).
   */
  const setResult = useCallback(
    (next: ParseResult | null | ((prev: ParseResult | null) => ParseResult | null)) => {
      setState((s) => {
        const resolved = typeof next === 'function'
          ? (next as (p: ParseResult | null) => ParseResult | null)(s.result)
          : next
        const dag = { ...s.dag, nodeMap: new Map(s.dag.nodeMap) }
        indexTreeIntoDAG(dag, resolved?.tree ?? null)
        return { ...s, result: resolved, dag }
      })
    },
    []
  )

  const parseDebounced = useCallback(
    (sql: string, dialect?: string) => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current)
      }
      debounceRef.current = setTimeout(() => {
        parse(sql, dialect)
      }, 300)
    },
    [parse]
  )

  return {
    result: state.result,
    loading: state.loading,
    error: state.error,
    warning: state.warning,
    selectedNodeId: state.selectedNodeId,
    selectedNode,
    optionsCache: state.optionsCache,
    optionsLoading: state.optionsLoading,
    decomposing: state.decomposing,
    dag: state.dag,
    treeEpoch: state.treeEpoch,
    parsedSql: state.parsedSql,
    parsedDialect: state.parsedDialect,
    parse,
    parseDebounced,
    selectNode,
    reset,
    clearResult,
    decomposeNode,
    submitUserEdit,
    setResult
  }
}
