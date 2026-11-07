import type { Evaluation, PlanCapture, PlanNode, PlanSqlMapping } from './execution'
import type { DiagnosticFinding } from './diagnostics'
import type { FragmentNode } from './fragment'

export interface ObservationReceipt {
  version: number; id: string; executionId: string; sessionId: string | null; sessionName: string | null
  connectionId: string; dialect: string; submittedSql: string; submittedSqlSha256: string
  capturedAt: string; createdAt: string; expiresAt: string; requestedMode: string
  plan: PlanNode; planCapture: PlanCapture; degraded: boolean; errorReason: string | null
}
export interface InvestigationDraft {
  observationId: string; sessionId: string | null; workspace: FragmentNode | null
  evaluation: Evaluation | null; findings: DiagnosticFinding[] | null
}
export interface SnapshotSummary {
  availability: 'AVAILABLE' | 'UNAVAILABLE'; errorReason: string | null
  id: string; createdAt: string; title: string; sessionName: string | null; dialect: string
  serverProduct: string | null; serverVersion: string | null; partial: boolean; degraded: boolean; pinned: boolean
}
export interface ReceiptSummary {
  availability: 'AVAILABLE' | 'UNAVAILABLE'; errorReason: string | null
  id: string; executionId: string; sessionName: string | null; dialect: string
  capturedAt: string | null; createdAt: string; expiresAt: string
}
export interface InvestigationSection<T> { state: string; reason: string | null; value: T | null }
export interface InvestigationSnapshot {
  integrity: { version: number; payloadSha256: string }
  content: {
    version: number; id: string; createdAt: string
    sourceObservationId: string
    source: {
      sessionId: string | null; sessionName: string | null; executionId: string; connectionId: string
      dialect: string; serverProduct: string | null; serverVersion: string | null
      capturedAt: string; requestedMode: string; submittedSql: string; submittedSqlSha256: string
    }
    annotation: { title: string; hypothesis: string | null; observation: string | null }
    observation: ObservationReceipt
    workspace: InvestigationSection<FragmentNode>
    diagnostics: InvestigationSection<{ evaluation: Evaluation; findings: DiagnosticFinding[] }>
    mapping: InvestigationSection<PlanSqlMapping>
  }
}
