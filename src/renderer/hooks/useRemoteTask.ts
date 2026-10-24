import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import { apiFetch } from '../utils/api'
import { dismissTaskNotice, reportTaskNotice } from '../utils/taskNotices'

interface TaskData { status: string; error?: string | null }
interface Run<T> {
  url: string; id: string | null; pending: boolean; stopped: boolean; terminal: boolean
  observation: number; pollBusy: boolean; timer: ReturnType<typeof setInterval> | null
  onData: (data: T) => void
  notice?: number; cleanupFlight?: Promise<void>
}
const terminal = new Set(['COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED'])
const statuses = new Set(['QUEUED', 'RUNNING', ...terminal])
const message = (e: unknown) => e instanceof Error ? e.message : String(e)
const idle = () => ({ id: null as string | null, status: null as string | null, loading: false,
  submitting: false, cancelling: false, error: null as string | null, errorReason: null as string | null,
  startedAt: null as number | null })

/** One task per instance. Every continuation is bound to its run and observation. */
export function useRemoteTask<T extends TaskData>(backendUrl: string | null, scope = '') {
  const [state, setState] = useState(idle)
  const current = useRef<Run<T> | null>(null)
  const mounted = useRef(true)
  const stop = (run: Run<T>) => {
    if (run.timer !== null) clearInterval(run.timer)
    run.timer = null
    run.observation++
  }
  const live = (run: Run<T>, observation: number) => mounted.current && current.current === run && run.observation === observation
  const read = async (run: Run<T>): Promise<T> => {
    const res = await apiFetch(`${run.url}/${encodeURIComponent(run.id!)}`)
    if (!res.ok) throw new Error(`Status request failed (HTTP ${res.status})`)
    const data = await res.json() as T
    if (!data || !statuses.has(data.status)) throw new Error('Invalid task status response')
    return data
  }
  const cleanup = async (run: Run<T>, show = false) => {
    if (run.cleanupFlight) return run.cleanupFlight
    stop(run)
    run.stopped = true
    const observation = run.observation
    if (run.terminal) return
    if (!run.id) {
      if (run.pending && !show && !run.notice) run.notice = reportTaskNotice('SUBMISSION_UNCONFIRMED',
        'A submitted query is awaiting its request ID. It will be cancelled when the ID arrives.')
      if (show && live(run, observation)) setState(s => ({ ...s, loading: false, cancelling: false,
        error: 'Cancellation is not confirmed. Waiting for the submitted request ID.', errorReason: 'CANCEL_UNCONFIRMED' }))
      return
    }
    if (show && live(run, observation)) setState(s => ({ ...s, cancelling: true }))
    run.cleanupFlight = (async () => {
    try {
      const deleted = await apiFetch(`${run.url}/${encodeURIComponent(run.id!)}`, { method: 'DELETE' })
      if (!deleted.ok && deleted.status !== 404) throw new Error(`Cancel request failed (HTTP ${deleted.status})`)
      const data = await read(run)
      if (!terminal.has(data.status)) throw new Error('The server has not confirmed cancellation; the query may still be running.')
      run.terminal = true
      if (run.notice) dismissTaskNotice(run.notice)
      if (show && live(run, observation)) {
        setState(s => ({ ...s, id: run.id, status: data.status, loading: false, submitting: false,
          cancelling: false, error: data.error ?? null, errorReason: null, startedAt: null }))
        run.onData(data)
      }
    } catch (e) {
      const error = `Cancellation is not confirmed: ${message(e)}`
      if (show && live(run, observation)) setState(s => ({ ...s, loading: false, cancelling: false,
        error, errorReason: 'CANCEL_UNCONFIRMED', startedAt: null }))
      else reportTaskNotice('CANCEL_UNCONFIRMED', error)
    }
    })()
    await run.cleanupFlight
    run.cleanupFlight = undefined
  }
  const cleanupRef = useRef(cleanup)
  cleanupRef.current = cleanup
  const reset = useCallback(() => {
    const run = current.current
    current.current = null
    if (run) void cleanupRef.current(run)
    setState(idle())
  }, [])
  useLayoutEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      const run = current.current
      current.current = null
      if (run) void cleanupRef.current(run)
    }
  }, [])
  useLayoutEffect(() => { reset() }, [backendUrl, scope, reset])

  const poll = async (run: Run<T>) => {
    if (run.pollBusy || run.stopped || run.terminal) return
    run.pollBusy = true
    const observation = run.observation
    try {
      const data = await read(run)
      if (!live(run, observation)) return
      if (terminal.has(data.status)) { run.terminal = true; stop(run) }
      setState(s => ({ ...s, status: data.status, loading: !run.terminal,
        error: data.error ?? null, errorReason: null, startedAt: run.terminal ? null : s.startedAt }))
      run.onData(data)
    } catch (e) {
      if (!live(run, observation)) return
      stop(run)
      setState(s => ({ ...s, loading: false, error: message(e), errorReason: 'STATUS_UNAVAILABLE' }))
    } finally { run.pollBusy = false }
  }
  const pollRef = useRef(poll)
  pollRef.current = poll
  const submit = useCallback(async (path: string, statusPath: string, idKey: string, body: unknown, onData: (data: T) => void) => {
    if (!backendUrl) { setState(s => ({ ...s, error: 'Backend not connected', errorReason: 'BACKEND_UNAVAILABLE' })); return }
    if (current.current?.pending) return
    const previous = current.current
    if (previous) void cleanupRef.current(previous)
    const run: Run<T> = { url: backendUrl + statusPath, id: null, pending: true, stopped: false,
      terminal: false, observation: 0, pollBusy: false, timer: null, onData }
    current.current = run
    setState({ ...idle(), status: 'QUEUED', loading: true, submitting: true, startedAt: Date.now() })
    try {
      // Retain the receipt if the view closes during POST. Socket abort cannot cancel server work.
      const res = await apiFetch(backendUrl + path, { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body) }, { timeoutMs: null })
      const data = await res.json()
      if ((res.status === 429 && data?.code === 'EXECUTION_CAPACITY_EXHAUSTED') ||
          (path === '/api/execution/analyze' && res.status === 400 && data?.code === 'ANALYZE_VALIDATION_ERROR' && data?.errorReason === 'INVALID_ANALYZE_MODE') ||
          (path === '/api/diagnostics/trace-async' && res.status === 400 && data?.code === 'TRACE_VALIDATION_ERROR')) {
        run.pending = false; run.stopped = true; run.terminal = true
        if (run.notice) dismissTaskNotice(run.notice)
        if (mounted.current && current.current === run) setState(s => ({ ...s, status: 'FAILED', loading: false,
          submitting: false, cancelling: false, startedAt: null, error: data.message ?? 'Result retention capacity is full. Please submit again later.',
          errorReason: 'SUBMISSION_REJECTED' }))
        return
      }
      if (!res.ok) throw new Error(data?.message ?? `Submission failed (HTTP ${res.status})`)
      if (typeof data?.[idKey] !== 'string' || !data[idKey]) throw new Error('Submission response has no task ID')
      run.id = data[idKey]
      run.pending = false
      if (run.stopped || current.current !== run || !mounted.current) {
        await cleanupRef.current(run, mounted.current && current.current === run)
        return
      }
      setState(s => ({ ...s, id: run.id, submitting: false }))
      run.timer = setInterval(() => { void pollRef.current(run) }, 500)
    } catch (e) {
      run.pending = false
      if (run.notice) dismissTaskNotice(run.notice)
      if (mounted.current && current.current === run) setState(s => ({ ...s, status: 'FAILED', loading: false,
        submitting: false, startedAt: null, error: message(e), errorReason: 'SUBMISSION_UNCONFIRMED' }))
      else reportTaskNotice('SUBMISSION_UNCONFIRMED', `The submitted request could not be confirmed: ${message(e)}`)
    }
  }, [backendUrl])
  const cancel = useCallback(async () => {
    const run = current.current
    if (run) await cleanupRef.current(run, true)
  }, [])
  const retryStatus = useCallback(() => {
    const run = current.current
    if (!run?.id || run.pending || run.terminal) return
    stop(run); run.stopped = false; run.pollBusy = false
    setState(s => ({ ...s, loading: true, error: null, errorReason: null }))
    run.timer = setInterval(() => { void pollRef.current(run) }, 500)
    void pollRef.current(run)
  }, [])
  return { ...state, submit, cancel, reset, retryStatus }
}
