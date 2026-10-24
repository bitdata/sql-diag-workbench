import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { apiFetch, initApiAuth, __resetApiAuthForTest, ApiTimeoutError, DEFAULT_API_TIMEOUT_MS } from './api'

describe('apiFetch auth token handling', () => {
  beforeEach(() => {
    __resetApiAuthForTest()
  })

  it('attaches X-Auth-Token after initApiAuth resolves a token', async () => {
    ;(window as unknown as Record<string, unknown>).electronAPI = {
      getAuthToken: async () => 'secret-token'
    }
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response())

    await initApiAuth()
    await apiFetch('/api/connections')

    const init = fetchSpy.mock.calls[0][1]
    expect(new Headers(init?.headers).get('X-Auth-Token')).toBe('secret-token')
  })

  it('sends no token header when electronAPI is absent (browser mode)', async () => {
    delete (window as unknown as Record<string, unknown>).electronAPI
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response())

    await initApiAuth()
    await apiFetch('/api/connections')

    const init = fetchSpy.mock.calls[0][1]
    expect(new Headers(init?.headers).get('X-Auth-Token')).toBeNull()
  })

  it('preserves caller-provided headers', async () => {
    ;(window as unknown as Record<string, unknown>).electronAPI = {
      getAuthToken: async () => 'secret-token'
    }
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response())

    await initApiAuth()
    await apiFetch('/api/execution/execute', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}'
    })

    const init = fetchSpy.mock.calls[0][1]
    const headers = new Headers(init?.headers)
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('X-Auth-Token')).toBe('secret-token')
  })
})

describe('apiFetch deadline handling (spec §4.2)', () => {
  beforeEach(() => {
    __resetApiAuthForTest()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  /**
   * What a hung backend looks like: fetch neither resolves nor rejects on its own, and
   * rejects only when its signal is aborted.
   *
   * The `signal.aborted` check at the top mirrors what the platform does for a signal that is
   * ALREADY aborted when fetch is called — it rejects immediately. Modelled explicitly
   * because `addEventListener('abort')` does NOT replay a past abort, so a listener-only mock
   * would sit there forever on that path and the test would hang instead of failing.
   */
  function neverSettlingFetch() {
    return vi.spyOn(globalThis, 'fetch').mockImplementation((_input, init) =>
      new Promise<Response>((_resolve, reject) => {
        const abortWith = () => reject(new DOMException('Aborted', 'AbortError'))
        if (init?.signal?.aborted) {
          abortWith()
          return
        }
        init?.signal?.addEventListener('abort', abortWith)
      })
    )
  }

  it('exposes the documented default deadline', () => {
    // Not decoration: if this constant ever went missing or undefined, every
    // advanceTimersByTimeAsync(DEFAULT_API_TIMEOUT_MS…) below would advance by NaN, advance
    // nothing, and the opt-out test below would pass VACUOUSLY instead of failing.
    expect(DEFAULT_API_TIMEOUT_MS).toBe(30_000)
  })

  it('bounds a hung request by DEFAULT_API_TIMEOUT_MS (fail-closed default)', async () => {
    neverSettlingFetch()

    const pending = apiFetch('/api/connections')
    const rejected = expect(pending).rejects.toBeInstanceOf(ApiTimeoutError)

    await vi.advanceTimersByTimeAsync(DEFAULT_API_TIMEOUT_MS + 1)

    await rejected
  })

  it('leaves a hung request alone when the call site opted out with timeoutMs: null', async () => {
    neverSettlingFetch()

    let settled = false
    const pending = apiFetch('/api/execution/execute', { method: 'POST' }, { timeoutMs: null })
    pending.catch(() => {
      settled = true
    })

    await vi.advanceTimersByTimeAsync(DEFAULT_API_TIMEOUT_MS * 3)

    expect(settled).toBe(false)
  })

  it('rejects with the caller AbortError — not a timeout — when the caller signal aborts', async () => {
    neverSettlingFetch()
    const caller = new AbortController()

    const pending = apiFetch('/api/connections', { signal: caller.signal })
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })

    caller.abort()
    await vi.advanceTimersByTimeAsync(DEFAULT_API_TIMEOUT_MS * 2)

    await rejected
  })

  it('does not arm the deadline for an already-aborted caller signal', async () => {
    neverSettlingFetch()
    const caller = new AbortController()
    caller.abort()

    const pending = apiFetch('/api/connections', { signal: caller.signal })

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    // The deadline must not fire afterwards (nothing to leak, nothing to reject twice).
    await vi.advanceTimersByTimeAsync(DEFAULT_API_TIMEOUT_MS * 2)
  })
})
