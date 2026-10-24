import { useLocale, getLocale } from '../i18n'
// src/renderer/hooks/useAddBackExploration.ts
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { FragmentNode } from '../types/fragment'
import type { DAGState } from './dagState'
import { buildParentIndex, type ParentIndex, type ReverseIndexDegradedReason } from '../utils/dagReverseIndex'
import {
  buildAddBackProjection, createAddBackState,
  hideParent, isRevealed as isEdgeRevealed, revealParent,
  type AddBackState,
} from '../utils/addBackProjection'

export interface UseAddBackExplorationParams {
  dag: DAGState
  /** The top-down tree this exploration belongs to. Entering/leaving the
   *  exploration never changes it (spec §4.3.7). */
  tree: FragmentNode | null
  /**
   * Identity of the CURRENT tree INSTANCE (App: `activeSessionId` + the parse
   * epoch from `useFragmentTree`). The exploration is invalidated when this
   * changes — see the guard below for why a node id cannot be used.
   */
  treeInstanceKey: string | number
  selectNode: (id: string) => void
}

export interface AddBackExplorationApi {
  active: boolean
  seedNodeId: string | null
  tree: FragmentNode | null
  breadcrumb: string[]
  originId: string | null
  reachesOrigin: boolean
  /** True only when the display root is the DAG root — the 原 SQL label gate
   *  (spec §4.3.4 #2). */
  isOriginRoot: boolean
  skipped: string[]
  degraded: boolean
  reason: ReverseIndexDegradedReason | null
  index: ParentIndex
  enter: (seedNodeId: string) => void
  exit: () => void
  reveal: (childId: string, parentId: string) => void
  hide: (childId: string, parentId: string) => void
  toggle: (childId: string, parentId: string) => void
  /** Whether an edge is currently revealed — the panel renders checkboxes from it. */
  isRevealed: (childId: string, parentId: string) => boolean
}

/**
 * Bottom-up exploration as an explicit state, one hop at a time.
 *
 * This hook deliberately has no writer channel back into the parse result: the
 * projection is derived at render time and thrown away on exit. The previous
 * implementation wrote the projection into `result.tree`, which the session
 * lifecycle's LineageStore sync would then persist as the session's tree
 * (spec §4.3.7).
 */
export function useAddBackExploration({
  dag, tree, treeInstanceKey, selectNode,
}: UseAddBackExplorationParams): AddBackExplorationApi {
  useLocale()
  const [state, setState] = useState<AddBackState | null>(null)
  const enteredTreeKeyRef = useRef<string | number | null>(null)

  const index = useMemo(() => buildParentIndex(dag, tree), [dag, tree, getLocale()])

  const enter = useCallback((seedNodeId: string) => {
    enteredTreeKeyRef.current = treeInstanceKey
    setState(createAddBackState(seedNodeId))
    selectNode(seedNodeId)
  }, [selectNode, treeInstanceKey])

  const exit = useCallback(() => {
    enteredTreeKeyRef.current = null
    setState(null)
  }, [])

  const reveal = useCallback((childId: string, parentId: string) => {
    setState((s) => (s ? revealParent(s, childId, parentId) : s))
  }, [])

  const hide = useCallback((childId: string, parentId: string) => {
    setState((s) => (s ? hideParent(s, childId, parentId) : s))
  }, [])

  const toggle = useCallback((childId: string, parentId: string) => {
    setState((s) => {
      if (!s) return s
      return isEdgeRevealed(s, childId, parentId)
        ? hideParent(s, childId, parentId)
        : revealParent(s, childId, parentId)
    })
  }, [])

  const isRevealed = useCallback(
    (childId: string, parentId: string) => (state ? isEdgeRevealed(state, childId, parentId) : false),
    [state]
  )

  // A new tree INSTANCE (re-parse, or another tab's tree) invalidates the
  // exploration: its seed and revealed edges belong to the old tree.
  //
  // Keyed on `treeInstanceKey`, NOT on `tree?.id` — `FragmentNode.id` is
  // content-derived (`FragmentIdentity.computeId(canonicalSql, dialect)`), so
  // `tree.id` is IDENTICAL for two parses of the same SQL, in the same tab and
  // across tabs, while the DAG the seed/edges resolve against has been replaced
  // (a re-parse calls `resetDAGState` and re-indexes only the fresh root-only
  // tree). Live repro of the old key: tab 1 in add-back on a CTE child, tab 2
  // holding the same SQL parsed → the guard stayed silent, `dag.nodeMap` lost the
  // seed, and the panel rendered 「Paste a SQL query」 under a banner showing the
  // raw 64-hex seed id.
  //
  // This check is evaluated during RENDER (`stale`) and not only in the effect:
  // an effect runs after the render, so the doomed render would still project
  // against the replaced DAG and produce one `tree: null` frame (the blank
  // panel). With the render-time gate the very first render after the key change
  // already reports `active: false`, so the tree panel keeps showing the
  // top-down tree and never blanks.
  const stale = state !== null && enteredTreeKeyRef.current !== treeInstanceKey

  // The effect is the state-clearing half of the same guard (render must stay
  // pure): it drops the dead exploration so a later re-enter starts clean.
  useEffect(() => {
    if (!state) return
    if (enteredTreeKeyRef.current !== treeInstanceKey) {
      enteredTreeKeyRef.current = null
      setState(null)
    }
  }, [state, treeInstanceKey])

  const live = state !== null && !stale
  const projection = useMemo(
    () => (live && state ? buildAddBackProjection(state, dag, index) : null),
    [live, state, dag, index, getLocale()]
  )
  return {
    active: live,
    seedNodeId: state?.seedNodeId ?? null,
    tree: projection?.tree ?? null,
    breadcrumb: projection?.breadcrumb ?? [],
    originId: projection?.originId ?? null,
    reachesOrigin: projection?.reachesOrigin ?? false,
    isOriginRoot: projection?.isOriginRoot ?? false,
    skipped: projection?.skipped ?? [],
    degraded: projection?.degraded ?? false,
    reason: projection?.reason ?? null,
    index,
    enter,
    exit,
    reveal,
    hide,
    toggle,
    isRevealed,
  }
}
