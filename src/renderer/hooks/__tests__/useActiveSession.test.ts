// src/renderer/hooks/__tests__/useActiveSession.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useActiveSession } from '../useActiveSession'

describe('useActiveSession', () => {
  beforeEach(() => {
    vi.useRealTimers()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('returns empty state when activeSessionId is null', () => {
    const { result } = renderHook(() => useActiveSession('http://localhost:8080', null))

    expect(result.current.sql).toBe('')
    expect(result.current.fragmentTreeJson).toBeNull()
    expect(result.current.selectedNodeId).toBeNull()
    expect(result.current.isLoading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('returns empty state when backendUrl is null', () => {
    const { result } = renderHook(() => useActiveSession(null, 'session-1'))

    expect(result.current.sql).toBe('')
    expect(result.current.isLoading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('fetches session and populates state on activeSessionId change', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        sqlText: 'SELECT * FROM users',
        fragmentTreeJson: '{"id":"root"}',
        selectedNodeId: 'node-1',
      }),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useActiveSession('http://localhost:8080', 'session-1'))

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false)
    })

    // apiFetch owns a client-side deadline and therefore always attaches a signal (spec §4.2),
    // so the init is matched by shape rather than by absence.
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/sessions/session-1',
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(result.current.sql).toBe('SELECT * FROM users')
    expect(result.current.fragmentTreeJson).toBe('{"id":"root"}')
    expect(result.current.selectedNodeId).toBe('node-1')
    expect(result.current.error).toBeNull()
  })

  it('sets error on 404 response', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useActiveSession('http://localhost:8080', 'session-deleted'))

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false)
    })

    expect(result.current.error).toBe('Session not found (may have been deleted)')
    expect(result.current.sql).toBe('')
  })

  it('sets error on network failure', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('Network error'))
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useActiveSession('http://localhost:8080', 'session-1'))

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false)
    })

    expect(result.current.error).toBe('Network error')
  })

  it('re-fetches when activeSessionId changes', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ sqlText: 'SELECT 1' }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ sqlText: 'SELECT 2' }),
      })
    globalThis.fetch = fetchMock

    const { result, rerender } = renderHook(
      ({ sessionId }) => useActiveSession('http://localhost:8080', sessionId),
      { initialProps: { sessionId: 'session-1' } }
    )

    await waitFor(() => {
      expect(result.current.sql).toBe('SELECT 1')
    })

    // Change session
    rerender({ sessionId: 'session-2' })

    await waitFor(() => {
      expect(result.current.sql).toBe('SELECT 2')
    })

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('reload() re-fetches the current session', async () => {
    let callCount = 0
    const fetchMock = vi.fn().mockImplementation(() => {
      callCount++
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ sqlText: `SELECT ${callCount}` }),
      })
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useActiveSession('http://localhost:8080', 'session-1'))

    await waitFor(() => {
      expect(result.current.sql).toBe('SELECT 1')
    })

    // Call reload
    result.current.reload()

    await waitFor(() => {
      expect(result.current.sql).toBe('SELECT 2')
    })

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
