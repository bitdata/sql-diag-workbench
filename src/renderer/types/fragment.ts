import { tr } from '../i18n'
export type FragmentType =
  | 'ORIGINAL'
  | 'STRIPPED'
  | 'SET_SPLIT'
  | 'PREDICATE_DECOMPOSED'
  | 'COLUMN_PRUNED'
  | 'JOIN_SPLIT'
  | 'SUBQUERY_EXTRACTED'
  | 'CTE_EXPANDED'
  | 'USER_EDITED'

export interface FragmentNode {
  id: string
  type: FragmentType
  label: string
  sqlFragment: string
  executable: boolean
  executionHint: string | null
  nodeCount: number
  ruleName: string | null
  children: FragmentNode[]
  /** Parent node IDs this fragment was derived from. Empty for ORIGINAL. */
  parentIds?: string[]
  /* spec §4.2 — lineage panel data (populated on demand, null for legacy nodes) */
  /** ADR-0001 identity source — normalized SQL text. */
  canonicalSql?: string
  /** Edit-chain single link (ADR-0002/0003). Null for non-edit nodes. */
  editParentId?: string | null
  /** ISO 8601 UTC timestamp of node creation. */
  createdAt?: string
  /** Per-node execution metrics, null until first successful run. */
  executionSummary?: import('../hooks/useExecution').ExecutionSummaryLike | null
  /** ADR-0001 identity-degradation marker. Non-null means this node's id was
   *  derived from raw SQL — canonicalization failed (e.g. the bodyless `WITH`
   *  of a mutually-recursive CTE group) or the token stream contained
   *  unsupported node types — so the id must not be treated as an equivalence
   *  identity. Serialized only when non-null (`spring.jackson.default-property-inclusion:
   *  non_null`), so it is absent from the JSON when clean: the degraded-identity
   *  test is the marker's PRESENCE (`!= null`), never `=== null` — a blank-string
   *  marker is still degraded, and truthiness decides only whether there is
   *  reason *text* to show. Read it through `isDegraded` / `getDegradedReason`
   *  below, not with raw checks. */
  canonicalizationError?: string | null
  /** Transform descriptor for lineage display. Populated by client-side
   *  wrapping (e.g. App.tsx LineageNode) or future backend support. */
  transform?: { kind: string; description: string }
}

export interface DecomposeOption {
  ruleName: string
  displayName: string
  description: string
  level: 'STRUCTURE' | 'CLAUSE' | 'PREDICATE'
}

export interface DecomposeOptionsGrouped {
  structure: DecomposeOption[]
  clause: DecomposeOption[]
  predicate: DecomposeOption[]
}

export interface ParseError {
  code: string
  message: string
  detail?: {
    position?: { line: number; column: number }
    token?: string
    suggestion?: string
  }
}

export interface ParseResult {
  scan?: DecompositionScan
  tree: FragmentNode | null
  options: DecomposeOption[]
  errors: ParseError[]
}

export interface ParseRequest {
  connectionId?: string | null
  sql: string
  dialect: string
}

export interface DecomposeRequest {
  connectionId?: string | null
  sqlFragment: string
  dialect: string
  ruleName: string
  /** Stable id of the source fragment in the DAG. Used to populate each
   *  child node's parentIds so lineage can be walked back to the source. */
  sourceNodeId?: string
}

export interface DecomposeOptionsRequest {
  connectionId?: string | null
  sqlFragment: string
  dialect: string
}

// SQL Node Position types for clickable node selection
export interface SqlPosition {
  line: number
  column: number
  offset: number
}

export type SqlNodePositionType =
  | 'TABLE_REF'
  | 'JOIN_CONDITION'
  | 'WHERE_CLAUSE'
  | 'COLUMN_REF'
  | 'SUBQUERY'
  | 'CTE_DEFINITION'
  | 'GROUP_BY'
  | 'ORDER_BY'
  | 'HAVING_CLAUSE'
  | 'SELECT_LIST'

export interface SqlNodePosition {
  nodeId: string
  type: SqlNodePositionType
  label: string
  start: SqlPosition
  end: SqlPosition
}

export interface SqlPositionsResponse {
  nodes: SqlNodePosition[]
}

export function getFragmentIcon(type: FragmentType): string {
  switch (type) {
    case 'ORIGINAL':
      return '◉'
    case 'STRIPPED':
      return '⊖'
    case 'SET_SPLIT':
      return '⧓'
    case 'PREDICATE_DECOMPOSED':
      return '⊡'
    case 'COLUMN_PRUNED':
      return '✂'
    case 'JOIN_SPLIT':
      return '⇄'
    case 'SUBQUERY_EXTRACTED':
      return '⇗'
    case 'CTE_EXPANDED':
      return '↔'
    case 'USER_EDITED':
      return '✎'
  }
}

export function getFragmentBadge(node: FragmentNode): string | null {
  if (node.ruleName === 'clause-stripping') return tr("Clause Removed")
  if (node.ruleName === 'set-op-split') return tr("Branch")
  if (node.ruleName === 'predicate-decomposition') return tr("Isolated")
  if (node.ruleName === 'cte-expansion') return 'CTE'
  if (node.ruleName === 'join-split') return tr("Join Table")
  if (node.ruleName === 'subquery-extraction') return tr("Subquery")
  if (node.type === 'ORIGINAL' && node.nodeCount > 0) return tr("{0} variants", node.nodeCount)
  return null
}

/** Spec §6 — single truncation rule for degraded-identity reason display. */
export function truncateDegradedReason(reason: string, maxLen = 120): string {
  return reason.length <= maxLen ? reason : reason.slice(0, maxLen) + '…'
}

/** ADR-0001 degraded-identity PRESENCE. This is deliberately `!= null`, not a
 *  truthiness check: legacy JSON omits the key entirely (`spring.jackson.
 *  default-property-inclusion: non_null`) and Jackson maps a missing key to
 *  `null`, so both of those mean "clean" — but a BLANK marker is still a
 *  degraded identity, and spec §6 requires the label to show without a reason
 *  tail (rule #2: the display surface must not hide its own degradation just
 *  because the reason text is empty). Never write `=== null`. */
export function isDegraded(node: FragmentNode): boolean {
  return node.canonicalizationError != null
}

/** Truncated reason TEXT for display, or null when there is none to show —
 *  either the identity is clean, or it is degraded with a blank/whitespace-only
 *  marker (plan global constraints: 空串/空白 ⇒ 不出 reason 尾巴, label still
 *  renders). Trim before the emptiness test so `'   '` cannot smuggle a
 *  looks-empty tooltip through truthiness. Gate the label with `isDegraded`;
 *  use this only for the reason tail. */
export function getDegradedReason(node: FragmentNode): string | null {
  const reason = node.canonicalizationError?.trim()
  return reason ? truncateDegradedReason(reason) : null
}

export interface DecompositionIssue { code: string; message: string; ruleName?: string | null; scopePath?: string | null; fragmentId?: string | null; sql?: string | null }
export interface ExperimentChange { operation: 'REMOVE' | 'KEEP_ONLY' | 'EXTRACT' | 'ADD'; subject: string; scopePath: string; beforeSql?: string | null; afterSql?: string | null }
export interface ExperimentEffect { sourceSqlHash: string; targetSqlHash: string; context: 'PRESERVED' | 'DETACHED' | 'UNKNOWN'; changes: ExperimentChange[]; retained: { subject: string; scopePath: string }[]; limitations: DecompositionIssue[] }
export interface TransformOutput { sourceNodeId: string; ruleName: string; targetNodeId: string; ordinal: number; effect: ExperimentEffect }
export interface DecompositionBatch { status: 'COMPLETE' | 'PARTIAL' | 'NOT_APPLICABLE' | 'FAILED'; children: FragmentNode[]; outputs: TransformOutput[]; issues: DecompositionIssue[] }
export interface DecompositionScan { status: 'COMPLETE' | 'PARTIAL' | 'FAILED'; issues: DecompositionIssue[] }
export interface RuleResult { sourceNodeId: string; ruleName: string; childIds: string[]; status?: DecompositionBatch['status'] | 'LEGACY'; sourceSqlHash?: string; outputs?: TransformOutput[]; issues?: DecompositionIssue[] }
