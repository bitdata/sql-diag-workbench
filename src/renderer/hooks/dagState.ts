import { FragmentNode, RuleResult } from '../types/fragment'

// ─────────────────────────────────────────────────────────────────────────
// Behavior matrix (Phase 1.5 verification)
//
// The DAG layer is the single source of truth across projection switches.
// Items 1–4, 8 and 10 are checked manually via the App's interactive UI;
// item 5 is a pure read-only view and is covered by useAddBackExploration's
// own tests instead.
//
// 1. parse(SQL)
//    → nodeMap ← { root: { id, type=ORIGINAL, parentIds=[] } }
//    → appliedRules = ∅
//    → decomposedChildren = ∅
//
// 2. right-click root → "Split JOIN" → decomposeNode(rootId, 'join-split')
//    → nodeMap += { split_A, split_B, split_C }   (children of root)
//    → appliedRules += { "root::join-split" }
//    → decomposedChildren[rootId] = [split_A, split_B, split_C]
//
// 3. right-click root again
//    → fetchOptions returns the cached options minus 'join-split'
//      (filtered by appliedRules), so the menu no longer offers it.
//
// 4. right-click root → "Split JOIN" again (idempotency)
//    → backend is hit again (no idempotent cache in useFragmentTree yet),
//      but the new children replace the rule's previous children in the
//      tree (applyRuleToNode). DAG re-indexes them — no orphaned entries.
//
// 5. add-back exploration (useAddBackExploration) is a READ-ONLY view: it
//    derives its projection from this DAG at render time and never writes
//    back into it, nor into result.tree (spec §4.3.7).
//
// 8. submitUserEdit(parentId, sql, desc)
//    → POST /api/fragment/create-user-edit
//    → NAVIGATE: the text already exists in this DAG → selectedNodeId = that
//      node; nodeMap unchanged (an access, not a new node).
//    → EDIT: returns { status:'EDIT', ... } — the result is ANOTHER SQL and is
//      deliberately NOT indexed into this DAG (spec §4.1/§4.2); the caller
//      opens it in a new tab.
//    → FAILED: non-ok / thrown error returned to the caller (rule #2).
//
// 10. reset() / clearResult()
//     → entire DAG is dropped (createDAGState). Re-parse repopulates.
// ─────────────────────────────────────────────────────────────────────────


/**
 * DAGState — the persistent state of the SQL decomposition DAG behind the
 * current tree view. Distinct from the tree currently on screen. Per spec
 * 2026-06-17 §3.3.2 the same fragment can appear in more than one place, so
 * the underlying data must not be coupled to the current tree shape.
 *
 * <p>All maps grow monotonically — once a fragment or rule is recorded, it
 * stays recorded for the lifetime of the session. A re-parse or a session
 * reset clears the state explicitly (createDAGState).
 */
export interface DAGState {
  /** All known fragments by stable id. Edited SQL gets its own workspace;
   *  cross-Tab edit provenance is stored separately from this graph. */
  nodeMap: Map<string, FragmentNode>

  /** Set of "nodeId::ruleName" keys for every decomposition that has
   *  been applied. Used by the optionsCache filter so the right-click
   *  menu never offers a rule that has already completed, even with no children. */
  appliedRules: Set<string>

  /** Aggregate children per source, derived from every ruleResults record.
   * Consumers such as the reverse index must see all rules, not only the last one. */
  decomposedChildren: Map<string, FragmentNode[]>
  /** Per-source/per-rule records, including successful empty results. */
  ruleResults: Map<string, RuleResult>
  completeness: 'recorded' | 'legacy-tree'
}

export function createDAGState(): DAGState {
  return {
    nodeMap: new Map(),
    appliedRules: new Set(),
    decomposedChildren: new Map(),
    ruleResults: new Map(),
    completeness: 'recorded',
  }
}

/** Stable key for an applied rule record. */
export function appliedRuleKey(nodeId: string, ruleName: string): string {
  return `${nodeId}::${ruleName}`
}

/**
 * Index every node in {@code tree} into the DAG node map. Called when a
 * new tree is parsed and when children are appended after decomposition.
 * Mutates {@code state} in place (the caller passes a freshly cloned map).
 */
export function indexTreeIntoDAG(
  state: DAGState,
  tree: FragmentNode | null
): void {
  if (!tree) return
  state.nodeMap.set(tree.id, tree)
  for (const child of tree.children) {
    indexTreeIntoDAG(state, child)
  }
}

/** Reset all state — called on session reset / re-parse. */
export function resetDAGState(): DAGState {
  return createDAGState()
}
