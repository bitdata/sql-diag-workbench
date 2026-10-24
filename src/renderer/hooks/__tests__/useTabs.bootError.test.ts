// src/renderer/hooks/__tests__/useTabs.bootError.test.ts
//
// PR-2 (boot race): pin observable boot state on useTabs.
// Before this PR, `createTab` only logged errors via `console.warn`.
// After this PR, createTab sets `bootError` state (rule #2 observable
// degradation) and exposes `booting` so consumers can disable typing.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useTabs } from '../useTabs'
import { resetMocks } from '../../test/testUtils'

const BACKEND = 'http://localhost:8080'

/**
 * Mirror of the helper already used in useTabs.test.ts — define it locally
 * to keep this file self-contained per the project test convention.
 */
function makeMemoryStorage(): Storage {
  let store: Record<string, string> = {}
  return {
    getItem: (k: string) => (k in store ? store[k] : null),
    setItem: (k: string, v: string) => {
      store[k] = String(v)
    },
    removeItem: (k: string) => {
      delete store[k]
    },
    clear: () => {
      store = {}
    },
    key: (i: number) => Object.keys(store)[i] ?? null,
    get length() {
      return Object.keys(store).length
    },
  }
}

/**
 * Build a fetch mock that resolves with the next queued response on each
 * call. The real hook calls `apiFetch(${backendUrl}/api/sessions)` so the
 * status + JSON body shape must match what the backend returns.
 */
function queueFetchResponses(
  ...responses: Array<{ ok: boolean; status?: number; body?: unknown }>
) {
  const calls = responses.map((r) =>
    vi.fn().mockResolvedValue({
      ok: r.ok,
      status: r.status ?? (r.ok ? 200 : 500),
      json: () => Promise.resolve(r.body ?? {}),
      text: () => Promise.resolve(JSON.stringify(r.body ?? {})),
    })
  )
  let i = 0
  globalThis.fetch = vi.fn().mockImplementation(() => {
    const handler = calls[Math.min(i, calls.length - 1)]
    i++
    return handler()
  })
  return globalThis.fetch
}

/** Render useTabs with a backend URL and isolated localStorage. */
function setup(backendUrl: string | null = BACKEND) {
  return renderHook(() => useTabs(backendUrl))
}

describe('useTabs — booting/bootError (PR-2)', () => {
  it('reports an owned tab failure only to its caller, with the captured dialect', async () => {
    const fetchMock = queueFetchResponses({ ok: false, status: 503 })
    const onError = vi.fn()
    const { result } = setup()
    await act(async () => {
      expect(await result.current.openInNewTab('SELECT 2', { name: 'Edited', dialect: 'mysql', onError })).toBeNull()
    })
    expect(onError).toHaveBeenCalledWith('HTTP 503')
    expect(result.current.bootError).toBeNull()
    expect(JSON.parse(String(vi.mocked(fetchMock).mock.calls[0][1]?.body)).dialect).toBe('mysql')
  })
  beforeEach(() => {
    Object.defineProperty(window, 'localStorage', {
      value: makeMemoryStorage(),
      configurable: true,
    })
    resetMocks()
  })

  afterEach(() => {
    resetMocks()
  })

  it('exposes booting=true while createTab is in flight, then false on resolve', async () => {
    // First call: hang the promise so we can observe `booting=true`.
    let release!: (v: { ok: boolean; status: number; json: () => Promise<unknown> }) => void
    globalThis.fetch = vi.fn().mockImplementation(
      () =>
        new Promise((resolve) => {
          release = resolve
        })
    )

    const { result } = setup()

    expect(result.current.booting).toBe(true)
    expect(result.current.bootError).toBeNull()

    act(() => {
      void result.current.createTab()
    })

    // While the POST is hanging, booting must remain true.
    expect(result.current.booting).toBe(true)
    expect(result.current.bootError).toBeNull()

    // Resolve the POST.
    await act(async () => {
      release({
        ok: true,
        status: 201,
        json: () =>
          Promise.resolve({
            id: 'sess-1',
            name: 'Untitled 1',
            sqlText: '',
            updatedAt: '2026-01-01T00:00:00.000Z',
            connectionId: null,
            dialect: 'postgresql',
            fragmentTreeJson: null,
          }),
      })
      // let microtasks settle
      await Promise.resolve()
    })

    expect(result.current.booting).toBe(false)
    expect(result.current.bootError).toBeNull()
    expect(result.current.tabs).toHaveLength(1)
  })

  it('exposes bootError when createTab rejects and clears on retryBoot success', async () => {
    queueFetchResponses(
      { ok: false, status: 500, body: { error: 'simulated failure' } },
      { ok: true, status: 200, body: { id: 'sess-1', name: 'Untitled 1', sqlText: '', dialect: 'postgresql', updatedAt: '2026-01-01T00:00:00.000Z' } }
    )

    const { result } = setup()

    await act(async () => {
      try {
        await result.current.createTab()
      } catch {
        // createTab swallows internally; ignore.
      }
    })

    expect(result.current.bootError).not.toBeNull()
    expect(result.current.booting).toBe(false)

    await act(async () => {
      try {
        await result.current.retryBoot()
      } catch {
        // ignore
      }
    })

    expect(result.current.bootError).toBeNull()
    expect(result.current.booting).toBe(false)
    expect(result.current.tabs).toHaveLength(1)
  })

  it('no longer swallows error in console only — bootError is set on rejection', async () => {
    // Silence the dev-only console.warn so the test output stays clean; the
    // assertion is that bootError is observable regardless of the log.
    const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    queueFetchResponses({ ok: false, status: 500, body: { error: 'boom' } })

    const { result } = setup()

    await act(async () => {
      try {
        await result.current.createTab()
      } catch {
        // ignore
      }
    })

    expect(result.current.bootError).not.toBeNull()
    expect(result.current.booting).toBe(false)

    consoleSpy.mockRestore()
  })

  // PR-2 (rule #1 exhaustive sweep — M-2): openInNewTab is the 3rd
  // createTab-style POST site. It must also surface bootError and clear on
  // retry, not just console.warn.
  it('openInNewTab failure also surfaces bootError and clears on retry', async () => {
    const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    queueFetchResponses(
      { ok: false, status: 500, body: { error: 'openInNewTab network' } },
      { ok: true, status: 200, body: { id: 'sess-2', name: 'From history', sqlText: 'SELECT 1 FROM t', dialect: 'postgresql', updatedAt: '2026-01-01T00:00:00.000Z' } }
    )

    const { result } = setup()

    // First call (openInNewTab) should set bootError.
    await act(async () => {
      try {
        await result.current.openInNewTab('SELECT 1 FROM t')
      } catch {
        // ignore
      }
    })

    expect(result.current.bootError).toMatch(/500/)
    expect(result.current.booting).toBe(false)

    expect(result.current.bootErrorLabel).toBe('Could not open SQL in a new tab')
    // Retry reopens the captured SQL instead of creating an empty tab.
    await act(async () => {
      try {
        await result.current.retryBoot()
      } catch {
        // ignore
      }
    })

    expect(result.current.bootError).toBeNull()
    expect(result.current.booting).toBe(false)
    expect(result.current.tabs).toHaveLength(1)
    expect(result.current.tabs[0].workspace.sql).toBe('SELECT 1 FROM t')

    consoleSpy.mockRestore()
  })
})
