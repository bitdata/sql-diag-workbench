import type { DiagnosticLifecycle } from '../types/diagnostics'

export function decodeDiagnosticLifecycle(value: unknown, status: string): DiagnosticLifecycle {
  const lc = value as DiagnosticLifecycle | null
  if (!lc || lc.version !== 1 || !Number.isSafeInteger(lc.revision) || lc.revision < 0 || typeof lc.settled !== 'boolean'
    || !['NOT_REQUESTED', 'NOT_REQUIRED', 'PENDING', 'SENT', 'FAILED', 'REJECTED'].includes(lc.cancelSignal)
    || !['NOT_STARTED', 'PENDING', 'NO_RESOURCES', 'CLOSED', 'UNCONFIRMED'].includes(lc.cleanup)
    || !(lc.errorReason === null || typeof lc.errorReason === 'string') || typeof lc.degraded !== 'boolean'
    || !lc.resourceObservation || typeof lc.resourceObservation !== 'object' || Array.isArray(lc.resourceObservation)
    || lc.serverExecution !== 'UNKNOWN' || lc.applicationCleanup !== 'UNCONFIRMED'
    || lc.settled && (!['COMPLETED', 'FAILED', 'CANCELLED'].includes(status) || !['CLOSED', 'NO_RESOURCES'].includes(lc.cleanup) || lc.cancelSignal === 'PENDING')) {
    throw new Error('DIAGNOSTIC_LIFECYCLE_INVALID')
  }
  return lc
}
