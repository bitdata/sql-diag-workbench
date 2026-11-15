import { apiFetch } from '../utils/api'
import { useEffect, useState, useCallback, useRef } from 'react'

interface SessionPayload {
  identityPolicy?: string
  workspaceRevision?: number
  workspaceInstanceId?: string
  sqlText: string
  fragmentTreeJson: string | null
  selectedNodeId: string | null
}

interface ActiveSessionState {
  identityPolicy?: string
  workspaceRevision?: number
  workspaceInstanceId?: string
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
  const epoch = useRef(0)
  const [state, setState] = useState<ActiveSessionState>({
    sql: '',
    fragmentTreeJson: null,
    selectedNodeId: null,
    isLoading: false,
    error: null,
    reload: () => {},
  })

  const load = useCallback(async () => {
    const requestEpoch = ++epoch.current
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
      if (requestEpoch !== epoch.current) return
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
      if (requestEpoch !== epoch.current) return
      setState((s) => ({
        ...s, identityPolicy: data.identityPolicy, workspaceRevision: data.workspaceRevision, workspaceInstanceId: data.workspaceInstanceId,
        sql: data.sqlText ?? '',
        fragmentTreeJson: data.fragmentTreeJson ?? null,
        selectedNodeId: data.selectedNodeId ?? null,
        isLoading: false,
        error: null,
      }))
    } catch (e) {
      if (requestEpoch !== epoch.current) return
      setState((s) => ({
        ...s,
        isLoading: false,
        error: e instanceof Error ? e.message : 'Failed to load session',
      }))
    }
  }, [backendUrl, activeSessionId])

  useEffect(() => {
    load()
    return () => { epoch.current++ }
  }, [load])

  return { ...state, reload: load }
}