import { apiFetch } from '../utils/api'
import { useState, useCallback, useEffect } from 'react'
import { ConnectionConfig, ConnectionRequest, ConnectionTestResult } from '../types/connection'

export function useConnections(backendUrl: string | null) {
  const [connections, setConnections] = useState<ConnectionConfig[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const fetchConnections = useCallback(async () => {
    if (!backendUrl) return
    setLoading(true)
    try {
      const res = await apiFetch(`${backendUrl}/api/connections`)
      if (!res.ok) throw new Error(`Could not load connections (HTTP ${res.status}). Retry loading.`)
      const data: ConnectionConfig[] = await res.json()
      setConnections(data)
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load connections')
    } finally {
      setLoading(false)
    }
  }, [backendUrl])

  useEffect(() => {
    fetchConnections()
  }, [fetchConnections])

  const createConnection = useCallback(async (request: ConnectionRequest): Promise<ConnectionConfig | null> => {
    if (!backendUrl) return null
    try {
      const res = await apiFetch(`${backendUrl}/api/connections`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
      })
      if (!res.ok) {
        const body = await res.text().catch(() => '')
        throw new Error(`Failed to create connection (HTTP ${res.status}${body ? ': ' + body : ''})`)
      }
      const config: ConnectionConfig = await res.json()
      setError(null)
      setConnections(prev => [...prev, config])
      return config
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to create connection')
      return null
    }
  }, [backendUrl])

  const updateConnection = useCallback(async (id: string, request: ConnectionRequest): Promise<ConnectionConfig | null> => {
    if (!backendUrl) return null
    try {
      const res = await apiFetch(`${backendUrl}/api/connections/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
      })
      if (!res.ok) {
        const body = await res.text().catch(() => '')
        throw new Error(`Failed to update connection (HTTP ${res.status}${body ? ': ' + body : ''})`)
      }
      const config: ConnectionConfig = await res.json()
      setError(null)
      setConnections(prev => prev.map(c => c.id === id ? config : c))
      return config
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update connection')
      return null
    }
  }, [backendUrl])

  const deleteConnection = useCallback(async (id: string): Promise<boolean> => {
    if (!backendUrl) return false
    try {
      const res = await apiFetch(`${backendUrl}/api/connections/${id}`, { method: 'DELETE' })
      if (res.ok) {
        setError(null)
        setConnections(prev => prev.filter(c => c.id !== id))
        return true
      }
      const body = await res.json()
      setError(body.message || `Could not delete connection (HTTP ${res.status}). Retry deleting.`)
      return false
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete connection')
      return false
    }
  }, [backendUrl])

  const testConnection = useCallback(async (request: ConnectionRequest): Promise<ConnectionTestResult> => {
    if (!backendUrl) return { success: false, error: 'Backend not connected' }
    try {
      const res = await apiFetch(`${backendUrl}/api/connections/test`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
      })
      if (!res.ok) throw new Error(`Connection test failed (HTTP ${res.status})`)
      return await res.json()
    } catch (e) {
      return { success: false, error: e instanceof Error ? e.message : 'Test failed' }
    }
  }, [backendUrl])

  return {
    connections,
    loading,
    error,
    fetchConnections,
    createConnection,
    updateConnection,
    deleteConnection,
    testConnection
  }
}
