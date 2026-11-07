import { apiFetch } from '../utils/api'
import { useState, useCallback, useLayoutEffect, useRef } from 'react'
import { dismissTaskNotice, reportTaskNotice } from '../utils/taskNotices'
import type { Evaluation } from '../types/execution'
import type { DiagnosticFinding } from '../types/diagnostics'
import { decodeDiagnosticResponse, DiagnosticResponseError } from '../utils/diagnosticResponse'

export interface DiagnosticLifecycle {
  status: 'idle' | 'loading' | 'ready' | 'unavailable' | 'error'
  ownerKey: string
  generation: number
  errorReason: string | null
}
export type DiagnosticActionResult = { status: 'skipped'; errorReason: string }

interface DiagnosticsState {
  lifecycle: DiagnosticLifecycle
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
  analyzePlan: (sql: string, sessionId: string | null, identity: { executionId: string; connectionId: string | null }) => Promise<void>
  analyze: (fragment: unknown, plan: unknown, timing: unknown, sessionId: string | null, identity?: { executionId: string; connectionId: string | null }) => Promise<void>
  clear: () => void
  verify: (findingId: string, verificationSql: string, connectionId: string) => Promise<void | DiagnosticActionResult>
  clearVerifyError: (findingId: string) => void
  retry?: () => void
}

export function useDiagnostics(backendUrl: string | null, scope = ''): DiagnosticsHook {
  const ownerKey = JSON.stringify([backendUrl, scope])
  const generation = useRef(0)
  const mounted = useRef(false)
  const latestOwner = useRef(ownerKey)
  latestOwner.current = ownerKey
  const empty = (): DiagnosticsState => ({ lifecycle: { status: 'idle', ownerKey, generation: generation.current, errorReason: null },
    findings: [], loading: false, error: null, verifyingIds: new Set(), verifyErrors: {} })
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
    mounted.current = true
    generation.current++
    verifying.current.clear()
    setState(empty())
    return () => { mounted.current = false; generation.current++; detachVerifications() }
  }, [backendUrl, scope])
  const [state, setState] = useState<DiagnosticsState>(empty)

  const requestAnalysis = useCallback(async (endpoint: string, body: unknown, executionId: string | null) => {
    if (!mounted.current || !backendUrl || latestOwner.current !== ownerKey) return
    const run = ++generation.current
    detachVerifications()
    setState({ ...empty(), loading: true, lifecycle: { status: 'loading', ownerKey, generation: run, errorReason: null } })
    try {
      const res = await apiFetch(`${backendUrl}/api/diagnostics/${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      })
      if (!res.ok) throw new DiagnosticResponseError(`DIAGNOSTICS_HTTP_${res.status}`, 'Diagnostics request failed')
      const data = await res.json()
      if (generation.current !== run || latestOwner.current !== ownerKey) return
      const decoded = decodeDiagnosticResponse(data, executionId)
      const available = decoded.evaluation.sourceState === 'VERIFIED'
      setState(s => ({ ...s, ...decoded, loading: false, lifecycle: { status: available ? 'ready' : 'unavailable',
        ownerKey, generation: run, errorReason: decoded.evaluation.errorReason ?? (available ? null : 'PLAN_SOURCE_UNAVAILABLE') } }))
    } catch (e: unknown) {
      if (generation.current !== run || latestOwner.current !== ownerKey) return
      const message = e instanceof Error ? e.message : 'Diagnostics request failed'
      const errorReason = e instanceof DiagnosticResponseError ? e.errorReason : 'DIAGNOSTICS_REQUEST_FAILED'
      setState(s => ({ ...s, error: message, loading: false, lifecycle: { status: 'error', ownerKey, generation: run, errorReason } }))
    }
  }, [backendUrl, ownerKey, detachVerifications])

  const analyze = useCallback<DiagnosticsHook['analyze']>((fragment, plan, timing, sessionId, identity) =>
    requestAnalysis('analyze', { fragment, plan, timing, sessionId, fragmentId: (fragment as { id?: string } | null)?.id ?? null, ...identity }, identity?.executionId ?? null), [requestAnalysis])
  const analyzePlan = useCallback<DiagnosticsHook['analyzePlan']>((sql, sessionId, identity) =>
    requestAnalysis('plan', { sql, sessionId, ...identity }, identity.executionId), [requestAnalysis])

  const clear = useCallback(() => {
    if (!mounted.current || latestOwner.current !== ownerKey) return
    generation.current++
    detachVerifications()
    setState(empty())
  }, [detachVerifications, ownerKey])

  const verify = useCallback(async (
    findingId: string,
    verificationSql: string,
    connectionId: string
  ) => {
    if (!mounted.current || !backendUrl || latestOwner.current !== ownerKey) return { status: 'skipped' as const, errorReason: 'DIAGNOSTICS_OWNER_CHANGED' }
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
      if (generation.current !== run || latestOwner.current !== ownerKey) return
      if (!['SUSPECTED', 'CONFIRMED', 'REFUTED'].includes(data.status) || typeof data.resultSummary !== 'string')
        throw new DiagnosticResponseError('DIAGNOSTICS_VERIFICATION_INVALID')
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
      if (generation.current !== run || latestOwner.current !== ownerKey) return
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
  }, [backendUrl, ownerKey])

  const clearVerifyError = useCallback((findingId: string) => {
    if (!mounted.current || latestOwner.current !== ownerKey) return
    setState(s => {
      const { [findingId]: _, ...rest } = s.verifyErrors
      return { ...s, verifyErrors: rest }
    })
  }, [ownerKey])

  // Gate during render; a layout reset alone leaves stale findings visible to children for one render.
  const visible = state.lifecycle.ownerKey === ownerKey ? state : empty()
  const renderedGeneration = visible.lifecycle.generation
  return { ...visible, analyze, analyzePlan, clear, verify: (...args) => {
    if (generation.current !== renderedGeneration) return Promise.resolve({ status: 'skipped', errorReason: 'DIAGNOSTICS_ACTION_STALE' })
    return verify(...args)
  }, clearVerifyError }
}
