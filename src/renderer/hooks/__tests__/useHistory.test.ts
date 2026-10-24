// src/renderer/hooks/__tests__/useHistory.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { useHistory } from '../useHistory'
import type { HistoryEntry } from '../../types/session'

const BASE_URL = 'http://localhost:8080'

function makeEntry(overrides: Partial<HistoryEntry> = {}): HistoryEntry {
  return {
    id: 'entry-1',
    sessionId: 'session-1',
    sqlText: 'SELECT 1',
    executedAt: '2026-01-01T00:00:00Z',
    durationMs: 42,
    rowCount: 5,
    ...overrides,
  }
}

describe('useHistory', () => {
  beforeEach(() => {
    vi.useRealTimers()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('returns empty initial state', () => {
    const { result } = renderHook(() => useHistory(BASE_URL))

    expect(result.current.entries).toEqual([])
    expect(result.current.loading).toBe(false)
  })

  it('does nothing when backendUrl is null', async () => {
    const fetchMock = vi.fn()
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useHistory(null))

    await act(async () => {
      await result.current.fetchHistory()
    })

    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.current.entries).toEqual([])
  })

  it('fetchHistory populates entries on success', async () => {
    const entries = [makeEntry(), makeEntry({ id: 'entry-2', sqlText: 'SELECT 2' })]
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(entries),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useHistory(BASE_URL))

    await act(async () => {
      await result.current.fetchHistory()
    })

    // apiFetch attaches its deadline signal to every bounded call (spec §4.2).
    expect(fetchMock).toHaveBeenCalledWith(
      `${BASE_URL}/api/history?limit=50`,
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(result.current.entries).toEqual(entries)
    expect(result.current.loading).toBe(false)
  })

  it('fetchHistory uses custom limit', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useHistory(BASE_URL))

    await act(async () => {
      await result.current.fetchHistory(10)
    })

    expect(fetchMock).toHaveBeenCalledWith(
      `${BASE_URL}/api/history?limit=10`,
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
  })

  it('fetchHistory silently swallows network errors', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('Network error'))
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useHistory(BASE_URL))

    await act(async () => {
      await result.current.fetchHistory()
    })

    expect(result.current.error).toBeTruthy()
    expect(result.current.degraded).toBe(true)
    expect(result.current.entries).toEqual([])
    expect(result.current.loading).toBe(false)
  })

  it('fetchHistory ignores non-ok responses and leaves entries unchanged', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.resolve({ error: 'Internal Server Error' }),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useHistory(BASE_URL))

    await act(async () => {
      await result.current.fetchHistory()
    })

    expect(result.current.entries).toEqual([])
    expect(result.current.error).toContain('HTTP 500')
  })

  it('addEntry POSTs to the API and re-fetches history', async () => {
    const entries = [makeEntry({ id: 'entry-new' })]
    const fetchMock = vi.fn()
      // POST /api/history
      .mockResolvedValueOnce({ ok: true, status: 201 })
      // GET /api/history?limit=50 (triggered by fetchHistory() inside addEntry)
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: () => Promise.resolve(entries),
      })
    globalThis.fetch = fetchMock

    // Mock crypto.randomUUID for deterministic assertion
    const originalRandomUUID = crypto.randomUUID
    crypto.randomUUID = vi.fn().mockReturnValue('uuid-123') as any

    const { result } = renderHook(() => useHistory(BASE_URL))

    await act(async () => {
      await result.current.addEntry('session-1', 'SELECT 1', 42, 5)
    })

    // Wait for the fire-and-forget fetchHistory() to complete
    await waitFor(() => {
      expect(result.current.entries).toEqual(entries)
    })

    // Verify POST call
    const postCall = fetchMock.mock.calls[0]
    expect(postCall[0]).toBe(`${BASE_URL}/api/history`)
    expect(postCall[1]).toEqual({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id: 'uuid-123',
        sessionId: 'session-1',
        sqlText: 'SELECT 1',
        durationMs: 42,
        rowCount: 5,
      }),
      signal: expect.any(AbortSignal),
    })

    // Verify re-fetch call
    expect(fetchMock.mock.calls[1][0]).toBe(`${BASE_URL}/api/history?limit=50`)

    crypto.randomUUID = originalRandomUUID
  })

  it('addEntry does nothing when backendUrl is null', async () => {
    const fetchMock = vi.fn()
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useHistory(null))

    await act(async () => {
      await result.current.addEntry('session-1', 'SELECT 1', null, null)
    })

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('uses updated backendUrl after re-render', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
    })
    globalThis.fetch = fetchMock

    const { result, rerender } = renderHook(
      ({ url }) => useHistory(url),
      { initialProps: { url: 'http://old:8080' as string | null } }
    )

    await act(async () => {
      await result.current.fetchHistory()
    })
    expect(fetchMock).toHaveBeenCalledWith(
      'http://old:8080/api/history?limit=50',
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    // Change URL
    rerender({ url: 'http://new:9090' })

    await act(async () => {
      await result.current.fetchHistory()
    })
    expect(fetchMock).toHaveBeenCalledWith(
      'http://new:9090/api/history?limit=50',
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
