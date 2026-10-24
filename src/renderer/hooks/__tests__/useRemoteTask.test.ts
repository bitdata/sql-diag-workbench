import { act, renderHook } from '@testing-library/react'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { useRemoteTask } from '../useRemoteTask'
import { apiFetch } from '../../utils/api'
vi.mock('../../utils/api', () => ({ apiFetch: vi.fn() }))
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body }) as Response
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }
const submit = (task: ReturnType<typeof useRemoteTask>, done = vi.fn()) => task.submit('/submit', '/tasks', 'id', {}, done)
describe('remote task lifecycle', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.mocked(apiFetch).mockReset() })
  afterEach(() => vi.useRealTimers())
  it('recognizes only the exact pre-admission analyze rejection', async () => {
    const envelope = { code: 'ANALYZE_VALIDATION_ERROR', errorReason: 'INVALID_ANALYZE_MODE', message: 'Invalid mode' }
    for (const [path, code, reason, expected] of [
      ['/api/execution/analyze', envelope.code, envelope.errorReason, 'SUBMISSION_REJECTED'],
      ['/submit', envelope.code, envelope.errorReason, 'SUBMISSION_UNCONFIRMED'],
      ['/api/execution/analyze', 'UNKNOWN', envelope.errorReason, 'SUBMISSION_UNCONFIRMED'],
      ['/api/execution/analyze', envelope.code, 'UNKNOWN', 'SUBMISSION_UNCONFIRMED'],
    ]) {
      vi.mocked(apiFetch).mockResolvedValue(response({ ...envelope, code, errorReason: reason }, 400))
      const { result, unmount } = renderHook(() => useRemoteTask('http://backend'))
      await act(async () => { await result.current.submit(path, '/tasks', 'id', {}, vi.fn()) })
      expect(result.current.errorReason).toBe(expected)
      expect(result.current.id).toBeNull()
      unmount()
    }
  })
  it('cleans up a definite capacity rejection without polling or deleting an unknown task', async () => {
    vi.mocked(apiFetch).mockResolvedValue(response({ code: 'EXECUTION_CAPACITY_EXHAUSTED', message: 'Capacity full' }, 429))
    const { result, unmount } = renderHook(() => useRemoteTask('http://backend'))
    await act(async () => { await submit(result.current) })
    expect(result.current.status).toBe('FAILED')
    expect(result.current.errorReason).toBe('SUBMISSION_REJECTED')
    expect(result.current.id).toBeNull()
    await act(async () => { await result.current.cancel() })
    expect(result.current.errorReason).toBe('SUBMISSION_REJECTED')
    await act(async () => { result.current.reset(); await vi.advanceTimersByTimeAsync(2000) })
    unmount()
    expect(apiFetch).toHaveBeenCalledTimes(1)
  })
  it('deduplicates synchronous submissions and cleans up a late receipt after cancellation', async () => {
    const receipt = deferred<Response>(); const done = vi.fn()
    vi.mocked(apiFetch).mockImplementation(async (_url, init) => init?.method === 'POST' ? receipt.promise : response({ status: 'CANCELLED' }))
    const { result } = renderHook(() => useRemoteTask('http://backend'))
    let pending!: Promise<void>
    act(() => { pending = submit(result.current, done); void submit(result.current, done) })
    expect(apiFetch).toHaveBeenCalledTimes(1)
    await act(async () => { await result.current.cancel() })
    expect(result.current.errorReason).toBe('CANCEL_UNCONFIRMED')
    await act(async () => { receipt.resolve(response({ id: 'late' })); await pending })
    expect(apiFetch).toHaveBeenCalledWith('http://backend/tasks/late', { method: 'DELETE' })
    expect(result.current.status).toBe('CANCELLED')
    expect(done).toHaveBeenCalledTimes(1)
  })
  it('cleans up a pending submission after unmount without publishing data', async () => {
    const receipt = deferred<Response>(); const done = vi.fn()
    vi.mocked(apiFetch).mockImplementation(async (_url, init) => init?.method === 'POST' ? receipt.promise : response({ status: 'CANCELLED' }))
    const { result, unmount } = renderHook(() => useRemoteTask('http://backend'))
    let pending!: Promise<void>; act(() => { pending = submit(result.current, done) })
    unmount()
    await act(async () => { receipt.resolve(response({ id: 'detached' })); await pending })
    expect(apiFetch).toHaveBeenCalledWith('http://backend/tasks/detached', { method: 'DELETE' })
    expect(done).not.toHaveBeenCalled()
  })
  it('stops failed polling and resumes only on explicit retry', async () => {
    let reads = 0
    vi.mocked(apiFetch).mockImplementation(async (_url, init) => init?.method === 'POST' ? response({ id: 'one' }) :
      ++reads === 1 ? response({}, 503) : response({ status: 'COMPLETED' }))
    const done = vi.fn(); const { result } = renderHook(() => useRemoteTask('http://backend'))
    await act(async () => { await submit(result.current, done); await vi.advanceTimersByTimeAsync(1000) })
    expect(result.current.errorReason).toBe('STATUS_UNAVAILABLE'); expect(reads).toBe(1)
    await act(async () => { result.current.retryStatus() })
    expect(result.current.status).toBe('COMPLETED'); expect(done).toHaveBeenCalledTimes(1)
  })
  it('invalidates old polling when the session changes', async () => {
    const old = deferred<Response>(); let reads = 0
    vi.mocked(apiFetch).mockImplementation(async (_url, init) => init?.method === 'POST' ? response({ id: 'old' }) :
      init?.method === 'DELETE' ? response({}) : ++reads === 1 ? old.promise : response({ status: 'CANCELLED' }))
    const done = vi.fn(); const { result, rerender } = renderHook(({ scope }) => useRemoteTask('http://backend', scope), { initialProps: { scope: 'first' } })
    await act(async () => { await submit(result.current, done); await vi.advanceTimersByTimeAsync(500) })
    rerender({ scope: 'second' })
    await act(async () => { old.resolve(response({ status: 'COMPLETED' })) })
    expect(done).not.toHaveBeenCalled(); expect(result.current.status).toBeNull()
  })
  it('reports unconfirmed cancellation instead of claiming the server stopped', async () => {
    vi.mocked(apiFetch).mockImplementation(async (_url, init) => init?.method === 'POST' ? response({ id: 'one' }) : response({}, 503))
    const { result } = renderHook(() => useRemoteTask('http://backend'))
    await act(async () => { await submit(result.current); await result.current.cancel() })
    expect(result.current.errorReason).toBe('CANCEL_UNCONFIRMED')
    expect(result.current.status).not.toBe('CANCELLED')
  })
})
