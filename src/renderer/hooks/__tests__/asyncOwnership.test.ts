import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useExecution } from '../useExecution'
import { useDiagnostics } from '../useDiagnostics'
import { useRowTrace } from '../useRowTrace'
import { apiFetch } from '../../utils/api'

vi.mock('../../utils/api', () => ({ apiFetch: vi.fn() }))
const response = (body: unknown) => ({ ok: true, json: async () => body }) as Response
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
}
describe('async request ownership', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.mocked(apiFetch).mockReset() })
  afterEach(() => { vi.useRealTimers() })

  it('keeps a completed query with its submitting node after selection changes', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(response({ executionId: 'original' }))
      .mockResolvedValue(response({ status: 'COMPLETED', rows: [[11]], timing: { durationMs: 1 } }))
    const { result, rerender } = renderHook(({ node }) => useExecution('http://backend', node), { initialProps: { node: 'A' } })
    await act(async () => { await result.current.execute('SELECT 11', 'conn') })
    rerender({ node: 'B' })
    await act(async () => { await vi.advanceTimersByTimeAsync(500) })
    expect(result.current.dataResult).toBeNull()
    rerender({ node: 'A' })
    expect(result.current.dataResult?.rows).toEqual([[11]])
  })

  it('does not repopulate diagnostics after clear while analyze is pending', async () => {
    const pending = deferred<Response>()
    vi.mocked(apiFetch).mockReturnValue(pending.promise)
    const { result } = renderHook(() => useDiagnostics('http://backend'))
    let done!: Promise<void>
    act(() => { done = result.current.analyze({ id: 'A' }, {}, {}, 'session') })
    act(() => result.current.clear())
    await act(async () => { pending.resolve(response({ findings: [{ id: 'old' }] })); await done })
    expect(result.current.findings).toEqual([])
  })

  it('does not let an old trace poll finish a replacement trace', async () => {
    const oldPoll = deferred<Response>()
    let posts = 0
    let oldReads = 0
    vi.mocked(apiFetch).mockImplementation(async (url, init) => {
      if (init?.method === 'POST') return response({ traceId: ++posts === 1 ? 'old' : 'new' })
      if (init?.method === 'DELETE') return response({})
      if (String(url).endsWith('/old') && oldReads++ === 0) return oldPoll.promise
      return response({ status: 'CANCELLED', steps: [], totalElapsedMs: 0 })
    })
    const { result } = renderHook(() => useRowTrace('http://backend'))
    await act(async () => { await result.current.trace('conn', '{}', []) })
    await act(async () => { await vi.advanceTimersByTimeAsync(500) })
    act(() => result.current.clear())
    await act(async () => { await result.current.trace('conn', '{}', []) })
    await act(async () => { oldPoll.resolve(response({ status: 'COMPLETED', steps: [], totalElapsedMs: 1 })) })
    expect(result.current.traceId).toBe('new')
    expect(result.current.status).toBe('RUNNING')
  })

  it('isolates a late verification and deduplicates the same finding while pending', async () => {
    const old = deferred<Response>()
    vi.mocked(apiFetch).mockReturnValue(old.promise)
    const { result } = renderHook(() => useDiagnostics('http://backend'))
    let done!: Promise<void>
    act(() => { done = result.current.verify('finding', 'SELECT 11', 'conn'); void result.current.verify('finding', 'SELECT 11', 'conn') })
    expect(apiFetch).toHaveBeenCalledTimes(1)
    act(() => result.current.clear())
    await act(async () => { old.resolve(response({ status: 'CONFIRMED', resultSummary: 'old' })); await done })
    expect(result.current.findings).toEqual([])
    expect(result.current.verifyingIds.size).toBe(0)
  })
})
