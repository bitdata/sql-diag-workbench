// src/renderer/hooks/__tests__/useTabs.bootFailSafe.test.ts
//
// PR-2 review I-2 — `booting` had no fail-safe.
//
// `booting` starts true (spec §6.1) and every in-flow termination of it is
// guarded on `backendUrl`: the hydration success branch, and
// createTab/openInNewTab's `finally` (whose own early return sits BEFORE the
// try). So when `getBackendUrl()` rejects and `backendUrl` stays null forever,
// none of them can ever run → `booting` permanently true, `bootError` null,
// no banner, editor permanently read-only. Pre-PR-2 the editor stayed
// editable in that state, so this was a REGRESSION, not just a new gap.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useTabs } from '../useTabs'
import { resetMocks } from '../../test/testUtils'

const BACKEND = 'http://localhost:8080'

/** Local mirror of the project's memory-storage test double. */
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

function setup(opts: {
  backendUrl?: string | null
  settled?: boolean
  error?: string | null
} = {}) {
  const backendUrl = opts.backendUrl === undefined ? null : opts.backendUrl
  const settled = opts.settled ?? false
  const error = opts.error ?? null
  return renderHook(() =>
    useTabs(backendUrl, { backendUrlSettled: settled, backendUrlError: error })
  )
}

describe('useTabs — boot fail-safe (PR-2 I-2)', () => {
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

  it('terminates boot with an observable error when resolution settled without a URL', () => {
    const { result } = setup({ backendUrl: null, settled: true, error: 'ipc failure' })

    expect(result.current.booting).toBe(false)
    expect(result.current.bootError).toBe('ipc failure')
  })

  it('supplies a default message when resolution failed without one', () => {
    const { result } = setup({ backendUrl: null, settled: true, error: null })

    expect(result.current.booting).toBe(false)
    expect(result.current.bootError).toMatch(/Backend URL unavailable/)
  })

  it('does NOT terminate boot while resolution is still pending', () => {
    // settled=false is the "we do not know yet" state — terminating here would
    // flash the banner and re-enable the editor during every normal boot.
    const { result } = setup({ backendUrl: null, settled: false })

    expect(result.current.booting).toBe(true)
    expect(result.current.bootError).toBeNull()
  })

  it('does NOT set a boot error when a URL was resolved', () => {
    // Guards the effect against misfiring: a resolved URL means boot is still
    // genuinely pending (no tab created yet), not degraded.
    const { result } = setup({ backendUrl: BACKEND, settled: true })

    expect(result.current.booting).toBe(true)
    expect(result.current.bootError).toBeNull()
  })

  it('createTab without a backend URL reports instead of silently no-op’ing', async () => {
    // Isolated from the fail-safe above by keeping settled=false, so the only
    // thing that can set bootError is createTab's own early return.
    const { result } = setup({ backendUrl: null, settled: false })
    expect(result.current.bootError).toBeNull()

    await act(async () => {
      await result.current.createTab()
    })

    expect(result.current.bootError).toMatch(/Backend URL unavailable/)
    // The early return sits before `setBooting(true)`, so boot must not be
    // re-stranded by a futile retry.
    expect(result.current.booting).toBe(true)
  })

  it('openInNewTab without a backend URL reports instead of silently no-op’ing', async () => {
    const { result } = setup({ backendUrl: null, settled: false })

    await act(async () => {
      await result.current.openInNewTab('SELECT 1 FROM t')
    })

    expect(result.current.bootError).toMatch(/Backend URL unavailable/)
    expect(result.current.booting).toBe(true)
  })
})
