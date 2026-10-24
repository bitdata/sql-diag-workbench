import { apiFetch } from '../utils/api'
import { useState, useCallback } from 'react'
import type { Session } from '../types/session'

export function useSessions(backendUrl: string | null) {
  const [sessions, setSessions] = useState<Session[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const fetchSessions = useCallback(async () => {
    if (!backendUrl) return
    setLoading(true)
    try {
      const res = await apiFetch(`${backendUrl}/api/sessions`)
      if (!res.ok) {
        const body = await res.json().catch(() => ({ message: `HTTP ${res.status}` }))
        setError(body.message ?? `HTTP ${res.status}`)
        return
      }
      const data: Session[] = await res.json()
      setSessions(data)
      setError(null)
    } catch (e: any) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [backendUrl])

  const updateSession = useCallback(async (id: string, updates: Partial<Session>) => {
    if (!backendUrl) return null
    if (['sqlText', 'dialect', 'connectionId', 'fragmentTreeJson', 'workspaceSnapshotJson'].some(key => key in updates)) {
      setError('Working state must be saved by the owning workspace queue.')
      return null
    }
    try {
      const res = await apiFetch(`${backendUrl}/api/sessions/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updates)
      })
      if (!res.ok) {
        const body = await res.json().catch(() => ({ message: `HTTP ${res.status}` }))
        setError(body.message ?? `HTTP ${res.status}`)
        return null
      }
      const updated: Session = await res.json()
      setSessions(prev => prev.map(s => s.id === id ? updated : s))
      return updated
    } catch (e: any) {
      setError(e.message)
      return null
    }
  }, [backendUrl])

  const deleteSession = useCallback(async (id: string) => {
    if (!backendUrl) return
    try {
      const res = await apiFetch(`${backendUrl}/api/sessions/${id}`, { method: 'DELETE' })
      if (!res.ok && res.status !== 404) throw new Error(`HTTP ${res.status}`)
      setSessions(prev => prev.filter(s => s.id !== id))
    } catch (e: any) {
      setError(e.message)
    }
  }, [backendUrl])

  return { sessions, loading, error, fetchSessions, updateSession, deleteSession }
}
