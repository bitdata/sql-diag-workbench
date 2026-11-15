// Every response belongs to a backend lifecycle and an operation version.
import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import { apiFetch } from '../utils/api'
import type { ConnectionConfig, ConnectionRequest, ConnectionTestResult } from '../types/connection'
type Scope = { url: string | null; alive: boolean; read: number; writes: number; generation: number;
  ui: number; activeReads: number; reconcile: boolean; tails: Map<string, Promise<unknown>> }
export function useConnections(backendUrl: string | null) {
  const [connections, setConnections] = useState<ConnectionConfig[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const owner = useRef<Scope | null>(null)
  const current = useCallback((scope: Scope) => scope.alive && owner.current === scope, [])
  const read = useCallback(async (scope: Scope, background = false): Promise<void> => {
    if (!current(scope) || !scope.url) return
    if (scope.writes) { scope.reconcile = true; return }
    const seq = ++scope.read, generation = scope.generation, ui = background ? scope.ui : ++scope.ui
    scope.activeReads++
    setLoading(true)
    const owns = () => current(scope) && scope.read === seq && scope.generation === generation && !scope.writes
    try {
      const response = await apiFetch(`${scope.url}/api/connections`)
      if (!response.ok) throw new Error(`Could not load connections (HTTP ${response.status}). Retry loading.`)
      const value = await response.json()
      if (owns()) { setConnections(value); if (!background && scope.ui === ui) setError(null) }
    } catch (e) {
      if (owns() && scope.ui === ui) setError(previous => background && previous ? previous : String(e instanceof Error ? e.message : e))
    } finally {
      scope.activeReads--
      if (owns()) setLoading(false)
    }
  }, [current])
  useLayoutEffect(() => {
    const scope: Scope = { url: backendUrl, alive: true, read: 0, writes: 0, generation: 0,
      ui: 0, activeReads: 0, reconcile: false, tails: new Map() }
    owner.current = scope
    setConnections([]); setError(null); setLoading(false)
    void read(scope)
    return () => { scope.alive = false }
  }, [backendUrl, read])
  const fetchConnections = useCallback(async () => {
    const scope = owner.current
    if (scope?.url === backendUrl) await read(scope)
  }, [backendUrl, read])
  const write = useCallback(async (url: string | null, key: string, method: string, request?: ConnectionRequest): Promise<ConnectionConfig | true | null> => {
    const scope = owner.current
    if (!scope || !scope.url || scope.url !== url || !current(scope)) return null
    const ui = ++scope.ui
    scope.generation++; scope.read++; scope.writes++
    if (scope.activeReads) scope.reconcile = true
    setLoading(false)
    const previous = scope.tails.get(key)
    let release!: () => void
    const tail = new Promise<void>(resolve => { release = resolve })
    scope.tails.set(key, tail)
    try {
      if (previous) await previous
      if (!current(scope)) return null
      const response = await apiFetch(`${scope.url}/api/connections${method === 'POST' ? '' : '/' + key}`, {
        method, ...(request ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request) } : {})
      })
      if (!response.ok) {
        const fallback = method === 'DELETE'
          ? `Could not delete connection (HTTP ${response.status}). Retry deleting.`
          : `Failed to ${method === 'POST' ? 'create' : 'update'} connection (HTTP ${response.status})`
        let message = fallback
        try {
          const body: unknown = await response.json()
          if (body && typeof body === 'object' && 'message' in body && typeof body.message === 'string') message = body.message
        } catch { /* Empty/malformed error bodies remain an observable HTTP failure. */ }
        throw new Error(message)
      }
      const value: ConnectionConfig | true = method === 'DELETE' ? true : await response.json()
      if (!current(scope)) return null
      setConnections(list => method === 'DELETE' ? list.filter(c => c.id !== key)
        : method === 'POST' ? [...list.filter(c => c.id !== (value as ConnectionConfig).id), value as ConnectionConfig]
        : list.map(c => c.id === key ? value as ConnectionConfig : c))
      if (scope.ui === ui) setError(null)
      return value
    } catch (e) {
      if (current(scope) && scope.ui === ui) setError(e instanceof Error ? e.message : 'Write failed')
      return null
    } finally {
      release()
      if (scope.tails.get(key) === tail) scope.tails.delete(key)
      scope.writes--; scope.generation++
      if (current(scope) && !scope.writes && scope.reconcile) {
        scope.reconcile = false
        void read(scope, true)
      }
    }
  }, [current, read])
  const createConnection = useCallback((request: ConnectionRequest) =>
    write(backendUrl, '@create', 'POST', request) as Promise<ConnectionConfig | null>, [backendUrl, write])
  const updateConnection = useCallback((id: string, request: ConnectionRequest) =>
    write(backendUrl, id, 'PUT', request) as Promise<ConnectionConfig | null>, [backendUrl, write])
  const deleteConnection = useCallback(async (id: string) =>
    await write(backendUrl, id, 'DELETE') === true, [backendUrl, write])
  const testConnection = useCallback(async (request: ConnectionRequest): Promise<ConnectionTestResult> => {
    const scope = owner.current
    if (!scope?.url || scope.url !== backendUrl || !current(scope)) return { success: false, error: 'Backend not connected' }
    const changed = (): ConnectionTestResult => ({ success: false, error: 'Connection context changed. Retry the test.', errorReason: 'CONNECTION_CONTEXT_CHANGED', degraded: true })
    try {
      const response = await apiFetch(`${scope.url}/api/connections/test`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request)
      })
      if (!response.ok) throw new Error(`Connection test failed (HTTP ${response.status})`)
      const result: ConnectionTestResult = await response.json()
      return current(scope) ? result : changed()
    } catch (cause) {
      return current(scope) ? { success: false, error: cause instanceof Error ? cause.message : 'Test failed' } : changed()
    }
  }, [backendUrl, current])
  return { connections, loading, error, fetchConnections, createConnection, updateConnection, deleteConnection, testConnection }
}
