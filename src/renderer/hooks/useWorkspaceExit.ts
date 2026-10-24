import { useCallback, useEffect, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import type { UseTabsApi } from './useTabs'
import type { ExitIssue, ExitResult, ExitDecision } from '../types/workspaceExit'
import { setWorkspaceExitLocked } from '../utils/workspaceExitGate'

export function useWorkspaceExit(options: { tabs: UseTabsApi; checkpoint: () => void;
  pending: boolean; blockers: () => string[]; timeoutMs?: number }) {
  const latest = useRef(options)
  latest.current = options
  const attempt = useRef<{ id: string; abort: AbortController } | null>(null)
  const [state, setState] = useState<{ attemptId: string; result?: ExitResult } | null>(null)
  const [, commit] = useState(0)
  const focus = useRef<HTMLElement | null>(null)
  const clear = useCallback(() => {
    attempt.current?.abort.abort()
    attempt.current = null
    setWorkspaceExitLocked(false)
    setState(null)
    setTimeout(() => { if (focus.current?.isConnected) focus.current.focus() }, 0)
  }, [])
  const decide = useCallback((decision: ExitDecision) => {
    const id = attempt.current?.id
    if (!id) return
    window.electronAPI?.decideWorkspaceExit?.(id, decision)
    if (decision !== 'discard') clear()
  }, [clear])
  useEffect(() => {
    const api = window.electronAPI
    if (!api?.onWorkspaceExit) return
    const unsubscribe = api.onWorkspaceExit(request => {
      if (request.cancelled) { if (attempt.current?.id === request.attemptId) clear(); return }
      if (attempt.current) return
      const current = { id: request.attemptId, abort: new AbortController() }
      attempt.current = current
      focus.current = document.activeElement as HTMLElement | null
      setWorkspaceExitLocked(true)
      flushSync(() => setState({ attemptId: current.id }))
      const report = (result: ExitResult) => {
        if (attempt.current !== current) return
        current.abort.abort() // Also stops an expired drain after its current write finishes.
        const bounded = { ...result, issues: result.issues.slice(0, 1000).map(i => ({ ...i,
          message: i.message.slice(0, 2000), tabName: i.tabName?.slice(0, 500) })) }
        setState({ attemptId: current.id, result: bounded })
        api.reportWorkspaceExit?.(bounded)
      }
      const issues = (code: string, message: string): ExitIssue[] => [{ code, message }]
      const timer = setTimeout(() => {
        const partial = latest.current.tabs.getExitSaveReport?.() ?? { savedSessionIds: [], issues: [] }
        report({ attemptId: current.id, status: 'timed-out', savedSessionIds: partial.savedSessionIds,
          issues: [...issues('EXIT_TIMEOUT', 'Saving could not be confirmed within 30 seconds. Pending writes may still complete.'), ...partial.issues] })
      }, latest.current.timeoutMs ?? 30000)
      void (async () => {
        // Explicit React commit barrier, including pending lifecycle projection effects.
        do {
          while (latest.current.pending || latest.current.tabs.getExitState?.().pending) {
            await new Promise(resolve => setTimeout(resolve, 20))
            if (current.abort.signal.aborted) return
          }
          await new Promise(resolve => setTimeout(resolve, 0))
          if (current.abort.signal.aborted) return
          flushSync(() => commit(v => v + 1))
        } while (latest.current.pending || latest.current.tabs.getExitState?.().pending)
        const now = latest.current
        const blockers = now.blockers()
        const error = now.tabs.getExitState?.().error
        if (error) blockers.push(error)
        if (blockers.length) {
          report({ attemptId: current.id, status: 'blocked', savedSessionIds: [],
            issues: blockers.map(message => ({ code: 'UNFINISHED_OPERATION', message })) })
          return
        }
        flushSync(() => latest.current.checkpoint())
        await new Promise(resolve => setTimeout(resolve, 0))
        flushSync(() => commit(v => v + 1))
        if (current.abort.signal.aborted) return
        const flush = latest.current.tabs.flushWorkspaces
        if (!flush) throw new Error('Workspace saving is unavailable.')
        const result = await flush(current.abort.signal)
        if (current.abort.signal.aborted) return
        report({ attemptId: current.id, status: result.issues.length ? 'failed' : 'saved', ...result })
      })().catch(e => {
        if (!current.abort.signal.aborted) report({ attemptId: current.id, status: 'failed', savedSessionIds: [],
          issues: issues('EXIT_FAILED', e instanceof Error ? e.message : String(e)) })
      }).finally(() => clearTimeout(timer))
    })
    return () => { unsubscribe(); attempt.current?.abort.abort(); attempt.current = null; setWorkspaceExitLocked(false) }
  }, [clear])
  useEffect(() => {
    if (!state) return
    const block = (event: Event) => {
      if ((event.target as Element | null)?.closest?.('[data-workspace-exit]')) return
      if (event instanceof KeyboardEvent && event.type === 'keydown' && event.key === 'Escape') decide('cancel')
      event.preventDefault()
      event.stopImmediatePropagation()
    }
    const events = ['pointerdown', 'click', 'keydown', 'beforeinput', 'input', 'change', 'contextmenu', 'submit']
    for (const event of events) window.addEventListener(event, block, true)
    return () => { for (const event of events) window.removeEventListener(event, block, true) }
  }, [state, decide])
  return { state, locked: !!state, retry: () => decide('retry'), cancel: () => decide('cancel'), discard: () => decide('discard') }
}
