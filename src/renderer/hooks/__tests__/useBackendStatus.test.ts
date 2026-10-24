// src/renderer/hooks/__tests__/useBackendStatus.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useBackendStatus } from '../useBackendStatus'
import type { ElectronAPI, BackendStatusEvent } from '../../types/electron'

/** Flush all pending microtasks (promises) while fake timers are active. */
async function flushMicrotasks() {
  await act(async () => {})
}

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

describe('useBackendStatus', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    delete (window as unknown as { electronAPI?: unknown }).electronAPI
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  describe('initial state', () => {
    it('starts with null status, error, and backendEvent', () => {
      const { result } = renderHook(() => useBackendStatus())

      expect(result.current.status).toBeNull()
      expect(result.current.error).toBeNull()
      expect(result.current.backendEvent).toBeNull()
    })
  })

  describe('browser mode (no electronAPI)', () => {
    it('sets status to UP when fetch succeeds', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({ ok: true })

      const { result } = renderHook(() => useBackendStatus())

      await flushMicrotasks()

      expect(result.current.status).toEqual({ status: 'UP' })
      expect(result.current.error).toBeNull()
      // apiFetch attaches its deadline signal to every bounded call (spec §4.2).
      expect(globalThis.fetch).toHaveBeenCalledWith(
        'http://localhost:8080/api/connections',
        expect.objectContaining({ signal: expect.any(AbortSignal) })
      )
    })

    it('sets status to DOWN when fetch returns non-ok response', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({ ok: false, status: 503 })

      const { result } = renderHook(() => useBackendStatus())

      await flushMicrotasks()

      expect(result.current.status).toEqual({ status: 'DOWN' })
      expect(result.current.error).toBe('Backend returned 503')
    })

    it('sets status to DOWN when fetch throws network error', async () => {
      globalThis.fetch = vi.fn().mockRejectedValue(new Error('Network error'))

      const { result } = renderHook(() => useBackendStatus())

      await flushMicrotasks()

      expect(result.current.status).toEqual({ status: 'DOWN' })
      expect(result.current.error).toBe('Cannot reach backend')
    })

    it('polls every 10 seconds', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({ ok: true })

      renderHook(() => useBackendStatus())

      await flushMicrotasks()
      expect(globalThis.fetch).toHaveBeenCalledTimes(1)

      // Advance 10 seconds
      await act(async () => {
        vi.advanceTimersByTime(10_000)
      })
      await flushMicrotasks()
      expect(globalThis.fetch).toHaveBeenCalledTimes(2)

      // Advance another 10 seconds
      await act(async () => {
        vi.advanceTimersByTime(10_000)
      })
      await flushMicrotasks()
      expect(globalThis.fetch).toHaveBeenCalledTimes(3)
    })

    it('cleans up interval on unmount', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({ ok: true })

      const { unmount } = renderHook(() => useBackendStatus())

      await flushMicrotasks()
      expect(globalThis.fetch).toHaveBeenCalledTimes(1)

      unmount()

      await act(async () => {
        vi.advanceTimersByTime(20_000)
      })
      await flushMicrotasks()

      // Should not have called fetch again after unmount
      expect(globalThis.fetch).toHaveBeenCalledTimes(1)
    })
  })

  describe('electron mode (electronAPI present)', () => {
    it('calls getBackendStatus on mount', async () => {
      const getBackendStatus = vi.fn().mockResolvedValue({ status: 'UP', timestamp: '2026-01-01' })
      installElectronApi({ getBackendStatus })

      const { result } = renderHook(() => useBackendStatus())

      await flushMicrotasks()

      expect(result.current.status).toEqual({ status: 'UP', timestamp: '2026-01-01' })
      expect(getBackendStatus).toHaveBeenCalledTimes(1)
      expect(result.current.error).toBeNull()
    })

    it('sets DOWN status when getBackendStatus rejects', async () => {
      const getBackendStatus = vi.fn().mockRejectedValue(new Error('IPC error'))
      installElectronApi({ getBackendStatus })

      const { result } = renderHook(() => useBackendStatus())

      await flushMicrotasks()

      expect(result.current.status).toEqual({ status: 'DOWN' })
      expect(result.current.error).toBe('Failed to reach backend')
    })

    it('subscribes to onBackendStatusChange', () => {
      const unsubscribe = vi.fn()
      const onBackendStatusChange = vi.fn().mockReturnValue(unsubscribe)
      installElectronApi({ onBackendStatusChange })

      renderHook(() => useBackendStatus())

      expect(onBackendStatusChange).toHaveBeenCalledTimes(1)
      expect(onBackendStatusChange).toHaveBeenCalledWith(expect.any(Function))
    })

    it('unsubscribes on unmount', () => {
      const unsubscribe = vi.fn()
      const onBackendStatusChange = vi.fn().mockReturnValue(unsubscribe)
      installElectronApi({ onBackendStatusChange })

      const { unmount } = renderHook(() => useBackendStatus())

      expect(unsubscribe).not.toHaveBeenCalled()

      unmount()

      expect(unsubscribe).toHaveBeenCalledTimes(1)
    })

    describe('event handlers', () => {
      it('handles running event', async () => {
        let eventCallback: ((event: BackendStatusEvent) => void) | undefined
        const onBackendStatusChange = vi.fn((cb) => {
          eventCallback = cb
          return vi.fn()
        })
        installElectronApi({ onBackendStatusChange })

        const { result } = renderHook(() => useBackendStatus())
        await flushMicrotasks()

        await act(async () => {
          eventCallback!({ status: 'running', port: 8080 })
        })

        expect(result.current.status).toEqual({ status: 'UP' })
        expect(result.current.error).toBeNull()
        expect(result.current.backendEvent).toEqual({ status: 'running', port: 8080 })
      })

      it('handles crashed event with custom error', async () => {
        let eventCallback: ((event: BackendStatusEvent) => void) | undefined
        const onBackendStatusChange = vi.fn((cb) => {
          eventCallback = cb
          return vi.fn()
        })
        installElectronApi({ onBackendStatusChange })

        const { result } = renderHook(() => useBackendStatus())
        await flushMicrotasks()

        await act(async () => {
          eventCallback!({ status: 'crashed', error: 'Out of memory' })
        })

        expect(result.current.status).toEqual({ status: 'DOWN' })
        expect(result.current.error).toBe('Out of memory')
        expect(result.current.backendEvent).toEqual({ status: 'crashed', error: 'Out of memory' })
      })

      it('handles crashed event with default error', async () => {
        let eventCallback: ((event: BackendStatusEvent) => void) | undefined
        const onBackendStatusChange = vi.fn((cb) => {
          eventCallback = cb
          return vi.fn()
        })
        installElectronApi({ onBackendStatusChange })

        const { result } = renderHook(() => useBackendStatus())
        await flushMicrotasks()

        await act(async () => {
          eventCallback!({ status: 'crashed' })
        })

        expect(result.current.status).toEqual({ status: 'DOWN' })
        expect(result.current.error).toBe('Backend process crashed')
      })

      it('handles error event with custom error', async () => {
        let eventCallback: ((event: BackendStatusEvent) => void) | undefined
        const onBackendStatusChange = vi.fn((cb) => {
          eventCallback = cb
          return vi.fn()
        })
        installElectronApi({ onBackendStatusChange })

        const { result } = renderHook(() => useBackendStatus())
        await flushMicrotasks()

        await act(async () => {
          eventCallback!({ status: 'error', error: 'Port already in use' })
        })

        expect(result.current.status).toEqual({ status: 'DOWN' })
        expect(result.current.error).toBe('Port already in use')
      })

      it('handles error event with default error', async () => {
        let eventCallback: ((event: BackendStatusEvent) => void) | undefined
        const onBackendStatusChange = vi.fn((cb) => {
          eventCallback = cb
          return vi.fn()
        })
        installElectronApi({ onBackendStatusChange })

        const { result } = renderHook(() => useBackendStatus())
        await flushMicrotasks()

        await act(async () => {
          eventCallback!({ status: 'error' })
        })

        expect(result.current.status).toEqual({ status: 'DOWN' })
        expect(result.current.error).toBe('Backend error')
      })

      it('handles starting event and clears previous error', async () => {
        let eventCallback: ((event: BackendStatusEvent) => void) | undefined
        const onBackendStatusChange = vi.fn((cb) => {
          eventCallback = cb
          return vi.fn()
        })
        installElectronApi({ onBackendStatusChange })

        const { result } = renderHook(() => useBackendStatus())
        await flushMicrotasks()

        // First set an error to verify it gets cleared
        await act(async () => {
          eventCallback!({ status: 'crashed', error: 'Previous error' })
        })
        expect(result.current.error).toBe('Previous error')

        await act(async () => {
          eventCallback!({ status: 'starting' })
        })

        expect(result.current.status).toEqual({ status: 'DOWN' })
        expect(result.current.error).toBeNull()
      })

      it('stopped event clears previous error', async () => {
        let eventCallback: ((event: BackendStatusEvent) => void) | undefined
        const onBackendStatusChange = vi.fn((cb) => {
          eventCallback = cb
          return vi.fn()
        })
        installElectronApi({ onBackendStatusChange })

        const { result } = renderHook(() => useBackendStatus())
        await flushMicrotasks()

        // Set an error first
        await act(async () => {
          eventCallback!({ status: 'crashed', error: 'Previous error' })
        })
        expect(result.current.error).toBe('Previous error')

        // stopped should clear the error
        await act(async () => {
          eventCallback!({ status: 'stopped' })
        })

        expect(result.current.status).toEqual({ status: 'DOWN' })
        expect(result.current.error).toBeNull()
      })

      it('stopped event sets DOWN status without modifying error', async () => {
        let eventCallback: ((event: BackendStatusEvent) => void) | undefined
        const onBackendStatusChange = vi.fn((cb) => {
          eventCallback = cb
          return vi.fn()
        })
        installElectronApi({ onBackendStatusChange })

        const { result } = renderHook(() => useBackendStatus())
        await flushMicrotasks()

        await act(async () => {
          eventCallback!({ status: 'stopped' })
        })

        expect(result.current.status).toEqual({ status: 'DOWN' })
        expect(result.current.backendEvent).toEqual({ status: 'stopped' })
      })
    })

    it('polls getBackendStatus every 10 seconds', async () => {
      const getBackendStatus = vi.fn().mockResolvedValue({ status: 'UP' })
      installElectronApi({ getBackendStatus })

      renderHook(() => useBackendStatus())

      await flushMicrotasks()
      expect(getBackendStatus).toHaveBeenCalledTimes(1)

      await act(async () => {
        vi.advanceTimersByTime(10_000)
      })
      await flushMicrotasks()
      expect(getBackendStatus).toHaveBeenCalledTimes(2)

      await act(async () => {
        vi.advanceTimersByTime(10_000)
      })
      await flushMicrotasks()
      expect(getBackendStatus).toHaveBeenCalledTimes(3)
    })

    it('cleans up polling interval on unmount', async () => {
      const getBackendStatus = vi.fn().mockResolvedValue({ status: 'UP' })
      installElectronApi({ getBackendStatus })

      const { unmount } = renderHook(() => useBackendStatus())

      await flushMicrotasks()
      expect(getBackendStatus).toHaveBeenCalledTimes(1)

      unmount()

      await act(async () => {
        vi.advanceTimersByTime(20_000)
      })
      await flushMicrotasks()

      expect(getBackendStatus).toHaveBeenCalledTimes(1)
    })
  })

  describe('recheck callback', () => {
    it('triggers immediate status check in browser mode', async () => {
      globalThis.fetch = vi.fn().mockResolvedValue({ ok: true })

      const { result } = renderHook(() => useBackendStatus())

      await flushMicrotasks()
      expect(globalThis.fetch).toHaveBeenCalledTimes(1)

      await act(async () => {
        result.current.recheck()
      })
      await flushMicrotasks()

      expect(globalThis.fetch).toHaveBeenCalledTimes(2)
    })

    it('triggers immediate status check in electron mode', async () => {
      const getBackendStatus = vi.fn().mockResolvedValue({ status: 'UP' })
      installElectronApi({ getBackendStatus })

      const { result } = renderHook(() => useBackendStatus())

      await flushMicrotasks()
      expect(getBackendStatus).toHaveBeenCalledTimes(1)

      await act(async () => {
        result.current.recheck()
      })
      await flushMicrotasks()

      expect(getBackendStatus).toHaveBeenCalledTimes(2)
    })

    it('updates status after recheck reflects new backend state', async () => {
      let callCount = 0
      globalThis.fetch = vi.fn().mockImplementation(async () => {
        callCount++
        return { ok: callCount > 1, status: callCount > 1 ? 200 : 503 }
      })

      const { result } = renderHook(() => useBackendStatus())

      await flushMicrotasks()

      expect(result.current.status).toEqual({ status: 'DOWN' })
      expect(result.current.error).toBe('Backend returned 503')

      await act(async () => {
        result.current.recheck()
      })
      await flushMicrotasks()

      expect(result.current.status).toEqual({ status: 'UP' })
      expect(result.current.error).toBeNull()
    })

    it('recheck is a stable reference across rerenders', () => {
      globalThis.fetch = vi.fn().mockResolvedValue({ ok: true })

      const { result, rerender } = renderHook(() => useBackendStatus())

      const firstRecheck = result.current.recheck
      rerender()

      expect(result.current.recheck).toBe(firstRecheck)
    })
  })
})
