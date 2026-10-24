export type ExecutionStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'TIMED_OUT'
  | 'FAILED'
  | 'CANCELLED'

export interface ExecutionRequest {
  sessionId?: string | null
  sql: string
  connectionId: string
  /** Optional per-request timeout override (seconds). Null/0/negative falls back to default. */
  queryTimeoutSeconds?: number
  /** Maximum number of rows to return. Clamped to 10,000 server-side. */
  maxRows?: number
  /** "estimated" or "actual" — which EXPLAIN mode to run. Defaults to estimated. */
  analyzeMode?: 'estimated' | 'actual'
}

export interface ColumnInfo {
  name: string
  type: string
}

export interface ExecutionTiming {
  durationMs: number
}

/**
 * Structured predicate text carried by a plan node on the EXPLAIN JSON
 * channels. Each field is absent when the adapter did not populate it
 * (undefined) or explicitly null when the backend sent JSON null — the
 * two are equivalent for the consumer. Empty strings are not emitted by
 * the backend.
 *
 * Mirrors backend ExecutionResult.PlanNodeConditions (live-channel-
 * assumption-sweep 2026-09-09 bug ⑧ PR-3).
 */
export interface PlanNodeConditions {
  /** PostgreSQL "Filter" / MySQL `attached_condition` on the synthetic Filter child. */
  filter?: string | null
  /** PostgreSQL "Index Cond" — predicate used by an Index Scan. */
  indexCond?: string | null
  /** PostgreSQL "Hash Cond" — Hash Join join predicate. */
  hashCond?: string | null
  /** PostgreSQL "Join Filter" — predicate applied AFTER a nested-loop join. */
  joinFilter?: string | null
}

export interface PlanNode {
  metrics?: NodeMetrics | null
  operation: string
  startupCost: number
  totalCost: number
  planRows: number
  actualRows: number
  actualDuration: number
  children: PlanNode[]
  /**
   * Structured scanned relation from the backend EXPLAIN JSON channels
   * (PostgreSQL "Relation Name" / MySQL "table_name"); absent (undefined/null)
   * when the channel carries none. operation is the BARE node type on those
   * channels — do not expect a "Seq Scan on <table>" suffix there.
   * Mirrors ExecutionResult.PlanNode#tableName (backend).
   */
  tableName?: string | null
  /**
   * Structured predicate text from the backend plan adapters. Absent
   * (undefined) when the adapter did not populate any predicate for
   * this node — consumers MUST treat that as "no predicate info",
   * not "no predicate exists", mirroring backend semantics.
   * Mirrors ExecutionResult.PlanNode#conditions (backend).
   */
  conditions?: PlanNodeConditions | null
}

export interface ResultCompleteness {
  state: 'COMPLETE' | 'TRUNCATED' | 'UNKNOWN'
  appliedLimit: number | null
  errorReason: string | null
}

export interface ExecutionResult {
  planCapture?: PlanCapture | null
  resultCompleteness?: ResultCompleteness | null
  completedAt?: string | null
  errorReason?: string | null
  degraded?: boolean
  historyPersistence?: { status: 'not_requested' | 'pending' | 'saved' | 'failed'; errorReason?: string | null }
  executionId: string
  status: ExecutionStatus
  columns: ColumnInfo[] | null
  rows: unknown[][] | null
  rowCount: number
  timing: ExecutionTiming | null
  plan: PlanNode | null
  /**
   * Why the EXPLAIN could not be captured, if the SELECT itself succeeded.
   * null when plan is populated, or when the SELECT never ran. Distinct
   * from `error`, which is for SELECT failures.
   */
  planError: string | null
  error: string | null
}

export type Mode = 'estimated' | 'actual'
export type MetricState = 'AVAILABLE' | 'NOT_COLLECTED' | 'NOT_EXECUTED' | 'UNKNOWN' | 'PARSE_ERROR'
export interface Metric {
  state: MetricState
  value: string | null
  unit: 'ROWS' | 'MILLISECONDS' | 'LOOPS' | 'COST'
  basis: 'PER_INVOCATION' | 'PER_LOOP_AVERAGE' | 'TOTAL' | 'NODE_ESTIMATE' | 'QUERY_ESTIMATE' | 'PREFIX_ESTIMATE' | 'UNKNOWN'
  sourceField: string | null
  errorReason: string | null
}
export interface ModeCapability { state: 'SUPPORTED' | 'UNSUPPORTED' | 'UNKNOWN'; errorReason: string | null }
export interface PlanCapabilities {
  connectionId: string; dialect: string; serverProduct: string | null; serverVersion: string | null
  estimated: ModeCapability; actual: ModeCapability; observedAt: string | null
  degraded: boolean; errorReason: string | null
}
export interface CaptureIssue { code: string; nodePath: string | null; message: string }
export interface PlanCapture {
  version: 1; requestedMode: Mode; attemptedMode: Mode | null
  submittedSql: string; explainSql: string | null; connectionId: string; sessionId: string | null; dialect: string
  serverProduct: string | null; serverVersion: string | null; capturedAt: string | null
  capabilities: PlanCapabilities | null; parseStatus: 'NOT_STARTED' | 'COMPLETE' | 'PARTIAL' | 'FAILED'
  issues: CaptureIssue[]
}
export interface NodeMetrics {
  version: 1; origin: 'DATABASE_NODE' | 'APPLICATION_SYNTHETIC' | 'LEGACY_UNKNOWN'; sourcePath: string | null
  estimatedRows: Metric; actualRows: Metric; actualDurationMs: Metric; loops: Metric; startupCost: Metric; totalCost: Metric
}
export interface RuleEvaluation {
  ruleId: string; status: 'EVALUATED' | 'NOT_APPLICABLE' | 'SKIPPED' | 'FAILED'; errorReason: string | null
}
export interface Evaluation {
  version: 1; executionId: string | null
  sourceState: 'VERIFIED' | 'UNVERIFIED' | 'EXPIRED' | 'MISMATCH' | 'UNAVAILABLE'
  rules: RuleEvaluation[]; degraded: boolean; errorReason: string | null
}
