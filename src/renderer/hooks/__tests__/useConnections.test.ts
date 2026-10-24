// src/renderer/hooks/__tests__/useConnections.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useConnections } from '../useConnections'
import { mockFetchResponses, lastFetchBody, resetMocks } from '../../test/testUtils'
import type { ConnectionConfig, ConnectionRequest } from '../../types/connection'

const sampleConnection: ConnectionConfig = {
  id: 'conn-1',
  name: 'Test DB',
  host: 'localhost',
  port: 5432,
  database: 'mydb',
  username: 'admin',
  dialect: 'postgresql',
  keychainRef: 'kr-1',
  readonly: false,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const sampleConnection2: ConnectionConfig = {
  ...sampleConnection,
  id: 'conn-2',
  name: 'Prod DB',
  host: 'prod.example.com',
}

const sampleRequest: ConnectionRequest = {
  name: 'New DB',
  host: 'localhost',
  port: 5432,
  database: 'newdb',
  username: 'user',
  password: 'secret',
  dialect: 'postgresql',
}

describe('useConnections', () => {
  beforeEach(() => {
    resetMocks()
  })

  it('initial state is correct', () => {
    // Auto-fetch fires but we haven't resolved yet — check synchronous initial state
    mockFetchResponses({ body: [] })
    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    // Before the effect resolves, connections start as []
    expect(result.current.connections).toEqual([])
    expect(typeof result.current.fetchConnections).toBe('function')
    expect(typeof result.current.createConnection).toBe('function')
    expect(typeof result.current.updateConnection).toBe('function')
    expect(typeof result.current.deleteConnection).toBe('function')
    expect(typeof result.current.testConnection).toBe('function')
  })

  it('auto-fetches connections on mount', async () => {
    mockFetchResponses({ body: [sampleConnection, sampleConnection2] })

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      // Let the auto-fetch effect resolve
      await Promise.resolve()
    })

    expect(result.current.connections).toEqual([sampleConnection, sampleConnection2])
    expect(result.current.error).toBeNull()
    expect(result.current.loading).toBe(false)

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    expect(fetchCalls).toHaveLength(1)
    expect(fetchCalls[0][0]).toBe('http://localhost:8080/api/connections')
  })

  it('auto-fetch sets loading=true during request, then false', async () => {
    let resolveFetch: (v: unknown) => void
    globalThis.fetch = vi.fn().mockReturnValue(
      new Promise(resolve => { resolveFetch = resolve })
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    // The useEffect has fired, loading should be true
    expect(result.current.loading).toBe(true)
    expect(result.current.connections).toEqual([])

    await act(async () => {
      resolveFetch!({
        ok: true,
        status: 200,
        json: () => Promise.resolve([sampleConnection]),
        text: () => Promise.resolve(''),
      })
    })

    expect(result.current.loading).toBe(false)
    expect(result.current.connections).toEqual([sampleConnection])
  })

  it('auto-fetch error populates error state', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error('Network failure'))

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    expect(result.current.error).toBe('Network failure')
    expect(result.current.connections).toEqual([])
    expect(result.current.loading).toBe(false)
  })

  it('does nothing when backendUrl is null', async () => {
    const { result } = renderHook(() => useConnections(null))

    await act(async () => {
      await Promise.resolve()
    })

    expect(globalThis.fetch).not.toHaveBeenCalled()
    expect(result.current.connections).toEqual([])
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('createConnection() returns ConnectionConfig and appends to list', async () => {
    const newConn: ConnectionConfig = {
      ...sampleConnection,
      id: 'conn-new',
      name: 'New DB',
    }
    // 1st response: auto-fetch, 2nd response: createConnection POST
    mockFetchResponses(
      { body: [sampleConnection] },
      { body: newConn }
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    expect(result.current.connections).toHaveLength(1)

    let created: ConnectionConfig | null = null
    await act(async () => {
      created = await result.current.createConnection(sampleRequest)
    })

    expect(created).toEqual(newConn)
    expect(result.current.connections).toHaveLength(2)
    expect(result.current.connections[1]).toEqual(newConn)
    expect(result.current.error).toBeNull()
  })

  it('createConnection() sends correct POST body and URL', async () => {
    mockFetchResponses(
      { body: [] },
      { body: sampleConnection }
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    await act(async () => {
      await result.current.createConnection(sampleRequest)
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    // 1st call: auto-fetch GET, 2nd call: createConnection POST
    expect(fetchCalls).toHaveLength(2)
    expect(fetchCalls[1][0]).toBe('http://localhost:8080/api/connections')
    expect(fetchCalls[1][1].method).toBe('POST')
    expect(fetchCalls[1][1].headers['Content-Type']).toBe('application/json')

    const body = lastFetchBody()
    expect(body).toEqual(sampleRequest)
  })

  it('createConnection() returns null and sets error on HTTP failure', async () => {
    mockFetchResponses(
      { body: [] },
      { status: 500, body: 'Internal Server Error' }
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    let created: ConnectionConfig | null = null
    await act(async () => {
      created = await result.current.createConnection(sampleRequest)
    })

    expect(created).toBeNull()
    expect(result.current.error).toContain('Failed to create connection')
    expect(result.current.error).toContain('500')
    expect(result.current.connections).toEqual([])
  })

  it('createConnection() returns null when backendUrl is null', async () => {
    const { result } = renderHook(() => useConnections(null))

    let created: ConnectionConfig | null = null
    await act(async () => {
      created = await result.current.createConnection(sampleRequest)
    })

    expect(created).toBeNull()
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('deleteConnection() returns true and removes from list', async () => {
    mockFetchResponses(
      { body: [sampleConnection, sampleConnection2] },
      { status: 204, body: {} }
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    expect(result.current.connections).toHaveLength(2)

    let deleted = false
    await act(async () => {
      deleted = await result.current.deleteConnection('conn-1')
    })

    expect(deleted).toBe(true)
    expect(result.current.connections).toHaveLength(1)
    expect(result.current.connections[0].id).toBe('conn-2')

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    expect(fetchCalls[1][0]).toBe('http://localhost:8080/api/connections/conn-1')
    expect(fetchCalls[1][1].method).toBe('DELETE')
  })

  it('deleteConnection() returns false on HTTP failure', async () => {
    mockFetchResponses(
      { body: [sampleConnection] },
      { status: 404, body: 'Not Found' }
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    let deleted = false
    await act(async () => {
      deleted = await result.current.deleteConnection('conn-nonexistent')
    })

    expect(deleted).toBe(false)
    expect(result.current.connections).toHaveLength(1)
  })

  it('deleteConnection() returns false on network error and sets error', async () => {
    mockFetchResponses({ body: [sampleConnection] })

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    // Override fetch for the delete call to simulate network error
    globalThis.fetch = vi.fn().mockRejectedValue(new Error('Connection refused'))

    let deleted = false
    await act(async () => {
      deleted = await result.current.deleteConnection('conn-1')
    })

    expect(deleted).toBe(false)
    expect(result.current.error).toBe('Connection refused')
    // List unchanged
    expect(result.current.connections).toHaveLength(1)
  })

  it('deleteConnection() returns false when backendUrl is null', async () => {
    const { result } = renderHook(() => useConnections(null))

    let deleted = false
    await act(async () => {
      deleted = await result.current.deleteConnection('conn-1')
    })

    expect(deleted).toBe(false)
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('testConnection() returns ConnectionTestResult on success', async () => {
    const testResult = { success: true, latency: 42, serverVersion: 'PostgreSQL 15.2' }
    mockFetchResponses(
      { body: [] },
      { body: testResult }
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    let returned: { success: boolean; error?: string } | null = null
    await act(async () => {
      returned = await result.current.testConnection(sampleRequest)
    })

    expect(returned).toEqual(testResult)

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    expect(fetchCalls[1][0]).toBe('http://localhost:8080/api/connections/test')
    expect(fetchCalls[1][1].method).toBe('POST')

    const body = lastFetchBody()
    expect(body).toEqual(sampleRequest)
  })

  it('testConnection() returns failure result on network error', async () => {
    mockFetchResponses({ body: [] })

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    globalThis.fetch = vi.fn().mockRejectedValue(new Error('Timeout'))

    let returned: { success: boolean; error?: string } | null = null
    await act(async () => {
      returned = await result.current.testConnection(sampleRequest)
    })

    expect(returned).toEqual({ success: false, error: 'Timeout' })
  })

  it('testConnection() returns failure when backendUrl is null', async () => {
    const { result } = renderHook(() => useConnections(null))

    let returned: { success: boolean; error?: string } | null = null
    await act(async () => {
      returned = await result.current.testConnection(sampleRequest)
    })

    expect(returned).toEqual({ success: false, error: 'Backend not connected' })
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('updateConnection() returns ConnectionConfig and replaces in list', async () => {
    const updatedConn: ConnectionConfig = {
      ...sampleConnection,
      name: 'Updated DB',
      host: 'newhost.example.com',
    }
    mockFetchResponses(
      { body: [sampleConnection] },
      { body: updatedConn }
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    expect(result.current.connections[0].name).toBe('Test DB')

    let updated: ConnectionConfig | null = null
    await act(async () => {
      updated = await result.current.updateConnection('conn-1', {
        ...sampleRequest,
        name: 'Updated DB',
      })
    })

    expect(updated).toEqual(updatedConn)
    expect(result.current.connections).toHaveLength(1)
    expect(result.current.connections[0].name).toBe('Updated DB')
    expect(result.current.connections[0].host).toBe('newhost.example.com')
  })

  it('updateConnection() sends correct PUT URL and body', async () => {
    mockFetchResponses(
      { body: [sampleConnection] },
      { body: sampleConnection }
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    await act(async () => {
      await result.current.updateConnection('conn-1', sampleRequest)
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    expect(fetchCalls[1][0]).toBe('http://localhost:8080/api/connections/conn-1')
    expect(fetchCalls[1][1].method).toBe('PUT')
    expect(fetchCalls[1][1].headers['Content-Type']).toBe('application/json')

    const body = lastFetchBody()
    expect(body).toEqual(sampleRequest)
  })

  it('updateConnection() returns null and sets error on HTTP failure', async () => {
    mockFetchResponses(
      { body: [sampleConnection] },
      { status: 422, body: 'Validation error' }
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    let updated: ConnectionConfig | null = null
    await act(async () => {
      updated = await result.current.updateConnection('conn-1', sampleRequest)
    })

    expect(updated).toBeNull()
    expect(result.current.error).toContain('Failed to update connection')
    expect(result.current.error).toContain('422')
    // List unchanged
    expect(result.current.connections[0].name).toBe('Test DB')
  })

  it('updateConnection() returns null when backendUrl is null', async () => {
    const { result } = renderHook(() => useConnections(null))

    let updated: ConnectionConfig | null = null
    await act(async () => {
      updated = await result.current.updateConnection('conn-1', sampleRequest)
    })

    expect(updated).toBeNull()
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('fetchConnections() can be called manually to re-fetch', async () => {
    mockFetchResponses(
      { body: [sampleConnection] },
      { body: [sampleConnection, sampleConnection2] }
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    // Wait for auto-fetch
    await act(async () => {
      await Promise.resolve()
    })

    expect(result.current.connections).toHaveLength(1)

    // Manual re-fetch
    await act(async () => {
      await result.current.fetchConnections()
    })

    expect(result.current.connections).toHaveLength(2)
    expect(result.current.connections[1].id).toBe('conn-2')
  })

  it('createConnection error does not clear existing connections', async () => {
    mockFetchResponses(
      { body: [sampleConnection] },
      { status: 500, body: 'Server Error' }
    )

    const { result } = renderHook(() => useConnections('http://localhost:8080'))

    await act(async () => {
      await Promise.resolve()
    })

    expect(result.current.connections).toHaveLength(1)

    await act(async () => {
      await result.current.createConnection(sampleRequest)
    })

    // Connections preserved despite error
    expect(result.current.connections).toHaveLength(1)
    expect(result.current.connections[0]).toEqual(sampleConnection)
    expect(result.current.error).toContain('Failed to create connection')
  })
})
