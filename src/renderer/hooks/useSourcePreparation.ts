import type { DiagnosticLifecycle } from '../types/diagnostics'
import { decodeDiagnosticLifecycle } from '../utils/diagnosticLifecycle'
import { useRemoteTask } from './useRemoteTask'

interface Progress<T> {
  preparationId: string; kind: 'DIRECT' | 'COMPLEX'; status: string
  errorReason: string | null; lifecycle: DiagnosticLifecycle; plan: T | null
}

/** Oracle preparation is independently owned and observed, including cancellation before its ID arrives. */
export function useSourcePreparation<T>(backendUrl: string, scope: string) {
  const task = useRemoteTask<Progress<T>>(backendUrl, scope, value => {
    const data = value as Progress<T>
    if (!data || typeof data.preparationId !== 'string' || !['DIRECT', 'COMPLEX'].includes(data.kind)
      || !(data.errorReason === null || typeof data.errorReason === 'string') || !Object.prototype.hasOwnProperty.call(data, 'plan')
      || data.status !== 'COMPLETED' && data.plan !== null || data.status === 'COMPLETED' && (!data.plan || typeof data.plan !== 'object')) throw new Error('SOURCE_PREPARATION_RESPONSE_INVALID')
    decodeDiagnosticLifecycle(data.lifecycle, data.status)
    return data
  }, true)
  return { ...task, blocked: task.loading || task.submitting || task.cancelling || task.cleanupPending
      || ['RESOURCE_CLEANUP_UNCONFIRMED', 'STATUS_UNAVAILABLE', 'SUBMISSION_UNCONFIRMED', 'CANCEL_UNCONFIRMED'].includes(task.errorReason ?? ''),
    start: (kind: 'DIRECT' | 'COMPLEX', request: unknown, onPlan: (plan: T) => void) =>
      task.submit('/api/diagnostics/source-preparations', '/api/diagnostics/source-preparations', 'preparationId', { kind, request }, data => {
        if (data.kind !== kind) throw new Error('SOURCE_PREPARATION_KIND_MISMATCH')
        if (data.status === 'COMPLETED' && data.plan) onPlan(data.plan)
      }),
  }
}
