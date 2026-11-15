import { useLayoutEffect, useRef, useState } from 'react'
import type { DiagnosticContext } from '../types/session'
import type { TraceEvidence, TraceProgress, KeyValue, TraceStep, TraceOverallStatus, DiagnosticFinding, EliminationOutcome } from '../types/diagnostics'
import { useRemoteTask } from './useRemoteTask'
import { decodeDiagnosticLifecycle } from '../utils/diagnosticLifecycle'

export interface TraceOptions { diagnosticContext?: DiagnosticContext; sourceReceiptId?: string; transformEdges?: import('../types/fragment').TransformOutput[]; mode?: 'EXISTENCE' | 'CARDINALITY'; expectedMax?: string | null; queryTimeoutSeconds?: number }
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
  lifecycle?: TraceProgress['lifecycle'] | null
  cleanupPending?: boolean
  dialect?: string
  trace: (connectionId: string, fragmentTreeJson: string, keys: KeyValue[], options?: TraceOptions) => Promise<void>
  cancel: () => Promise<void>; clear: () => void; retryStatus?: () => void
}
export function useRowTrace(backendUrl: string | null, scope = '', prepareContext?: () => Promise<DiagnosticContext>, dialect = ''): UseRowTraceReturn {
  const task = useRemoteTask<TraceProgress>(backendUrl, scope, value => {
    const data = value as TraceProgress
    if (!data || typeof data.traceId !== 'string' || !Array.isArray(data.steps)) throw new Error('TRACE_RESPONSE_INVALID')
    if (dialect === 'oracle') decodeDiagnosticLifecycle(data.lifecycle, data.status)
    return data
  }, dialect === 'oracle')
  const [progress, setProgress] = useState<TraceProgress | null>(null)
  const [submittedKeys, setSubmittedKeys] = useState<KeyValue[] | null>(null)
  const [snapshot, setSnapshot] = useState<TraceSnapshot | null>(null)
  const [contractError, setContractError] = useState<string | null>(null)
  const [preparing, setPreparing] = useState(false)
  const generation = useRef(0)
  const currentScope = useRef(scope); currentScope.current = scope
  useLayoutEffect(() => { generation.current++; setPreparing(false); setProgress(null); setSubmittedKeys(null); setSnapshot(null); setContractError(null)
    return () => { generation.current++ }
  }, [backendUrl, scope])
  const clear = () => { generation.current++; setPreparing(false); task.reset(); setProgress(null); setSubmittedKeys(null); setSnapshot(null); setContractError(null) }
  return {
    traceId: task.id, status: (task.id && task.status === 'QUEUED' ? 'RUNNING' : task.status ?? 'idle') as UseRowTraceReturn['status'],
    steps: progress?.steps ?? [], eliminationStep: progress?.eliminationStep ?? null, findings: progress?.findings ?? [],
    totalElapsedMs: progress?.totalElapsedMs ?? 0, error: task.error ?? contractError,
    errorReason: task.errorReason ?? contractError ?? progress?.errorReason ?? null, eliminationOutcome: progress?.eliminationOutcome ?? null,
    evidence: progress?.evidence ?? null, submittedKeys,
    dialect, lifecycle: task.lifecycle as TraceProgress['lifecycle'] | null, cleanupPending: task.cleanupPending,
    mode: snapshot?.options.mode ?? 'EXISTENCE', quantity: contractError ? null : progress?.quantity,
    cancelSignal: dialect === 'oracle' && task.lifecycle ? { state: task.lifecycle.cancelSignal as import('../types/diagnostics').DiagnosticLifecycle['cancelSignal'], errorReason: task.lifecycle.errorReason ?? null } : progress?.cancelSignal, snapshot,
    blocked: preparing || task.loading || task.submitting || task.cancelling || task.cleanupPending || ['SUBMISSION_UNCONFIRMED', 'CANCEL_UNCONFIRMED', 'STATUS_UNAVAILABLE', 'RESOURCE_CLEANUP_UNCONFIRMED'].includes(task.errorReason ?? ''),
    trace: async (connectionId, fragmentTreeJson, keys, options = {}) => {
      const version = ++generation.current, capturedScope = scope
      let context = options.diagnosticContext
      if (prepareContext) {
        setPreparing(true)
        try { context = await prepareContext() }
        catch (e) { if (generation.current === version && currentScope.current === capturedScope) setContractError(e instanceof Error ? e.message : 'WORKSPACE_SAVE_UNCONFIRMED'); return }
        finally { if (generation.current === version) setPreparing(false) }
        if (version !== generation.current || currentScope.current !== capturedScope) return
      }
      const requestKeys = keys.map(key => ({ ...key }))
      const sqlById: Record<string, string> = {}
      let rootId: string | undefined
      const walk = (n: { id: string; sqlFragment: string; children?: unknown[] }) => { sqlById[n.id] = n.sqlFragment; n.children?.forEach(c => walk(c as typeof n)) }
      try { const root = JSON.parse(fragmentTreeJson); rootId = root.id; walk(root) } catch { /* The server returns an observable invalid-tree failure. */ }
      const requestOptions = { ...options, ...(context ? { diagnosticContext: context } : {}), mode: options.mode ?? 'EXISTENCE' as const }
      const expectedMaximum = requestOptions.expectedMax == null ? null : /^[0-9]{1,256}$/.test(requestOptions.expectedMax)
        ? BigInt(requestOptions.expectedMax).toString() : requestOptions.expectedMax
      setSnapshot({ scope, connectionId, fragmentTreeJson, keys: requestKeys, options: requestOptions, sqlById })
      setProgress(null); setContractError(null)
      setSubmittedKeys(requestKeys)
      await task.submit('/api/diagnostics/trace-async', '/api/diagnostics/trace-async', 'traceId',
        options.sourceReceiptId ? { connectionId, diagnosticContext: context, sourceReceiptId: options.sourceReceiptId,
          mode: 'CARDINALITY', queryTimeoutSeconds: options.queryTimeoutSeconds } :
          { connectionId, fragmentTreeJson, keys: requestKeys, ...requestOptions }, data => {
          if (options.sourceReceiptId && data.observationKind !== 'INDEPENDENT_SOURCE') {
            setContractError('TRACE_RESPONSE_INVALID'); setProgress(null); return
          }
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
              (data.quantity.rootCount ?? null) !== ((options.sourceReceiptId ? data.steps[0] : data.steps.find(s => s.fragmentId === rootId))?.count ?? null) ||
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
