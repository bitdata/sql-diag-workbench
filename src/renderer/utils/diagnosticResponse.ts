import type { Evaluation } from '../types/execution'
import type { DiagnosticFinding } from '../types/diagnostics'

export class DiagnosticResponseError extends Error {
  constructor(readonly errorReason: string, message = errorReason) { super(message) }
}
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const nullableText = (v: unknown) => v == null || typeof v === 'string'
const member = (v: unknown, choices: string[]) => typeof v === 'string' && choices.includes(v)

/** The server's evaluation receipt, not an empty-array fallback, establishes a completed analysis. */
export function decodeDiagnosticResponse(raw: unknown, executionId: string | null): {
  findings: DiagnosticFinding[]; evaluation: Evaluation
  reportPersistence: { status: string; errorReason?: string | null } | null
} {
  const invalid = () => { throw new DiagnosticResponseError('DIAGNOSTICS_RESPONSE_INVALID') }
  if (!object(raw) || !Array.isArray(raw.findings) || !object(raw.evaluation)) return invalid()
  const e = raw.evaluation
  if (e.version !== 1 || !nullableText(e.executionId)
    || !member(e.sourceState, ['VERIFIED', 'UNVERIFIED', 'EXPIRED', 'MISMATCH', 'UNAVAILABLE'])
    || !Array.isArray(e.rules) || typeof e.degraded !== 'boolean' || !nullableText(e.errorReason)) return invalid()
  if (e.executionId !== executionId) throw new DiagnosticResponseError('DIAGNOSTICS_EXECUTION_MISMATCH')
  const ruleIds = new Set<string>()
  for (const rule of e.rules) {
    if (!object(rule) || typeof rule.ruleId !== 'string' || !rule.ruleId || ruleIds.has(rule.ruleId)
      || !member(rule.status, ['EVALUATED', 'NOT_APPLICABLE', 'SKIPPED', 'FAILED']) || !nullableText(rule.errorReason)) return invalid()
    ruleIds.add(rule.ruleId)
  }
  const ids = new Set<string>()
  for (const f of raw.findings) {
    if (!object(f) || typeof f.id !== 'string' || !f.id || ids.has(f.id)
      || typeof f.ruleId !== 'string' || !f.ruleId || typeof f.title !== 'string' || typeof f.message !== 'string'
      || !member(f.severity, ['HIGH', 'MEDIUM', 'LOW']) || !member(f.status, ['SUSPECTED', 'CONFIRMED', 'REFUTED'])
      || !Array.isArray(f.evidence) || f.evidence.some(v => typeof v !== 'string')
      || typeof f.confidence !== 'number' || !Number.isFinite(f.confidence) || f.confidence < 0 || f.confidence > 1
      || !nullableText(f.verificationSql) || !nullableText(f.verificationResult) || !nullableText(f.affectedNodeId)
      || !nullableText(f.planNodePath) || !nullableText(f.errorReason)
      || (f.degraded !== undefined && typeof f.degraded !== 'boolean')) return invalid()
    ids.add(f.id)
  }
  const persistence = raw.reportPersistence
  if (persistence != null && (!object(persistence) || !member(persistence.status, ['saved', 'failed', 'not_requested'])
    || !nullableText(persistence.errorReason))) return invalid()
  return { findings: raw.findings as DiagnosticFinding[], evaluation: e as unknown as Evaluation,
    reportPersistence: persistence == null ? null : persistence as { status: string; errorReason?: string | null } }
}
