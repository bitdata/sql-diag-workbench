import { apiFetch } from '../utils/api'
import { useState, useCallback, useLayoutEffect, useRef } from 'react'
import type { HistoryEntry } from '../types/session'

export function useHistory(backendUrl: string | null) {
  const [entries, setEntries] = useState<HistoryEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const generation = useRef(0)
  const scope = useRef(0)
  useLayoutEffect(() => {
    generation.current++; scope.current++; setEntries([]); setLoading(false); setError(null)
    return () => { generation.current++; scope.current++ }
  }, [backendUrl])

  const fetchHistory = useCallback(async (limit = 50) => {
    if (!backendUrl) return
    const run = ++generation.current
    setLoading(true)
    try {
      const res = await apiFetch(`${backendUrl}/api/history?limit=${limit}`)
      if (!res.ok) throw new Error(`History could not be loaded (HTTP ${res.status}).`)
      const data: HistoryEntry[] = await res.json()
      if (!Array.isArray(data) || data.some(row => typeof row.id !== 'string' || typeof row.sqlText !== 'string')) throw new Error('Invalid history response.')
      if (generation.current === run) { setEntries(data); setError(null) }
    } catch (cause) {
      if (generation.current === run) setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      if (generation.current === run) setLoading(false)
    }
  }, [backendUrl])

  const addEntry = useCallback(async (sessionId: string, sqlText: string, durationMs: number | null, rowCount: number | null,
    resultCompleteness?: import('../types/execution').ResultCompleteness | null) => {
    if (!backendUrl) return
    const owner = scope.current
    try {
      const response = await apiFetch(`${backendUrl}/api/history`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: crypto.randomUUID(), sessionId, sqlText, durationMs, rowCount, resultCompleteness })
      })
      if (!response.ok) throw new Error(`History could not be saved (HTTP ${response.status}).`)
      if (scope.current === owner) await fetchHistory()
    } catch (cause) {
      if (scope.current === owner) setError(cause instanceof Error ? cause.message : String(cause))
    }
  }, [backendUrl, fetchHistory])

  return { entries, loading, error, degraded: error !== null, fetchHistory, addEntry }
}
