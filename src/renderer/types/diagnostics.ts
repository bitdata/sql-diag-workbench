export type Severity = 'HIGH' | 'MEDIUM' | 'LOW'

export type FindingStatus = 'SUSPECTED' | 'CONFIRMED' | 'REFUTED'

export interface DiagnosticFinding {
  id: string
  ruleId: string
  severity: Severity
  title: string
  message: string
  evidence: string[]
  confidence: number  // 0.0–1.0
  verificationSql: string | null
  affectedNodeId: string | null
  status: FindingStatus
  verificationResult: string | null
  degraded?: boolean
  errorReason?: string | null
}

export interface KeyValue {
  type?: 'TEXT' | 'DECIMAL' | 'BOOLEAN' | 'NULL'
  column: string
  value: string | number | boolean | null
}

export type TraceStepStatus = 'HAS_ROWS' | 'NO_ROWS' | 'SKIPPED' | 'ERROR' | 'TIMEOUT'

export interface TraceStep {
  count?: string | null
  fragmentSql?: string | null
  observedAt?: string | null
  stepIndex: number
  fragmentId: string
  fragmentLabel: string
  sql: string
  status: TraceStepStatus
  rowCount: number
  elapsedMs: number
  error: string | null
  reasonCode?: string | null
  unavailableKeys?: string[]
}

/**
 * Discriminated union matching backend `EliminationOutcome` sealed interface.
 * The `outcomeType` discriminator lets the frontend exhaustively switch on
 * the four states; each variant carries exactly the fields the backend
 * sealed subtype does. Added 2026-08-27 to make the outcome a first-class
 * field on `TraceProgress` instead of being lost in polling.
 *
 * <p>Made a true discriminated union (PR-4, 2026-08-27) so TS narrows per
 * variant and exhaustiveness is enforced. The prior flat interface made
 * every field optional, forcing `outcome.parentStep!` / `outcome.step!`
 * non-null assertions in the switch — those silently hide a backend shape
 * change that drops a field. The union turns that into a compile error.
 */
export type EliminationOutcomeType = 'EDGE' | 'ERROR' | 'MISSING_AT_SOURCE' | 'NONE' | 'INCONCLUSIVE'

export type EliminationOutcome =
  | { outcomeType: 'EDGE'; childStep: TraceStep; parentStep: TraceStep }
  | { outcomeType: 'ERROR'; step: TraceStep; reason: string }
  | { outcomeType: 'MISSING_AT_SOURCE'; leafStep: TraceStep }
  | { outcomeType: 'NONE' }
  | { outcomeType: 'INCONCLUSIVE'; reason: string }

export interface TraceEvidence {
  scope: 'CURRENT_TREE'
  coverage: 'ALL_OBSERVED' | 'PARTIAL'
  totalNodes: number
  observedNodes: number
  rootPresence: 'PRESENT' | 'ABSENT' | 'UNKNOWN'
  limitations: { code: string; fragmentId: string | null; ruleId: string | null; message: string }[]
}

export type TraceOverallStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED'

export interface TraceProgress {
  mode?: 'EXISTENCE' | 'CARDINALITY'
  quantity?: QuantityEvidence | null
  cancelSignal?: { state: 'NOT_REQUIRED' | 'PENDING' | 'SENT' | 'FAILED'; errorReason: string | null } | null
  evidence?: TraceEvidence | null
  traceId: string
  status: TraceOverallStatus
  steps: TraceStep[]
  eliminationStep: TraceStep | null
  findings: DiagnosticFinding[]
  totalElapsedMs: number
  error: string | null
  eliminationOutcome: EliminationOutcome | null   // null on pre-2026-08-27 backends
  errorReason: string | null                      // null unless outcome is ERROR
}

export interface QuantityEvidence {
  expectedMax: string | null
  rootCount: string | null
  verdict: 'EXCEEDS_EXPECTATION' | 'WITHIN_EXPECTATION' | 'OBSERVATION_ONLY' | 'UNKNOWN'
  comparisons: {
    kind: 'JOIN_COUNT_CHANGE' | 'UNION_ALL_BRANCHES'
    parentFragmentId: string
    observations: { fragmentId: string; ordinal: number | null; reusedObservation: boolean }[]
    limitations: { code: string; message: string }[]
  }[]
}
