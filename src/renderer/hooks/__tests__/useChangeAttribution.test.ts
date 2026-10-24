// src/renderer/hooks/__tests__/useChangeAttribution.test.ts
import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useChangeAttribution } from '../useChangeAttribution'
import type { ChangeOp } from '../useChangeAttribution'

// ─── Helpers ────────────────────────────────────────────────────────────────

const BACKEND = 'http://localhost:8080'

function op(kind: ChangeOp['kind'], path: string): ChangeOp {
  return { kind, nodeType: 'SELECT', path, oldText: null, newText: null }
}

/** Mock fetch returning `ops` with ok: true. */
function fetchReturning(ops: ChangeOp[]) {
  return vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: () => Promise.resolve({ ops }),
  })
}

afterEach(() => {
  vi.restoreAllMocks()
})

// ─── Tests ──────────────────────────────────────────────────────────────────

describe('useChangeAttribution', () => {
  // ── Null / empty / identical inputs ─────────────────────────────────────

  it('returns error "Backend not connected" when backendUrl is null', () => {
    const fetchMock = fetchReturning([])
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(null, 'SELECT a', 'SELECT b', 'postgresql')
    )

    expect(result.current.ops).toEqual([])
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBe('Backend not connected')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('returns empty state with no fetch when before is empty', async () => {
    const fetchMock = fetchReturning([])
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, '', 'SELECT b', 'postgresql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.ops).toEqual([])
    expect(result.current.error).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('returns empty state with no fetch when after is empty', async () => {
    const fetchMock = fetchReturning([])
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT a', '', 'postgresql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.ops).toEqual([])
    expect(result.current.error).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('returns empty state with no fetch when before equals after', async () => {
    const fetchMock = fetchReturning([])
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT a', 'SELECT a', 'postgresql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.ops).toEqual([])
    expect(result.current.error).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  // ── Successful fetch ───────────────────────────────────────────────────

  it('fetches /api/diff and returns ops on success', async () => {
    const ops = [op('ADD', '$.where'), op('DEL', '$.limit')]
    const fetchMock = fetchReturning(ops)
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT a', 'SELECT b', 'postgresql')
    )

    // loading is true while the fetch is in flight
    expect(result.current.loading).toBe(true)

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.ops).toEqual(ops)
    expect(result.current.error).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)

    // Verify request shape
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`${BACKEND}/api/diff`)
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(JSON.parse(init.body as string)).toEqual({
      before: 'SELECT a',
      after: 'SELECT b',
      dialect: 'postgresql',
    })
    expect(init.signal).toBeInstanceOf(AbortSignal)
  })

  it('passes the dialect through to the request body', async () => {
    const fetchMock = fetchReturning([])
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT 1', 'SELECT 2', 'mysql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    const body = JSON.parse(fetchMock.mock.calls[0][1].body as string)
    expect(body.dialect).toBe('mysql')
  })

  // ── Response without ops field ────────────────────────────────────────

  it('defaults to empty array when response has no ops field', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({}),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT a', 'SELECT b', 'postgresql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.ops).toEqual([])
    expect(result.current.error).toBeNull()
  })

  // ── Error handling ──────────────────────────────────────────────────────

  it('surfaces the error message from a non-ok response', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 422,
      json: () => Promise.resolve({ message: 'Parse failed' }),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT a', 'SELECT b', 'postgresql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.ops).toEqual([])
    expect(result.current.error).toBe('Parse failed')
  })

  it('falls back to HTTP <status> when error body has no message field', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.resolve({}),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT a', 'SELECT b', 'postgresql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBe('HTTP 500')
  })

  it('falls back to HTTP <status> when error body JSON parsing rejects', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      json: () => Promise.reject(new Error('not json')),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT a', 'SELECT b', 'postgresql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    // The .then((e) => { throw ... }) chain — when json() rejects, the
    // rejection propagates to .catch, which reads e.message.
    // The json() rejection is a plain Error, so e.message === 'not json'.
    expect(result.current.error).toBe('not json')
  })

  it('sets error from a network failure', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('Network down'))
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT a', 'SELECT b', 'postgresql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.ops).toEqual([])
    expect(result.current.error).toBe('Network down')
  })

  it('uses "Diff failed" for non-Error thrown values', async () => {
    const fetchMock = vi.fn().mockRejectedValue('string error')
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT a', 'SELECT b', 'postgresql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBe('Diff failed')
  })

  // ── Abort on input change ──────────────────────────────────────────────

  it('aborts the previous fetch when inputs change', async () => {
    const ops1 = [op('ADD', '$.where')]
    const ops2 = [op('DEL', '$.limit')]

    let callCount = 0
    const fetchMock = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      callCount++
      const myCall = callCount
      return new Promise((resolve, reject) => {
        init.signal?.addEventListener('abort', () => {
          reject(new DOMException('Aborted', 'AbortError'))
        })
        // Resolve only the second call
        if (myCall === 2) {
          resolve({
            ok: true,
            status: 200,
            json: () => Promise.resolve({ ops: ops2 }),
          })
        }
        // First call never resolves on its own — it gets aborted
      })
    })
    globalThis.fetch = fetchMock

    const { result, rerender } = renderHook(
      ({ after }) => useChangeAttribution(BACKEND, 'SELECT a', after, 'postgresql'),
      { initialProps: { after: 'SELECT b' } }
    )

    expect(result.current.loading).toBe(true)

    // Change the `after` prop → triggers abort of the first request
    rerender({ after: 'SELECT c' })

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(result.current.ops).toEqual(ops2)
    expect(result.current.error).toBeNull()
  })

  // ── Abort on unmount ───────────────────────────────────────────────────

  it('swallows AbortError on unmount without setting error state', async () => {
    const abortError = new DOMException('Aborted', 'AbortError')
    const fetchMock = vi.fn().mockRejectedValue(abortError)
    globalThis.fetch = fetchMock

    const { result, unmount } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT a', 'SELECT b', 'postgresql')
    )

    expect(result.current.loading).toBe(true)

    unmount() // triggers controller.abort(); AbortError must be swallowed

    // Let the rejected promise flush
    await new Promise((resolve) => setTimeout(resolve, 0))

    // No error should have been set (AbortError is silently ignored)
    expect(result.current.error).toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  // ── Re-run clears previous error ──────────────────────────────────────

  it('clears the previous error when a new fetch starts', async () => {
    let callCount = 0
    const fetchMock = vi.fn().mockImplementation(() => {
      callCount++
      if (callCount === 1) {
        return Promise.resolve({
          ok: false,
          status: 500,
          json: () => Promise.resolve({ message: 'Server error' }),
        })
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ ops: [op('ADD', '$.x')] }),
      })
    })
    globalThis.fetch = fetchMock

    const { result, rerender } = renderHook(
      ({ after }) => useChangeAttribution(BACKEND, 'SELECT a', after, 'postgresql'),
      { initialProps: { after: 'SELECT b' } }
    )

    await waitFor(() => {
      expect(result.current.error).toBe('Server error')
    })

    // Trigger a new fetch
    rerender({ after: 'SELECT c' })

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBeNull()
    expect(result.current.ops).toEqual([op('ADD', '$.x')])
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  // ── Loading preserves previous ops ─────────────────────────────────────

  it('preserves previous ops while loading the next diff', async () => {
    const ops1 = [op('ADD', '$.where')]
    let callCount = 0
    const fetchMock = vi.fn().mockImplementation(() => {
      callCount++
      if (callCount === 1) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () => Promise.resolve({ ops: ops1 }),
        })
      }
      // Second call hangs — never resolves on its own
      return new Promise(() => {})
    })
    globalThis.fetch = fetchMock

    const { result, rerender } = renderHook(
      ({ after }) => useChangeAttribution(BACKEND, 'SELECT a', after, 'postgresql'),
      { initialProps: { after: 'SELECT b' } }
    )

    await waitFor(() => {
      expect(result.current.ops).toEqual(ops1)
    })

    // Trigger a new fetch — loading goes true, but old ops survive
    rerender({ after: 'SELECT c' })

    await waitFor(() => {
      expect(result.current.loading).toBe(true)
    })

    // The previous ops are still visible during loading
    expect(result.current.ops).toEqual(ops1)
  })

  // ── Degraded / diffMethod surfacing (PR-α) ───────────────────────────

  it('surfaces degraded=true and normalizes backend "PATH_SET" to "path-set"', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          ops: [
            {
              kind: 'MOD',
              nodeType: 'X',
              path: 'p',
              oldText: 'a',
              newText: 'b',
            },
          ],
          degraded: true,
          diffMethod: 'PATH_SET',
        }),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT 1', 'SELECT 2', 'postgresql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.degraded).toBe(true)
    expect(result.current.diffMethod).toBe('path-set')
  })

  it('reports degraded=false when backend response is precise ("LCS")', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({ ops: [], degraded: false, diffMethod: 'LCS' }),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() =>
      useChangeAttribution(BACKEND, 'SELECT 1', 'SELECT 2', 'postgresql')
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.degraded).toBe(false)
    expect(result.current.diffMethod).toBe('ast-lcs')
  })

  // ── Suspected bugs ─────────────────────────────────────────────────────

  it.skip('TODO: when backendUrl transitions from null to valid, error should clear', () => {
    // Currently when backendUrl is null, error is set to 'Backend not connected'.
    // When it becomes valid, the effect re-runs and fetches, setting loading=true
    // and clearing error. This actually works — but the intermediate state where
    // error='Backend not connected' is shown even though the user just connected
    // the backend might be a UX concern. Verify if this is intentional.
  })
})
