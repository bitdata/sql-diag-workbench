import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useExecution } from '../useExecution'
import { useRowTrace } from '../useRowTrace'
import { useDiagnostics } from '../useDiagnostics'
import { DEFAULT_API_TIMEOUT_MS } from '../../utils/api'

/**
 * Spec §4.2 pins a deadline per CALL FAMILY: short/medium calls inherit
 * DEFAULT_API_TIMEOUT_MS, the long ones opt out with `timeoutMs: null`. These tests pin the
 * opt-out through the hook — with a request that never settles, the hook must still be in
 * its pending state after the default deadline has elapsed. Dropping `timeoutMs: null` from
 * a call site turns the matching test red.
 */
const BACKEND = 'http://localhost:8080'

/** What a hung backend looks like: fetch neither resolves nor rejects. */
function neverSettlingFetch() {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(() => new Promise<Response>(() => {}))
}

describe('long-call deadline opt-out (spec §4.2)', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('useExecution.execute keeps waiting past the default deadline', async () => {
    vi.useFakeTimers()
    const fetchSpy = neverSettlingFetch()
    const { result } = renderHook(() => useExecution(BACKEND, 'node-1'))

    await act(async () => {
      void result.current.execute('SELECT 1', 'conn-1')
    })
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    expect(result.current.loading).toBe(true)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DEFAULT_API_TIMEOUT_MS + 5_000)
    })
    expect(result.current.loading).toBe(true)
    expect(result.current.error).toBeNull()
  })

  it('useRowTrace.trace keeps waiting past the default deadline', async () => {
    vi.useFakeTimers()
    neverSettlingFetch()
    const { result } = renderHook(() => useRowTrace(BACKEND))

    await act(async () => {
      void result.current.trace('conn-1', '{}', [])
    })
    expect(result.current.status).toBe('QUEUED')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DEFAULT_API_TIMEOUT_MS + 5_000)
    })
    expect(result.current.status).toBe('QUEUED')
    expect(result.current.error).toBeNull()
  })

  it('useDiagnostics.verify keeps waiting past the default deadline', async () => {
    vi.useFakeTimers()
    neverSettlingFetch()
    const { result } = renderHook(() => useDiagnostics(BACKEND))

    await act(async () => {
      void result.current.verify('f-1', 'SELECT 1', 'conn-1')
    })
    expect(result.current.verifyingIds.has('f-1')).toBe(true)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DEFAULT_API_TIMEOUT_MS + 5_000)
    })
    expect(result.current.verifyingIds.has('f-1')).toBe(true)
    expect(result.current.verifyErrors['f-1']).toBeUndefined()
  })
})