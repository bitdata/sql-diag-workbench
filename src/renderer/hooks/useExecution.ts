import { useCallback, useEffect, useRef, useState } from 'react'
import type { ExecutionResult, ExecutionStatus } from '../types/execution'
import { useRemoteTask } from './useRemoteTask'
import { normalizeCompleteness } from '../utils/resultCompleteness'

export interface ExecutionSettings { maxRows: number; queryTimeout: number }
export interface ExecutionSummaryLike { rows: number; durationMs: number; resultCompleteness?: import('../types/execution').ResultCompleteness | null }
export interface ExecutionContext { sessionId: string | null; dialect: string; connectionId: string | null; sql?: string; revision?: number }
export interface ExecutionOwner extends ExecutionContext { nodeId: string; sql: string; kind: 'execute' | 'analyze' }

export function useExecution(backendUrl: string | null, activeNodeId: string | null,
  onExecutionSuccess?: (summary: ExecutionSummaryLike, owner: ExecutionOwner) => void,
  executionSettings: ExecutionSettings = { maxRows: 1000, queryTimeout: 30 }, context?: ExecutionContext) {
  const scope = JSON.stringify([context?.sessionId, context?.dialect, context?.connectionId, context?.revision])
  const task = useRemoteTask<ExecutionResult>(backendUrl, scope)
  const [cache, setCache] = useState<Record<string, { data?: ExecutionResult; plan?: ExecutionResult; sql: string; connectionId: string }>>({})
  const [kind, setKind] = useState<'execute' | 'analyze' | null>(null)
  const [taskOwner, setTaskOwner] = useState<ExecutionOwner | null>(null)
  const [localError, setLocalError] = useState<string | null>(null)
  const latest = useRef({ activeNodeId, onExecutionSuccess, context, scope, executionSettings })
  latest.current = { activeNodeId, onExecutionSuccess, context, scope, executionSettings }
  useEffect(() => { setCache({}); setKind(null); setLocalError(null) }, [scope])
  const run = async (kind: 'execute' | 'analyze', sql: string, connectionId: string, analyzeMode?: 'estimated' | 'actual') => {
    const now = latest.current
    if (!now.activeNodeId) { setLocalError('No fragment selected'); return }
    if (task.submitting) return
    setLocalError(null)
    const owner: ExecutionOwner = { sessionId: now.context?.sessionId ?? null, dialect: now.context?.dialect ?? '',
      nodeId: now.activeNodeId, sql, connectionId, kind, revision: now.context?.revision }
    const key = JSON.stringify([now.scope, owner.nodeId])
    setTaskOwner(owner)
    setKind(kind)
    setCache(prev => ({ ...prev, [key]: { ...(prev[key]?.sql === sql && prev[key]?.connectionId === connectionId ? prev[key] : {}), sql, connectionId, [kind === 'execute' ? 'data' : 'plan']: undefined } }))
    await task.submit(`/api/execution/${kind}`, '/api/execution/execute', 'executionId', {
      sql, connectionId, queryTimeoutSeconds: now.executionSettings.queryTimeout, maxRows: now.executionSettings.maxRows,
      sessionId: owner.sessionId,
      ...(analyzeMode ? { analyzeMode } : {}),
    }, data => {
      if (data.status === 'QUEUED' || data.status === 'RUNNING') return
      setCache(prev => ({ ...prev, [key]: { ...(prev[key]?.sql === sql && prev[key]?.connectionId === connectionId ? prev[key] : {}), sql, connectionId, [kind === 'execute' ? 'data' : 'plan']: data } }))
      if (kind === 'execute' && data.status === 'COMPLETED' && data.rows) {
        latest.current.onExecutionSuccess?.({ rows: data.rows.length, durationMs: data.timing?.durationMs ?? 0,
          resultCompleteness: normalizeCompleteness(data.resultCompleteness) }, owner)
      }
    })
  }
  const selected = cache[JSON.stringify([scope, activeNodeId])]
  const valid = selected && (context?.sql === undefined || context.sql === selected.sql)
    && (!context?.connectionId || context.connectionId === selected.connectionId)
  const ownsTask = taskOwner?.nodeId === activeNodeId && (context?.sql === undefined || context.sql === taskOwner.sql)
  const dataResult = valid ? selected.data ?? null : null
  const planResult = valid ? selected.plan ?? null : null
  const reset = useCallback(() => { task.reset(); setCache({}); setKind(null); setLocalError(null) }, [task.reset])
  return { dataResult, planResult, result: dataResult, executionId: task.id, status: (ownsTask ? task.status : null) as ExecutionStatus | null,
    provenance: valid ? { sql: selected.sql, connectionId: selected.connectionId, dialect: context?.dialect ?? '' } : null,
    kind, loading: task.loading, submitting: task.submitting, cancelling: task.cancelling, error: (ownsTask ? task.error : null) ?? localError,
    errorReason: (ownsTask || task.loading ? task.errorReason : null) ?? (localError ? 'NO_FRAGMENT' : null), startedAt: task.startedAt, cancel: task.cancel, retryStatus: task.retryStatus,
    execute: (sql: string, connectionId: string) => run('execute', sql, connectionId),
    analyze: (sql: string, connectionId: string, mode: 'estimated' | 'actual' = 'estimated') => run('analyze', sql, connectionId, mode),
    reset, clearResult: reset }
}
