import { apiFetch } from '../utils/api'
import { useEffect, useState, useCallback } from 'react'

interface SessionPayload {
  sqlText: string
  fragmentTreeJson: string | null
  selectedNodeId: string | null
}

interface ActiveSessionState {
  sql: string
  fragmentTreeJson: string | null
  selectedNodeId: string | null
  isLoading: boolean
  error: string | null
  reload: () => void
}

/**
 * Given an active tab's sessionId, fetches the session payload from
 * `/api/sessions/{id}` and exposes its fields. Re-fetches when the active
 * sessionId changes. Returns a sane empty state when activeSessionId is
 * null (e.g. on initial boot before any tab exists).
 *
 * The hook does NOT auto-save — that's the caller's job (App.tsx wires
 * useDebounce + PUT). It only loads.
 */
export function useActiveSession(
  backendUrl: string | null,
  activeSessionId: string | null
): ActiveSessionState {
  const [state, setState] = useState<ActiveSessionState>({
    sql: '',
    fragmentTreeJson: null,
    selectedNodeId: null,
    isLoading: false,
    error: null,
    reload: () => {},
  })

  const load = useCallback(async () => {
    if (!backendUrl || !activeSessionId) {
      setState((s) => ({
        ...s,
        sql: '',
        fragmentTreeJson: null,
        selectedNodeId: null,
        isLoading: false,
        error: null,
      }))
      return
    }
    setState((s) => ({ ...s, isLoading: true, error: null }))
    try {
      const res = await apiFetch(`${backendUrl}/api/sessions/${activeSessionId}`)
      if (res.status === 404) {
        setState((s) => ({
          ...s,
          isLoading: false,
          error: 'Session not found (may have been deleted)',
        }))
        return
      }
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = (await res.json()) as SessionPayload & {
        sqlText?: string
        fragmentTreeJson?: string | null
        selectedNodeId?: string | null
      }
      setState((s) => ({
        ...s,
        sql: data.sqlText ?? '',
        fragmentTreeJson: data.fragmentTreeJson ?? null,
        selectedNodeId: data.selectedNodeId ?? null,
        isLoading: false,
        error: null,
      }))
    } catch (e) {
      setState((s) => ({
        ...s,
        isLoading: false,
        error: e instanceof Error ? e.message : 'Failed to load session',
      }))
    }
  }, [backendUrl, activeSessionId])

  useEffect(() => {
    load()
  }, [load])

  return { ...state, reload: load }
}