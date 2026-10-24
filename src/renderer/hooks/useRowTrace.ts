import { useEffect, useState } from 'react'
import type { TraceEvidence, TraceProgress, KeyValue, TraceStep, TraceOverallStatus, DiagnosticFinding, EliminationOutcome } from '../types/diagnostics'
import { useRemoteTask } from './useRemoteTask'

export interface TraceOptions { transformEdges?: import('../types/fragment').TransformOutput[]; mode?: 'EXISTENCE' | 'CARDINALITY'; expectedMax?: string | null; queryTimeoutSeconds?: number }
export interface TraceSnapshot { scope: string; connectionId: string; fragmentTreeJson: string; keys: KeyValue[]; options: TraceOptions; sqlById: Record<string, string> }

export interface UseRowTraceReturn {
  traceId: string | null; status: 'idle' | TraceOverallStatus; steps: TraceStep[]; eliminationStep: TraceStep | null
  findings: DiagnosticFinding[]; totalElapsedMs: number; error: string | null; errorReason: string | null
  eliminationOutcome: EliminationOutcome | null
  evidence?: TraceEvidence | null
  submittedKeys?: KeyValue[] | null
  mode?: 'EXISTENCE' | 'CARDINALITY'
  quantity?: TraceProgress['quantity']
  cancelSignal?: TraceProgress['cancelSignal']
  snapshot?: TraceSnapshot | null
  blocked?: boolean
  trace: (connectionId: string, fragmentTreeJson: string, keys: KeyValue[], options?: TraceOptions) => Promise<void>
  cancel: () => Promise<void>; clear: () => void; retryStatus?: () => void
}
export function useRowTrace(backendUrl: string | null, scope = ''): UseRowTraceReturn {
  const task = useRemoteTask<TraceProgress>(backendUrl, scope)
  const [progress, setProgress] = useState<TraceProgress | null>(null)
  const [submittedKeys, setSubmittedKeys] = useState<KeyValue[] | null>(null)
  const [snapshot, setSnapshot] = useState<TraceSnapshot | null>(null)
  const [contractError, setContractError] = useState<string | null>(null)
  useEffect(() => { setProgress(null); setSubmittedKeys(null); setSnapshot(null); setContractError(null) }, [backendUrl, scope])
  const clear = () => { task.reset(); setProgress(null); setSubmittedKeys(null); setSnapshot(null); setContractError(null) }
  return {
    traceId: task.id, status: (task.id && task.status === 'QUEUED' ? 'RUNNING' : task.status ?? 'idle') as UseRowTraceReturn['status'],
    steps: progress?.steps ?? [], eliminationStep: progress?.eliminationStep ?? null, findings: progress?.findings ?? [],
    totalElapsedMs: progress?.totalElapsedMs ?? 0, error: task.error ?? contractError,
    errorReason: task.errorReason ?? contractError ?? progress?.errorReason ?? null, eliminationOutcome: progress?.eliminationOutcome ?? null,
    evidence: progress?.evidence ?? null, submittedKeys,
    mode: snapshot?.options.mode ?? 'EXISTENCE', quantity: contractError ? null : progress?.quantity, cancelSignal: progress?.cancelSignal, snapshot,
    blocked: task.loading || task.submitting || task.cancelling || ['SUBMISSION_UNCONFIRMED', 'CANCEL_UNCONFIRMED', 'STATUS_UNAVAILABLE'].includes(task.errorReason ?? ''),
    trace: async (connectionId, fragmentTreeJson, keys, options = {}) => {
      const requestKeys = keys.map(key => ({ ...key }))
      const sqlById: Record<string, string> = {}
      let rootId: string | undefined
      const walk = (n: { id: string; sqlFragment: string; children?: unknown[] }) => { sqlById[n.id] = n.sqlFragment; n.children?.forEach(c => walk(c as typeof n)) }
      try { const root = JSON.parse(fragmentTreeJson); rootId = root.id; walk(root) } catch { /* The server returns an observable invalid-tree failure. */ }
      const requestOptions = { ...options, mode: options.mode ?? 'EXISTENCE' as const }
      const expectedMaximum = requestOptions.expectedMax == null ? null : /^[0-9]{1,256}$/.test(requestOptions.expectedMax)
        ? BigInt(requestOptions.expectedMax).toString() : requestOptions.expectedMax
      setSnapshot({ scope, connectionId, fragmentTreeJson, keys: requestKeys, options: requestOptions, sqlById })
      setProgress(null); setContractError(null)
      setSubmittedKeys(requestKeys)
      await task.submit('/api/diagnostics/trace-async', '/api/diagnostics/trace-async', 'traceId',
        { connectionId, fragmentTreeJson, keys: requestKeys, ...requestOptions }, data => {
          if (requestOptions.mode === 'CARDINALITY') {
            const validCount = (value: unknown) => typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value) && BigInt(value) <= 9223372036854775807n
            if (data.mode !== 'CARDINALITY' || !data.quantity) {
              setContractError('TRACE_RESPONSE_UNSUPPORTED'); setProgress(null); return
            }
            else if (!Array.isArray(data.steps) || data.steps.some(s => s.count != null && !validCount(s.count)) ||
              data.quantity.rootCount != null && !validCount(data.quantity.rootCount) || !Array.isArray(data.quantity.comparisons) ||
              !['EXCEEDS_EXPECTATION', 'WITHIN_EXPECTATION', 'OBSERVATION_ONLY', 'UNKNOWN'].includes(data.quantity.verdict) ||
              data.quantity.expectedMax != null && !validCount(data.quantity.expectedMax) ||
              data.status !== 'COMPLETED' && data.quantity.verdict !== 'UNKNOWN' ||
              data.quantity.verdict !== 'UNKNOWN' && data.quantity.rootCount == null ||
              (data.quantity.expectedMax ?? null) !== expectedMaximum ||
              (data.quantity.rootCount ?? null) !== (data.steps.find(s => s.fragmentId === rootId)?.count ?? null) ||
              data.quantity.verdict !== (data.status !== 'COMPLETED' || data.quantity.rootCount == null ? 'UNKNOWN' :
                requestOptions.expectedMax == null ? 'OBSERVATION_ONLY' :
                  BigInt(data.quantity.rootCount) > BigInt(requestOptions.expectedMax) ? 'EXCEEDS_EXPECTATION' : 'WITHIN_EXPECTATION') ||
              data.quantity.comparisons.some(c => !c || !data.steps.some(s => s.fragmentId === c.parentFragmentId) ||
                !Array.isArray(c.observations) || c.observations.some(o => !o || !data.steps.some(s => s.fragmentId === o.fragmentId && s.count != null)))) {
              setContractError('TRACE_RESPONSE_INVALID'); setProgress(null); return
            }
          }
          setProgress(data)
        })
    }, cancel: task.cancel, clear, retryStatus: task.retryStatus,
  }
}
