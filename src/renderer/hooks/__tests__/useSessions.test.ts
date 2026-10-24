// src/renderer/hooks/__tests__/useSessions.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { useSessions } from '../useSessions'
import type { Session } from '../../types/session'

const BASE_URL = 'http://localhost:8080'

function makeSession(overrides: Partial<Session> = {}): Session {
  return {
    id: 'session-1',
    name: 'Test Session',
    sqlText: 'SELECT 1',
    dialect: 'postgresql',
    connectionId: null,
    fragmentTreeJson: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('useSessions', () => {
  beforeEach(() => {
    vi.useRealTimers()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('returns empty initial state', () => {
    const { result } = renderHook(() => useSessions(BASE_URL))

    expect(result.current.sessions).toEqual([])
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('does nothing when backendUrl is null', async () => {
    const fetchMock = vi.fn()
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useSessions(null))

    await act(async () => {
      await result.current.fetchSessions()
    })

    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.current.sessions).toEqual([])
    expect(result.current.loading).toBe(false)
  })

  it('fetchSessions populates sessions on success', async () => {
    const sessions = [makeSession(), makeSession({ id: 'session-2', name: 'Second' })]
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(sessions),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useSessions(BASE_URL))

    await act(async () => {
      await result.current.fetchSessions()
    })

    // apiFetch attaches its deadline signal to every bounded call (spec §4.2).
    expect(fetchMock).toHaveBeenCalledWith(
      `${BASE_URL}/api/sessions`,
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(result.current.sessions).toEqual(sessions)
    expect(result.current.error).toBeNull()
    expect(result.current.loading).toBe(false)
  })

  it('fetchSessions sets error on network failure', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('Network error'))
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useSessions(BASE_URL))

    await act(async () => {
      await result.current.fetchSessions()
    })

    expect(result.current.error).toBe('Network error')
    expect(result.current.sessions).toEqual([])
    expect(result.current.loading).toBe(false)
  })

  // SUSPECTED BUG: fetchSessions does not check res.ok — a non-ok JSON response
  it('fetchSessions sets error on non-ok response', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      json: () => Promise.resolve({ message: 'Not found' }),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useSessions(BASE_URL))

    await act(async () => {
      await result.current.fetchSessions()
    })

    expect(result.current.error).toBe('Not found')
    expect(result.current.sessions).toEqual([])
  })

  it('fetchSessions falls back to HTTP status when error body has no message', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.resolve({ error: 'Internal Server Error' }),
    })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useSessions(BASE_URL))

    await act(async () => {
      await result.current.fetchSessions()
    })

    expect(result.current.error).toBe('HTTP 500')
    expect(result.current.sessions).toEqual([])
  })

  it('updateSession replaces the matching session', async () => {
    const original = makeSession({ id: 'session-1', name: 'Before' })
    const updated = makeSession({ id: 'session-1', name: 'After' })

    const fetchMock = vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: () => Promise.resolve([original]),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: () => Promise.resolve(updated),
      })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useSessions(BASE_URL))

    await act(async () => {
      await result.current.fetchSessions()
    })

    let returnValue: Session | null = null
    await act(async () => {
      returnValue = await result.current.updateSession('session-1', { name: 'After' })
    })

    expect(fetchMock).toHaveBeenCalledWith(`${BASE_URL}/api/sessions/session-1`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'After' }),
      signal: expect.any(AbortSignal),
    })
    expect(returnValue).toEqual(updated)
    expect(result.current.sessions[0].name).toBe('After')
  })

  it('deleteSession removes the session from state', async () => {
    const sessions = [
      makeSession({ id: 'session-1' }),
      makeSession({ id: 'session-2', name: 'Second' }),
    ]

    const fetchMock = vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: () => Promise.resolve(sessions),
      })
      .mockResolvedValueOnce({ ok: true, status: 204 })
    globalThis.fetch = fetchMock

    const { result } = renderHook(() => useSessions(BASE_URL))

    await act(async () => {
      await result.current.fetchSessions()
    })
    expect(result.current.sessions).toHaveLength(2)

    await act(async () => {
      await result.current.deleteSession('session-1')
    })

    expect(fetchMock).toHaveBeenCalledWith(`${BASE_URL}/api/sessions/session-1`, {
      method: 'DELETE',
      signal: expect.any(AbortSignal),
    })
    expect(result.current.sessions).toHaveLength(1)
    expect(result.current.sessions[0].id).toBe('session-2')
  })

  it('uses updated backendUrl after re-render', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
    })
    globalThis.fetch = fetchMock

    const { result, rerender } = renderHook(
      ({ url }) => useSessions(url),
      { initialProps: { url: 'http://old:8080' as string | null } }
    )

    await act(async () => {
      await result.current.fetchSessions()
    })
    expect(fetchMock).toHaveBeenCalledWith(
      'http://old:8080/api/sessions',
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )

    // Change URL
    rerender({ url: 'http://new:9090' })

    await act(async () => {
      await result.current.fetchSessions()
    })
    expect(fetchMock).toHaveBeenCalledWith(
      'http://new:9090/api/sessions',
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
