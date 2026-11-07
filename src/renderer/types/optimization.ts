// Versioned optimization wire contract, independent of diagnostic reports.
export interface Source { sessionId: string; sourceRevision: string; sql: string; connectionId: string; connectionRevision: string; dialect: 'postgresql' | 'mysql' }
export interface CandidateRequest { sessionId: string; sourceRevision: string; sql: string; connectionId: string; dialect: 'postgresql' | 'mysql' }
export interface Candidate { id: string; ruleId: 'positive-in-exists' | 'count-star-exists'; ruleVersion: 1; path: string; sql: string; conditions: string[] }
export interface Issue { code: string; path: string; message: string }
export interface Generation { version: 1; source: Source; candidates: Candidate[]; skipped: Issue[] }
export interface ValidationRequest { source: Source; candidateSql: string; candidateId: string | null; timeoutSeconds: number | null }
export interface Submission { validationId: string }
export interface ReportSummary { version: number; validationId: string; completedAt: string; verdict: string; originalPreview: string; candidatePreview: string }
export interface ReportPage { version: 1; items: ReportSummary[]; nextCursor: string | null }
export interface Cell { kind: 'EXACT_NUMBER' | 'TEXT' | 'NULL'; value: string | null }
export interface Column { label: string; jdbcType: number; typeName: string; precision: number; scale: number; signed: boolean }
export interface Difference { cells: Cell[]; originalCount: string; candidateCount: string }
export interface Side { rowCount: string; complete: boolean; columns: Column[] }
export interface SourceEvidence { schema: string; table: string; kind: string; engine: string | null; checks: Record<string, string>; columns: Column[] }
export interface Snapshot { state: 'SHARED_RR' | 'UNVERIFIED'; product: string; serverVersion: string; database: string; databaseUser: string; isolation: string; readOnly: boolean; sessionIdentity: string; settings: Record<string, string>; sources: SourceEvidence[] }
export interface Persistence { status: 'PENDING' | 'SAVED' | 'FAILED'; errorReason: string | null }
export interface Report {
  version: 1; validationId: string; status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED';
  verdict: 'NOT_EVALUATED' | 'MATCH' | 'DIFFERENT' | 'INCONCLUSIVE'; errorReason: string | null; degraded: boolean;
  source: Source; candidateSql: string; candidateId: string | null; candidateEvidence: Candidate | null; comparisonMode: 'EXACT_TYPED_BAG_V1';
  snapshot: Snapshot | null; original: Side | null; candidate: Side | null; differences: Difference[]; differencesTruncated: boolean;
  startedAt: string | null; completedAt: string | null; persistence: Persistence;
}

export interface ExperimentRequest { version: 1; source: Source; candidateSql: string; candidateId: string | null; seed: string | null; timeoutSeconds: number | null }
export interface ExperimentSubmission { experimentId: string }
export interface ExperimentProtocol { id: 'PAIRED_SELECT_V1'; seed: string; warmupPairs: 2; measuredPairs: 10; orderAlgorithm: 'LCG32_FISHER_YATES_V1'; pgPreparation: 'THRESHOLD_ZERO'; mysqlPreparation: 'CLIENT_PREPARED_STREAMING'; fetchSize: number }
export interface ExperimentSample { pair: number; phase: 'WARMUP' | 'MEASURED'; order: 'AB' | 'BA'; side: 'A' | 'B'; status: 'COMPLETE' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED'; durationNs: string | null; executeReturnNs: string | null; drainNs: string | null; closeNs: string | null; rows: string; bytes: string; errorReason: string | null }
export interface ExperimentDistribution { medianNs: string; q1Ns: string; q3Ns: string; minNs: string; maxNs: string }
export interface ExperimentSummary { state: 'DESCRIPTIVE' | 'NOT_AVAILABLE'; original: ExperimentDistribution | null; candidate: ExperimentDistribution | null; pairedMedianDeltaNs: string | null; relativeMedianChangePct: string | null; notes: string[] }
export interface ExperimentValidation { verdict: 'NOT_EVALUATED' | 'MATCH' | 'DIFFERENT' | 'INCONCLUSIVE'; errorReason: string | null; comparisonMode: 'EXACT_TYPED_BAG_V1'; snapshot: Snapshot | null; original: Side | null; candidate: Side | null; differences: Difference[]; differencesTruncated: boolean }
export interface ExperimentPlanMetric { state: string; value: string | null; unit: string; basis: string; sourceField: string; errorReason: string | null }
export interface ExperimentPlanDetails { version: 1; channel: 'POSTGRESQL_JSON' | 'MYSQL_TREE'; rawPlan: string | null; database: string | null; databaseUser: string | null; settings: Record<string,string>; planningTimeMs: ExperimentPlanMetric; executionTimeMs: ExperimentPlanMetric; nodes: { sourcePath: string; raw: string | null; firstRowMs: ExperimentPlanMetric }[]; errorReason: string | null }
export interface ExperimentPlanEvidence { status: 'COMPLETE' | 'PARTIAL' | 'FAILED' | 'SKIPPED'; dialect: 'postgresql' | 'mysql'; channel: 'POSTGRESQL_JSON' | 'MYSQL_TREE'; connectionRevision: string | null; submittedSql: string | null; explainSql: string | null; capturedAt: string; databaseProduct: string | null; databaseVersion: string | null; sessionIdentity: string | null; details: ExperimentPlanDetails | null; errorReason: string | null; degraded: boolean }
export interface ExperimentActualPlans { original: ExperimentPlanEvidence; candidate: ExperimentPlanEvidence; degraded: boolean; errorReason: string | null }
export interface ExperimentReport { version: 1; experimentId: string; status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED'; phase: 'ADMISSION' | 'CONNECTING' | 'VALIDATING' | 'WARMUP' | 'MEASURED' | 'ACTUAL_PLAN' | 'CLEANUP' | 'PERSISTING' | 'DONE' | 'CLEANUP_UNCONFIRMED'; comparisonState: 'NOT_EVALUATED' | 'IN_PROGRESS' | 'COMPLETE' | 'INCOMPARABLE'; errorReason: string | null; degraded: boolean; request: ExperimentRequest; protocol: ExperimentProtocol; validation: ExperimentValidation | null; environment: { before: Record<string,string>; after: Record<string,string>; comparable: boolean; limitations: string[] } | null; samples: ExperimentSample[]; summary: ExperimentSummary; persistence: Persistence; actualPlans: ExperimentActualPlans | null; createdAt: string; startedAt: string | null; completedAt: string | null }
