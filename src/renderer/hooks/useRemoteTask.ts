import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import { apiFetch } from '../utils/api'
import { dismissTaskNotice, reportTaskNotice } from '../utils/taskNotices'

interface Lifecycle { version: 1; revision: number; settled: boolean; cancelSignal: string; cleanup: string; errorReason?: string | null }
interface TaskData { status: string; error?: string | null; errorReason?: string | null; lifecycle?: Lifecycle }

interface Run<T> {
  url: string; id: string | null; pending: boolean; stopped: boolean; terminal: boolean
  observation: number; pollBusy: boolean; timer: ReturnType<typeof setInterval> | null
  onData: (data: T) => void
  notice?: number; cleanupFlight?: Promise<void>
  observeCleanup: boolean; resultDelivered?: boolean; lastRevision?: number; lastLifecycleSignature?: string; cleanupSettled?: boolean
  deadlineAt?: number; deadlineTimer?: ReturnType<typeof setTimeout>
}
const terminal = new Set(['COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED'])
const statuses = new Set(['QUEUED', 'RUNNING', ...terminal])
const message = (e: unknown) => e instanceof Error ? e.message : String(e)
const idle = () => ({ id: null as string | null, status: null as string | null, loading: false,
  submitting: false, cancelling: false, error: null as string | null, errorReason: null as string | null,
  startedAt: null as number | null, cleanupPending: false, lifecycle: null as Lifecycle | null })

/** One task per instance. Every continuation is bound to its run and observation. */
export function useRemoteTask<T extends TaskData>(backendUrl: string | null, scope = '', decode?: (value: unknown) => T, observeCleanup: boolean | ((path: string) => boolean) = false) {
  const [state, setState] = useState(idle)
  const current = useRef<Run<T> | null>(null)
  const mounted = useRef(true)
  const stop = (run: Run<T>) => {
    if (run.timer !== null) clearInterval(run.timer)
    run.timer = null
    run.observation++
  }
  const live = (run: Run<T>, observation: number) => mounted.current && current.current === run && run.observation === observation
  const finishBudget = (run: Run<T>) => { clearTimeout(run.deadlineTimer); run.deadlineTimer = undefined; run.deadlineAt = undefined }
  const expireCleanup = (run: Run<T>) => {
    stop(run); run.stopped = true; finishBudget(run)
    const error = 'Cancellation or cleanup is not confirmed. Check status explicitly; database work may still be active.'
    if (mounted.current && current.current === run) setState(s => ({ ...s, loading: false, cancelling: false, cleanupPending: false,
      error, errorReason: 'RESOURCE_CLEANUP_UNCONFIRMED', startedAt: null }))
    else reportTaskNotice('RESOURCE_CLEANUP_UNCONFIRMED', error)
  }
  const beginBudget = (run: Run<T>) => {
    if (run.deadlineAt !== undefined) return
    run.deadlineAt = Date.now() + 5000
    run.deadlineTimer = setTimeout(() => expireCleanup(run), 5000)
  }
  const budgeted = async <R,>(run: Run<T>, action: () => Promise<R>): Promise<R> => {
    if (run.deadlineAt === undefined) return action()
    let timer: ReturnType<typeof setTimeout> | undefined
    try { return await Promise.race([action(), new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error('Cleanup observation deadline exceeded')), Math.max(0, run.deadlineAt! - Date.now()))
    })]) } finally { clearTimeout(timer) }
  }
  const acceptLifecycle = (run: Run<T>, data: T) => {
    const lc = data.lifecycle
    if (!lc || lc.version !== 1 || !Number.isSafeInteger(lc.revision) || lc.revision < 0 || typeof lc.settled !== 'boolean'
      || !['NOT_REQUESTED','NOT_REQUIRED','PENDING','SENT','FAILED','REJECTED'].includes(lc.cancelSignal)
      || !['NOT_STARTED','PENDING','NO_RESOURCES','CLOSED','UNCONFIRMED'].includes(lc.cleanup)
      || (lc.errorReason != null && typeof lc.errorReason !== 'string')
      || (lc.settled && (!terminal.has(data.status) || !['CLOSED','NO_RESOURCES'].includes(lc.cleanup) || lc.cancelSignal === 'PENDING'))) throw new Error('Invalid cleanup lifecycle response')
    if (run.lastRevision !== undefined && lc.revision < run.lastRevision) return false
    const signature = JSON.stringify([data.status, lc.settled, lc.cancelSignal, lc.cleanup, lc.errorReason, data.errorReason])
    if (lc.revision === run.lastRevision && signature !== run.lastLifecycleSignature) throw new Error('Conflicting cleanup lifecycle revision')
    run.lastRevision = lc.revision
    run.lastLifecycleSignature = signature
    run.cleanupSettled = lc.settled
    const done = terminal.has(data.status)
    if (done) run.terminal = true
    const pending = done && !lc.settled
    if (pending) beginBudget(run)
    if (lc.settled) { finishBudget(run); stop(run); run.stopped = true }
    setState(s => ({ ...s, id: run.id, status: data.status, submitting: false, loading: !done, cancelling: false,
      cleanupPending: pending, lifecycle: lc, error: data.error ?? null,
      errorReason: data.errorReason ?? lc.errorReason ?? (pending ? 'RESOURCE_CLEANUP_PENDING' : null), startedAt: done ? null : s.startedAt }))
    if (!done || !run.resultDelivered) { run.onData(data); if (done) run.resultDelivered = true }
    return true
  }
  const read = async (run: Run<T>): Promise<T> => {
    const res = await apiFetch(`${run.url}/${encodeURIComponent(run.id!)}`)
    if (!res.ok) { const error = new Error(`Status request failed (HTTP ${res.status})`); if (res.status === 410 && /\/api\/optimization\/(validations|experiments)$/.test(run.url)) Object.assign(error, { taskReason: 'TASK_EXPIRED' }); throw error }
    const raw: unknown = await res.json()
    const data = decode ? decode(raw) : raw as T
    if (!data || !statuses.has(data.status)) throw new Error('Invalid task status response')
    return data
  }
  const cleanup = async (run: Run<T>, show = false) => {
    if (run.cleanupFlight) return run.cleanupFlight
    if (run.observeCleanup) return cleanupObserved(run, show)
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
  const cleanupObserved = async (run: Run<T>, show: boolean) => {
    if (run.cleanupSettled) { stop(run); finishBudget(run); return }
    if (!run.id) {
      run.stopped = true
      if (run.pending && !run.notice) run.notice = reportTaskNotice('SUBMISSION_UNCONFIRMED', 'Waiting for the submitted task ID before cancellation.')
      if (show && mounted.current && current.current === run) setState(s => ({ ...s, loading: false, cancelling: false, errorReason: 'CANCEL_UNCONFIRMED' }))
      return
    }
    stop(run); run.stopped = false; beginBudget(run)
    const observation = run.observation
    if (show && live(run, observation)) setState(s => ({ ...s, cancelling: true }))
    run.cleanupFlight = budgeted(run, async () => {
      // Query outcome already known: only observe cleanup; never issue a second cancel.
      if (!run.terminal) {
        const deleted = await apiFetch(`${run.url}/${encodeURIComponent(run.id!)}`, { method: 'DELETE' }, { timeoutMs: 5000 })
        if (!deleted.ok && deleted.status !== 404) throw new Error(`Cancel request failed (HTTP ${deleted.status})`)
      }
      if (!live(run, observation)) return
      const data = await read(run)
      if (!live(run, observation)) return
      if (show) acceptLifecycle(run, data)
      else { reportTaskNotice('RESOURCE_CLEANUP_UNCONFIRMED', 'The view closed before cleanup was confirmed.'); stop(run); run.stopped = true; finishBudget(run); return }
      if (!run.cleanupSettled) run.timer = setInterval(() => { void pollRef.current(run) }, 500)
    }).catch(e => {
      if (!live(run, observation)) return
      stop(run); run.stopped = true; finishBudget(run)
      if (show && mounted.current && current.current === run) setState(s => ({ ...s, loading: false, cancelling: false, cleanupPending: false,
        error: message(e), errorReason: 'RESOURCE_CLEANUP_UNCONFIRMED' }))
      else reportTaskNotice('RESOURCE_CLEANUP_UNCONFIRMED', message(e))
    })
    try { await run.cleanupFlight } finally { run.cleanupFlight = undefined }
  }
  const cleanupRef = useRef(cleanup)
  cleanupRef.current = cleanup
  const reset = useCallback(() => {
    const run = current.current
    current.current = null
    if (run) { finishBudget(run); void cleanupRef.current(run) }
    setState(idle())
  }, [])
  useLayoutEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      const run = current.current
      current.current = null
      if (run) { finishBudget(run); void cleanupRef.current(run) }
    }
  }, [])
  useLayoutEffect(() => { reset() }, [backendUrl, scope, reset])

  const poll = async (run: Run<T>) => {
    if (run.pollBusy || run.stopped || (run.terminal && (!run.observeCleanup || run.cleanupSettled))) return
    run.pollBusy = true
    const observation = run.observation
    try {
      const data = await budgeted(run, () => read(run))
      if (!live(run, observation)) return
      if (run.observeCleanup) { acceptLifecycle(run, data); return }
      if (terminal.has(data.status)) { run.terminal = true; stop(run) }
      setState(s => ({ ...s, status: data.status, loading: !run.terminal,
        error: data.error ?? null, errorReason: null, startedAt: run.terminal ? null : s.startedAt }))
      run.onData(data)
    } catch (e) {
      if (!live(run, observation)) return
      stop(run); finishBudget(run)
      setState(s => ({ ...s, loading: false, cleanupPending: false, error: message(e), errorReason: (e as { taskReason?: string })?.taskReason ?? 'STATUS_UNAVAILABLE' }))
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
      terminal: false, observation: 0, pollBusy: false, timer: null, onData,
      observeCleanup: (typeof observeCleanup === 'function' ? observeCleanup(path) : observeCleanup) && ['/api/execution/execute', '/api/execution/analyze', '/api/diagnostics/trace-async', '/api/diagnostics/source-preparations'].includes(path) }
    current.current = run
    setState({ ...idle(), status: 'QUEUED', loading: true, submitting: true, startedAt: Date.now() })
    try {
      // Retain the receipt if the view closes during POST. Socket abort cannot cancel server work.
      const res = await apiFetch(backendUrl + path, { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body) }, { timeoutMs: null })
      const data = await res.json()
      if ((res.status === 503 && ['/api/execution/execute','/api/execution/analyze'].includes(path) && data?.code === 'EXECUTION_SHUTTING_DOWN' && data?.errorReason === 'APPLICATION_SHUTDOWN') ||
          (res.status === 429 && data?.code === 'EXECUTION_CAPACITY_EXHAUSTED') ||
          (path === '/api/execution/analyze' && res.status === 400 && data?.code === 'ANALYZE_VALIDATION_ERROR' && data?.errorReason === 'INVALID_ANALYZE_MODE') ||
          (path === '/api/optimization/validations' && ((res.status === 400 && data?.code === 'OPTIMIZATION_VALIDATION_ERROR') || (res.status === 409 && data?.code === 'OPTIMIZATION_SOURCE_CHANGED'))) ||
          (path === '/api/optimization/experiments' && ((res.status === 400 && data?.code === 'OPTIMIZATION_VALIDATION_ERROR') || (res.status === 409 && ['OPTIMIZATION_SOURCE_CHANGED','EXPERIMENT_BUSY'].includes(data?.code)))) ||
          (['/api/diagnostics/trace-async','/api/diagnostics/source-preparations'].includes(path) && [400,404,409,410,422,429,503].includes(res.status) && typeof data?.errorReason === 'string')) {
        run.pending = false; run.stopped = true; run.terminal = true
        if (run.notice) dismissTaskNotice(run.notice)
        if (mounted.current && current.current === run) setState(s => ({ ...s, status: 'FAILED', loading: false,
          submitting: false, cancelling: false, startedAt: null, error: data.message ?? 'Result retention capacity is full. Please submit again later.',
          errorReason: ['/api/diagnostics/trace-async','/api/diagnostics/source-preparations'].includes(path) || data?.code === 'EXECUTION_SHUTTING_DOWN' ? data.errorReason : 'SUBMISSION_REJECTED' }))
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
  }, [backendUrl, observeCleanup])
  const cancel = useCallback(async () => {
    const run = current.current
    if (run) await cleanupRef.current(run, true)
  }, [])
  const retryStatus = useCallback(() => {
    const run = current.current
    if (!run?.id || run.pending || (run.terminal && (!run.observeCleanup || run.cleanupSettled))) return
    stop(run); finishBudget(run); run.stopped = false; run.pollBusy = false
    if (run.observeCleanup) beginBudget(run)
    setState(s => ({ ...s, loading: true, error: null, errorReason: null }))
    run.timer = setInterval(() => { void pollRef.current(run) }, 500)
    void pollRef.current(run)
  }, [])
  return { ...state, submit, cancel, reset, retryStatus }
}
