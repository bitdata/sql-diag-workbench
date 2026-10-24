import { useCallback, useEffect, useRef, useState } from 'react'
import { isWorkspaceExitLocked } from '../utils/workspaceExitGate'
import type { FragmentNode } from '../types/fragment'
import type { EditLink } from '../utils/editLinks'
import type { UserEditOutcome } from './useFragmentTree'
import { createTabIntent, type TabCreationIntent, type UseTabsApi } from './useTabs'

type CompletedEdit = Extract<UserEditOutcome, { status: 'EDIT' }>
interface EditContext {
  sourceSessionId: string
  sourceTabName: string
  sourceTree: FragmentNode
  fragId: string
  newSql: string
  description: string
  dialect: string
  connectionId?: string | null
}
type Recovery = { stage: 'submit'; context: EditContext } |
  { stage: 'open'; context: EditContext; outcome: CompletedEdit; intent: TabCreationIntent }
interface Feedback {
  label: string
  message: string
  severity: 'error' | 'info' | 'warning'
  sourceSessionId: string
  recovery?: Recovery
}
interface Options {
  tabs: UseTabsApi
  tree: FragmentNode | null
  nodeMap: Map<string, FragmentNode>
  dialect: string
  submit: (id: string, sql: string, description: string, dialect: string) => Promise<UserEditOutcome>
  record: (link: EditLink) => void
}

const NO_CHANGE = 'the edited text resolves to the very facet you are editing, so there is no ' +
  'other SQL to open. Nothing was saved and no tab was opened. ' +
  'Equivalent SQL text has the same fragment identity.'
const UNSUPPORTED = 'this fragment cannot be canonicalized, so it has no stable identity and nothing was saved. ' +
  'No tab was opened. Reason from the backend: '
const DEGRADED = 'the edit was saved as a new fragment and opened in a new tab. The fragment ' +
  'you edited carries a degraded identity (its id was derived from raw text), ' +
  'so an equivalent rewrite cannot be recognized as the same fragment.'

/** Owns the two independent edit stages. A saved result never re-enters submission. */
export function useFragmentEditFeedback(options: Options) {
  const latest = useRef(options)
  latest.current = options
  const busy = useRef(false)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  const [pending, setPending] = useState(false)

  const blockedReason = useCallback((recovery: Recovery): string | null => {
    const { context } = recovery
    const now = latest.current
    if (!now.tabs.tabs.some(t => t.sessionId === context.sourceSessionId)) {
      return 'The source tab has been closed. Dismiss this notice and start a new edit.'
    }
    // Opening an already returned result needs no current DAG; use its captured source.
    if (recovery.stage === 'open') return null
    if (now.tabs.activeSessionId !== context.sourceSessionId) return 'Return to the source tab to retry this edit.'
    if (now.tree !== context.sourceTree || !now.nodeMap.has(context.fragId) || now.dialect !== context.dialect) {
      return 'The source SQL context has changed. Dismiss this notice and start a new edit.'
    }
    return null
  }, [])

  const openCompleted = useCallback(async (context: EditContext, outcome: CompletedEdit, intent: TabCreationIntent): Promise<boolean> => {
    const recovery: Recovery = { stage: 'open', context, outcome, intent }
    const blocked = blockedReason(recovery)
    if (blocked) {
      if (mounted.current) setFeedback({ label: 'Edit completed; new tab could not be opened', message: blocked,
        severity: 'error', sourceSessionId: context.sourceSessionId })
      return false
    }
    const targetTabName = context.description.trim() || 'Edited fragment'
    let error = 'The request failed. The new tab could not be confirmed.'
    let terminal = false
    const targetSessionId = await latest.current.tabs.openInNewTab(outcome.sql, {
      name: targetTabName, dialect: context.dialect, intent, onError: (reason, code) => {
        error = reason
        terminal = code === 'CREATION_VALIDATION_ERROR' || code === 'CREATION_REQUEST_CONFLICT' || code === 'CREATION_TARGET_GONE'
      },
      editOrigin: { sourceNodeId: context.fragId, sourceSessionId: context.sourceSessionId,
        sourceTabName: context.sourceTabName, targetNodeId: outcome.nodeId },
      exitContinuation: true,
    })
    if (!mounted.current) return false
    if (!targetSessionId) {
      setFeedback({ label: 'Edit completed; new tab could not be opened',
        message: terminal ? error : `The edit result is available. Retry opening its tab. ${error}`,
        severity: 'error', sourceSessionId: context.sourceSessionId, ...(terminal ? {} : { recovery }) })
      return false
    }
    if (latest.current.tabs.tabs.some(t => t.sessionId === context.sourceSessionId)) {
      latest.current.record({ sourceNodeId: context.fragId, sourceSessionId: context.sourceSessionId,
        sourceTabName: context.sourceTabName, targetSessionId, targetTabName, targetNodeId: outcome.nodeId })
    } else {
      setFeedback({ label: 'Edited SQL opened in a new tab',
        message: 'The source tab was closed while the new tab was opening. Its saved edit origin is no longer available for navigation.',
        severity: 'warning', sourceSessionId: context.sourceSessionId })
      return true
    }
    setFeedback(outcome.sourceDegradedReason !== undefined ? {
      label: 'Edited SQL opened in a new tab',
      message: outcome.sourceDegradedReason ? `${DEGRADED} Reason: ${outcome.sourceDegradedReason}` : DEGRADED,
      severity: 'warning', sourceSessionId: context.sourceSessionId,
    } : null)
    return true
  }, [blockedReason])

  const submitContext = useCallback(async (context: EditContext): Promise<boolean> => {
    const recovery: Recovery = { stage: 'submit', context }
    const outcome = await latest.current.submit(context.fragId, context.newSql, context.description, context.dialect)
    if (!mounted.current) return false
    if (outcome.status === 'FAILED') {
      setFeedback({ label: 'Could not confirm the edit result', message: outcome.reason,
        severity: 'error', sourceSessionId: context.sourceSessionId, recovery })
      // Close the Radix scrim so the owned feedback/recovery is reachable.
      // The original payload remains in recovery, even when the request result is unknown.
      return true
    }
    if (outcome.status === 'EDIT') {
      const intent = createTabIntent(outcome.sql, { name: context.description.trim() || 'Edited fragment', dialect: context.dialect, connectionId: context.connectionId,
        editOrigin: { sourceNodeId: context.fragId, sourceSessionId: context.sourceSessionId,
          sourceTabName: context.sourceTabName, targetNodeId: outcome.nodeId } })
      // Switching/replacing the source while saving cannot silently attribute a new result to another tab.
      const blocked = blockedReason(recovery)
      if (blocked) {
        setFeedback({ label: 'Edit completed; new tab could not be opened', message: blocked,
          severity: 'error', sourceSessionId: context.sourceSessionId, recovery: { stage: 'open', context, outcome, intent } })
        return true
      }
      await openCompleted(context, outcome, intent)
      return true
    }
    setFeedback(outcome.status === 'NAVIGATE' ? null : {
      label: outcome.status === 'NO_CHANGE' ? 'No changes to open' : 'This SQL edit is not supported',
      message: outcome.status === 'NO_CHANGE' ? NO_CHANGE : `${UNSUPPORTED}${outcome.reason}`,
      severity: outcome.status === 'NO_CHANGE' ? 'info' : 'warning', sourceSessionId: context.sourceSessionId,
    })
    return true
  }, [blockedReason, openCompleted])

  const execute = useCallback(async (action: () => Promise<boolean>) => {
    if (busy.current) return false
    busy.current = true
    setPending(true)
    setFeedback(null)
    try { return await action() }
    finally { busy.current = false; if (mounted.current) setPending(false) }
  }, [])

  const save = useCallback(async (fragId: string | null, _originalSql: string, newSql: string, description: string) => {
    if (isWorkspaceExitLocked()) return false
    if (busy.current) return false
    const now = latest.current
    const sourceTab = now.tabs.tabs.find(t => t.sessionId === now.tabs.activeSessionId)
    if (!fragId || !sourceTab || !now.tree || !now.nodeMap.has(fragId)) {
      setFeedback({ label: 'Could not confirm the edit result', message: 'No valid source tab and SQL fragment. Return to the source and start a new edit.',
        severity: 'error', sourceSessionId: sourceTab?.sessionId ?? '' })
      return true
    }
    const context: EditContext = { sourceSessionId: sourceTab.sessionId, sourceTabName: sourceTab.name,
      sourceTree: now.tree, fragId, newSql, description, dialect: now.dialect, connectionId: now.tabs.activeWorkspace?.connectionId ?? null }
    return execute(() => submitContext(context))
  }, [execute, submitContext])

  const retry = useCallback(async () => {
    if (isWorkspaceExitLocked()) return
    if (!feedback?.recovery || busy.current) return
    const recovery = feedback.recovery
    const blocked = blockedReason(recovery)
    if (blocked) { setFeedback({ ...feedback, message: blocked }); return }
    // Keep the feedback visible during retries so its action reflects the pending state.
    busy.current = true
    setPending(true)
    try {
      if (recovery.stage === 'open') await openCompleted(recovery.context, recovery.outcome, recovery.intent)
      else await submitContext(recovery.context)
    } finally { busy.current = false; if (mounted.current) setPending(false) }
  }, [feedback, blockedReason, openCompleted, submitContext])

  return { feedback, pending, save, retry, dismiss: () => { if (!busy.current) setFeedback(null) },
    blocked: feedback?.recovery ? blockedReason(feedback.recovery) : null }
}
