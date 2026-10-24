import { apiFetch } from '../utils/api'
import { useState, useCallback, useEffect, useRef } from 'react'
import { SqlNodePosition, SqlPositionsResponse } from '../types/fragment'

interface UseNodeSelectionOptions {
  backendUrl: string
  onSelectNode?: (nodeId: string | null) => void
}

interface UseNodeSelectionResult {
  positions: SqlNodePosition[]
  hoveredPosition: SqlNodePosition | null
  selectedPosition: SqlNodePosition | null
  loading: boolean
  error: string | null
  reset: () => void
  fetchPositions: (sql: string, dialect: string) => Promise<void>
  clearPositions: () => void
  setHoveredPosition: (pos: SqlNodePosition | null) => void
  setSelectedPosition: (pos: SqlNodePosition | null) => void
  selectNodeAtOffset: (offset: number) => SqlNodePosition | null
}

export function useNodeSelection({
  backendUrl,
  onSelectNode,
}: UseNodeSelectionOptions): UseNodeSelectionResult {
  const [positions, setPositions] = useState<SqlNodePosition[]>([])
  const [hoveredPosition, setHoveredPosition] = useState<SqlNodePosition | null>(null)
  const [selectedPosition, setSelectedPosition] = useState<SqlNodePosition | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  const fetchPositions = useCallback(async (sql: string, dialect: string) => {
    if (!sql || sql.trim().length === 0) {
      setPositions([])
      return
    }

    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    setLoading(true)
    setError(null)

    try {
      const response = await apiFetch(`${backendUrl}/api/sql/positions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sql, dialect }),
        signal: controller.signal,
      })

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({ message: 'Failed to fetch positions' }))
        throw new Error(errorData.message || `HTTP ${response.status}`)
      }

      const data: SqlPositionsResponse = await response.json()
      // Guard: only update state if this is still the latest request
      if (abortRef.current !== controller) return
      setPositions(data.nodes)
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      // Guard: only update state if this is still the latest request
      if (abortRef.current !== controller) return
      const message = err instanceof Error ? err.message : 'Unknown error'
      setError(message)
      setPositions([])
    } finally {
      // Guard: don't clear loading if a newer request is still in flight
      if (abortRef.current === controller) {
        setLoading(false)
      }
    }
  }, [backendUrl])

  const reset = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
    setPositions([])
    setHoveredPosition(null)
    setSelectedPosition(null)
    setError(null)
    setLoading(false)
  }, [])

  const clearPositions = reset // Alias for backward compat

  const selectNodeAtOffset = useCallback((offset: number): SqlNodePosition | null => {
    const found = positions.find(pos =>
      offset >= pos.start.offset && offset <= pos.end.offset
    )
    return found || null
  }, [positions])

  // Notify parent when selected position changes
  useEffect(() => {
    if (selectedPosition && onSelectNode) {
      onSelectNode(selectedPosition.nodeId)
    }
  }, [selectedPosition, onSelectNode])

  return {
    positions,
    hoveredPosition,
    selectedPosition,
    loading,
    error,
    reset,
    fetchPositions,
    clearPositions,
    setHoveredPosition,
    setSelectedPosition,
    selectNodeAtOffset,
  }
}