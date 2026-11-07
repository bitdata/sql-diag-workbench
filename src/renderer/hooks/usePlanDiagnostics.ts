import { useEffect, useRef } from 'react'
import type { ExecutionResult } from '../types/execution'
import type { FragmentNode } from '../types/fragment'
import { isWorkspaceExitLocked } from '../utils/workspaceExitGate'
import { useDiagnostics, type DiagnosticActionResult, type DiagnosticsHook } from './useDiagnostics'

export interface DiagnosticSubject {
  kind: 'input' | 'fragment'
  sessionId: string | null
  connectionId: string
  dialect: string
  revision: string | number
  sql: string
  result: ExecutionResult
  fragment?: FragmentNode | null
}

export function diagnosticSubjectKey(subject: DiagnosticSubject | null): string {
  return subject ? JSON.stringify([subject.sessionId, subject.connectionId, subject.dialect, subject.revision,
    subject.kind, subject.kind === 'input' ? '$editor' : subject.fragment?.id, subject.sql, subject.result.executionId]) : 'no-plan'
}

/**
 * Owner changes invalidate the receipt AND its dedup state together:
 *   idle -> loading -> ready | unavailable | error --explicit retry--> loading
 *   owner/null/clear -> idle (new generation); mode changes keep the receipt.
 * The transport owns generation; no second "last plan" ref survives its reset.
 */
export function usePlanDiagnostics(backendUrl: string | null, candidate: DiagnosticSubject | null,
  enabled: boolean, blocked = false): DiagnosticsHook {
  const subject = !blocked && !isWorkspaceExitLocked() && candidate?.result.plan && candidate.sql
    && candidate.connectionId && (candidate.kind === 'input' || candidate.fragment?.sqlFragment === candidate.sql) ? candidate : null
  const key = diagnosticSubjectKey(subject)
  const transport = useDiagnostics(backendUrl, key)
  const latest = useRef({ key, subject, transport, enabled, blocked })
  latest.current = { key, subject, transport, enabled, blocked }

  useEffect(() => {
    if (!enabled || !subject || transport.lifecycle.status !== 'idle' || isWorkspaceExitLocked()) return
    const identity = { executionId: subject.result.executionId, connectionId: subject.connectionId }
    if (subject.kind === 'input') void transport.analyzePlan(subject.sql, subject.sessionId, identity)
    else void transport.analyze(subject.fragment, subject.result.plan, subject.result.timing, subject.sessionId, identity)
    // Subject content is identified synchronously by key; unrelated object allocations do not request again.
  }, [backendUrl, key, enabled, transport.lifecycle.status, transport.analyze, transport.analyzePlan])

  const generation = transport.lifecycle.generation
  const current = () => {
    const now = latest.current
    return now.key === key && now.transport.lifecycle.generation === generation
      && now.transport.lifecycle.ownerKey === transport.lifecycle.ownerKey && !!now.subject && !now.blocked && !isWorkspaceExitLocked()
  }
  const reject = (): DiagnosticActionResult => ({ status: 'skipped', errorReason: 'DIAGNOSTICS_ACTION_STALE' })
  return {
    ...transport,
    clear: () => { if (current()) latest.current.transport.clear() },
    retry: () => {
      if (current() && latest.current.enabled && latest.current.transport.lifecycle.status === 'error') latest.current.transport.clear()
    },
    verify: async (id, sql, connectionId) => {
      const now = latest.current
      if (!current() || now.transport.lifecycle.status !== 'ready' || now.subject?.connectionId !== connectionId) return reject()
      const finding = now.transport.findings.find(f => f.id === id)
      if (!finding || finding.degraded || finding.status !== 'SUSPECTED' || !sql || finding.verificationSql !== sql) return reject()
      return now.transport.verify(id, sql, connectionId)
    },
    clearVerifyError: id => { if (current()) latest.current.transport.clearVerifyError(id) },
  }
}
