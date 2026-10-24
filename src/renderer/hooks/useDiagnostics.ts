import { apiFetch } from '../utils/api'
import { useState, useCallback, useLayoutEffect, useRef } from 'react'
import { dismissTaskNotice, reportTaskNotice } from '../utils/taskNotices'
import type { Evaluation } from '../types/execution'
import type { DiagnosticFinding } from '../types/diagnostics'

interface DiagnosticsState {
  evaluation?: Evaluation | null
  reportPersistence?: { status: string; errorReason?: string | null } | null
  findings: DiagnosticFinding[]
  loading: boolean
  error: string | null
  verifyingIds: Set<string>
  verifyErrors: Record<string, string>
}

/**
 * Shape of the return value of {@link useDiagnostics}. Exported so the
 * parent (App.tsx) can own the hook and pass the state + actions down to
 * {@code ResultsPanel} / {@code DiagnosticsPanel} without re-creating the
 * hook at the child level.
 */
export interface DiagnosticsHook extends DiagnosticsState {
  analyze: (fragment: unknown, plan: unknown, timing: unknown, sessionId: string | null, identity?: { executionId: string; connectionId: string | null }) => Promise<void>
  clear: () => void
  verify: (findingId: string, verificationSql: string, connectionId: string) => Promise<void>
  clearVerifyError: (findingId: string) => void
}

export function useDiagnostics(backendUrl: string | null, scope = ''): DiagnosticsHook {
  const generation = useRef(0)
  const verifying = useRef(new Set<string>())
  const pendingVerifications = useRef(new Set<{ notice?: number }>())
  const detachVerifications = useCallback(() => {
    for (const pending of pendingVerifications.current) {
      pending.notice ??= reportTaskNotice('VERIFICATION_PENDING',
        'A diagnostic verification is still running on the server. This API does not support cancellation.')
    }
    verifying.current.clear()
  }, [])
  useLayoutEffect(() => {
    generation.current++
    verifying.current.clear()
    setState({ findings: [], loading: false, error: null, verifyingIds: new Set(), verifyErrors: {} })
    return () => { generation.current++; detachVerifications() }
  }, [backendUrl, scope])
  const [state, setState] = useState<DiagnosticsState>({
    findings: [],
    loading: false,
    error: null,
    verifyingIds: new Set(),
    verifyErrors: {},
  })

  const analyze = useCallback(async (
    fragment: unknown,
    plan: unknown,
    timing: unknown,
    sessionId: string | null,
    identity?: { executionId: string; connectionId: string | null }
  ) => {
    if (!backendUrl) return
    const run = ++generation.current
    detachVerifications()
    setState(s => ({ ...s, findings: [], verifyingIds: new Set(), verifyErrors: {}, loading: true, error: null, evaluation: null, reportPersistence: null }))
    try {
      const fragmentId = (fragment as { id?: string } | null)?.id ?? null
      const res = await apiFetch(`${backendUrl}/api/diagnostics/analyze`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fragment, plan, timing, sessionId, fragmentId, ...identity })
      })
      if (!res.ok) throw new Error(`Diagnostics failed (HTTP ${res.status})`)
      const data = await res.json()
      if (generation.current !== run) return
      setState(s => ({ ...s, findings: data.findings ?? [], evaluation: data.evaluation ?? null, loading: false, reportPersistence: data.reportPersistence ?? null }))
    } catch (e: unknown) {
      if (generation.current !== run) return
      const message = e instanceof Error ? e.message : 'Diagnostics request failed'
      setState(s => ({ ...s, error: message, loading: false }))
    }
  }, [backendUrl, detachVerifications])

  const clear = useCallback(() => {
    generation.current++
    detachVerifications()
    setState({ findings: [], loading: false, error: null, verifyingIds: new Set(), verifyErrors: {} })
  }, [detachVerifications])

  const verify = useCallback(async (
    findingId: string,
    verificationSql: string,
    connectionId: string
  ) => {
    if (!backendUrl) return
    if (verifying.current.has(findingId)) return
    verifying.current.add(findingId)
    const run = generation.current
    const pending: { notice?: number } = {}
    pendingVerifications.current.add(pending)

    setState(s => ({
      ...s,
      verifyingIds: new Set(s.verifyingIds).add(findingId)
    }))

    try {
      // Spec §4.2: diagnostics verification runs a real query on the target DB — a
      // legitimately long call that must not inherit apiFetch's default deadline.
      const res = await apiFetch(`${backendUrl}/api/diagnostics/verify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ findingId, verificationSql, connectionId })
      }, { timeoutMs: null })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      if (data.degraded) throw new Error(data.resultSummary || 'Connection credentials require attention. Edit the connection.')
      if (generation.current !== run) return
      verifying.current.delete(findingId)

      setState(s => ({
        ...s,
        findings: s.findings.map(f =>
          f.id === findingId
            ? { ...f, status: data.status, verificationResult: data.resultSummary }
            : f
        ),
        verifyingIds: (() => {
          const next = new Set(s.verifyingIds)
          next.delete(findingId)
          return next
        })(),
        verifyErrors: (() => {
          const { [findingId]: _, ...rest } = s.verifyErrors
          return rest
        })(),
      }))
    } catch (e: unknown) {
      if (generation.current !== run) return
      verifying.current.delete(findingId)
      const message = e instanceof Error ? e.message : 'Verification failed'
      setState(s => ({
        ...s,
        verifyingIds: (() => {
          const next = new Set(s.verifyingIds)
          next.delete(findingId)
          return next
        })(),
        verifyErrors: { ...s.verifyErrors, [findingId]: message },
      }))
    } finally {
      pendingVerifications.current.delete(pending)
      if (pending.notice) dismissTaskNotice(pending.notice)
    }
  }, [backendUrl])

  const clearVerifyError = useCallback((findingId: string) => {
    setState(s => {
      const { [findingId]: _, ...rest } = s.verifyErrors
      return { ...s, verifyErrors: rest }
    })
  }, [])

  return { ...state, analyze, clear, verify, clearVerifyError }
}
