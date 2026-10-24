// src/renderer/hooks/__tests__/useBackendUrl.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useBackendUrl, useBackendUrlState } from '../useBackendUrl'
import type { ElectronAPI } from '../../types/electron'

/** Install a mock electronAPI on window for Electron-mode tests. */
function installElectronApi(overrides: Partial<ElectronAPI> = {}) {
  const api = {
    getBackendUrl: vi.fn().mockResolvedValue('http://127.0.0.1:8080'),
    getBackendStatus: vi.fn().mockResolvedValue({ status: 'UP' }),
    onBackendStatusChange: vi.fn(() => vi.fn()),
    ...overrides,
  } as ElectronAPI
  ;(window as unknown as { electronAPI: ElectronAPI }).electronAPI = api
  return api
}

describe('useBackendUrl', () => {
  beforeEach(() => {
    vi.useRealTimers()
  })

  afterEach(() => {
    delete (window as unknown as { electronAPI?: unknown }).electronAPI
    vi.restoreAllMocks()
  })

  it('falls back to http://localhost:8080 in browser mode (no electronAPI)', () => {
    const { result } = renderHook(() => useBackendUrl())

    // The effect sets the URL synchronously — no async wait needed
    expect(result.current).toBe('http://localhost:8080')
    // Browser mode never probes via fetch
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('returns null initially and resolves from electronAPI.getBackendUrl in Electron mode', async () => {
    const getBackendUrl = vi.fn().mockResolvedValue('http://127.0.0.1:54321')
    installElectronApi({ getBackendUrl })

    const { result } = renderHook(() => useBackendUrl())

    // null until the IPC promise settles
    expect(result.current).toBeNull()

    await act(async () => {})

    expect(result.current).toBe('http://127.0.0.1:54321')
    expect(getBackendUrl).toHaveBeenCalledTimes(1)
  })

  it('stays null when getBackendUrl rejects', async () => {
    const getBackendUrl = vi.fn().mockRejectedValue(new Error('ipc failure'))
    installElectronApi({ getBackendUrl })

    const { result } = renderHook(() => useBackendUrl())

    // Flush the rejection and its .catch(() => setUrl(null))
    await act(async () => {})

    expect(result.current).toBeNull()
    expect(getBackendUrl).toHaveBeenCalledTimes(1)
  })

  it('does not re-invoke getBackendUrl on rerender', async () => {
    const getBackendUrl = vi.fn().mockResolvedValue('http://127.0.0.1:9999')
    installElectronApi({ getBackendUrl })

    const { result, rerender } = renderHook(() => useBackendUrl())
    await act(async () => {})
    expect(result.current).toBe('http://127.0.0.1:9999')

    rerender()
    rerender()

    expect(getBackendUrl).toHaveBeenCalledTimes(1)
    expect(result.current).toBe('http://127.0.0.1:9999')
  })

  it('keeps the fallback URL stable across rerenders in browser mode', () => {
    const { result, rerender } = renderHook(() => useBackendUrl())

    rerender()

    expect(result.current).toBe('http://localhost:8080')
  })
})

/**
 * PR-2 review I-2. The plain `useBackendUrl` contract above deliberately keeps
 * returning bare `null` on failure — but that collapses "no URL yet" and "no
 * URL ever" into one value, which is what stranded `booting` at true forever
 * (editor permanently read-only). `useBackendUrlState` exists to separate them.
 */
describe('useBackendUrlState — settled/error lifecycle (PR-2 I-2)', () => {
  beforeEach(() => {
    vi.useRealTimers()
  })

  afterEach(() => {
    delete (window as unknown as { electronAPI?: unknown }).electronAPI
    vi.restoreAllMocks()
  })

  it('reports settled=false while the IPC promise is still pending', () => {
    installElectronApi({ getBackendUrl: vi.fn(() => new Promise<string>(() => {})) })

    const { result } = renderHook(() => useBackendUrlState())

    expect(result.current.url).toBeNull()
    expect(result.current.settled).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('reports settled=true with the URL on resolve', async () => {
    installElectronApi({ getBackendUrl: vi.fn().mockResolvedValue('http://127.0.0.1:54321') })

    const { result } = renderHook(() => useBackendUrlState())
    await act(async () => {})

    expect(result.current.url).toBe('http://127.0.0.1:54321')
    expect(result.current.settled).toBe(true)
    expect(result.current.error).toBeNull()
  })

  it('reports settled=true WITH an error on rejection — the signal boot needs to terminate', async () => {
    installElectronApi({ getBackendUrl: vi.fn().mockRejectedValue(new Error('ipc failure')) })

    const { result } = renderHook(() => useBackendUrlState())
    await act(async () => {})

    expect(result.current.url).toBeNull()
    expect(result.current.settled).toBe(true)
    expect(result.current.error).toBe('ipc failure')
  })

  it('reports settled=true in browser mode (no IPC round-trip to wait for)', () => {
    const { result } = renderHook(() => useBackendUrlState())

    expect(result.current.url).toBe('http://localhost:8080')
    expect(result.current.settled).toBe(true)
    expect(result.current.error).toBeNull()
  })
})
