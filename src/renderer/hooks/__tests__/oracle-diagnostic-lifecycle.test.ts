import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useRowTrace } from '../useRowTrace'
import { useSourcePreparation } from '../useSourcePreparation'
import { apiFetch } from '../../utils/api'
vi.mock('../../utils/api', () => ({ apiFetch: vi.fn() }))
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body }) as Response
const lifecycle = (revision: number, settled = false) => ({ version: 1, revision, settled, cancelSignal: 'NOT_REQUIRED', cleanup: settled ? 'CLOSED' : 'PENDING',
  errorReason: null, degraded: false, resourceObservation: {}, serverExecution: 'UNKNOWN', applicationCleanup: 'UNCONFIRMED' })
const progress = (revision: number, settled = false) => ({ traceId: 'trace', status: 'COMPLETED', steps: [], findings: [], lifecycle: lifecycle(revision, settled), error: null, errorReason: null })
beforeEach(() => { vi.useFakeTimers(); vi.mocked(apiFetch).mockReset() })
afterEach(() => vi.useRealTimers())
const step = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms) })

it('Oracle trace keeps terminal facts while cleanup expires and explicit GET recovers without POST or DELETE', async () => {
  let reads = 0, recovered = false
  vi.mocked(apiFetch).mockImplementation(async (_url, init) => init?.method === 'POST' ? response({ traceId: 'trace' }) : response(progress(++reads, recovered)))
  const { result, unmount } = renderHook(() => useRowTrace('http://synthetic', 'one', undefined, 'oracle'))
  await act(async () => { await result.current.trace('c', '{}', [{ column: 'K', type: 'DECIMAL', value: '9007199254740993' }]) })
  await step(5500)
  expect(result.current.status).toBe('COMPLETED'); expect(result.current.errorReason).toBe('RESOURCE_CLEANUP_UNCONFIRMED'); expect(result.current.blocked).toBe(true)
  const stopped = reads; await step(10000); expect(reads).toBe(stopped)
  recovered = true
  await act(async () => { result.current.retryStatus?.() })
  expect(result.current.lifecycle?.settled).toBe(true); expect(result.current.blocked).toBe(false)
  expect(vi.mocked(apiFetch).mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  expect(vi.mocked(apiFetch).mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(0)
  expect(result.current.submittedKeys?.[0].value).toBe('9007199254740993')
  unmount()
})
it('Oracle trace refuses malformed and missing resource evidence', async () => {
  vi.mocked(apiFetch).mockImplementation(async (_url, init) => init?.method === 'POST' ? response({ traceId: 'trace' }) : response({ ...progress(1, true), lifecycle: { ...lifecycle(1, true), resourceObservation: null } }))
  const { result, unmount } = renderHook(() => useRowTrace('http://synthetic', '', undefined, 'oracle'))
  await act(async () => { await result.current.trace('c', '{}', [{ column: 'K', type: 'DECIMAL', value: '1' }]) }); await step(500)
  expect(result.current.errorReason).toBe('STATUS_UNAVAILABLE'); expect(result.current.steps).toEqual([]); expect(result.current.lifecycle).toBeNull()
  unmount()
})
it('preparation cancellation before its ID arrives uses the owned source endpoint and cannot accept a late plan', async () => {
  let finish!: (value: Response) => void
  vi.mocked(apiFetch).mockImplementation(async (_url, init) => init?.method === 'POST' ? new Promise<Response>(resolve => { finish = resolve }) : init?.method === 'DELETE' ? response({ cancelled: true }) :
    response({ preparationId: 'prep', kind: 'COMPLEX', status: 'CANCELLED', errorReason: 'SOURCE_PREPARATION_CANCELLED', plan: null, lifecycle: lifecycle(1, true) }))
  const { result, unmount } = renderHook(() => useSourcePreparation('http://synthetic', 'one'))
  const seen = vi.fn(); let submitted!: Promise<void>
  act(() => { submitted = result.current.start('COMPLEX', {}, seen) })
  await act(async () => { await result.current.cancel() })
  await act(async () => { finish(response({ preparationId: 'prep' }, 202)); await submitted })
  expect(vi.mocked(apiFetch).mock.calls.some(([url, init]) => String(url).endsWith('/source-preparations/prep') && init?.method === 'DELETE')).toBe(true)
  expect(seen).not.toHaveBeenCalled(); expect(result.current.status).toBe('CANCELLED'); expect(result.current.lifecycle?.settled).toBe(true)
  unmount()
})
it('preparation resets isolate the old scope and reject stale completed responses', async () => {
  let finish!: (value: Response) => void
  vi.mocked(apiFetch).mockImplementation(async (_url, init) => init?.method === 'POST' ? response({ preparationId: 'prep' }, 202) : init?.method === 'DELETE' ? response({}) : new Promise<Response>(resolve => { finish = resolve }))
  const { result, rerender, unmount } = renderHook(({ scope }) => useSourcePreparation('http://synthetic', scope), { initialProps: { scope: 'one' } })
  const seen = vi.fn()
  await act(async () => { await result.current.start('DIRECT', {}, seen) }); await step(500)
  rerender({ scope: 'two' })
  await act(async () => { finish(response({ preparationId: 'prep', kind: 'DIRECT', status: 'COMPLETED', plan: {}, errorReason: null, lifecycle: lifecycle(1, true) })) })
  expect(result.current.status).toBeNull(); expect(seen).not.toHaveBeenCalled()
  unmount()
})
