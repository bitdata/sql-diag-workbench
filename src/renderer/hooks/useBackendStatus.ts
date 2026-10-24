import { apiFetch } from '../utils/api'
import { useState, useEffect, useCallback } from 'react'
import type { BackendStatusEvent } from '../types/electron'

interface BackendStatus {
  status: 'UP' | 'DOWN'
  timestamp?: string
}

export function useBackendStatus() {
  const [status, setStatus] = useState<BackendStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [backendEvent, setBackendEvent] = useState<BackendStatusEvent | null>(null)

  const check = useCallback(async () => {
    if (!window.electronAPI) {
      // In browser dev mode, verify backend is actually reachable via API probe
      try {
        const backendUrl = 'http://localhost:8080'
        const res = await apiFetch(`${backendUrl}/api/connections`)
        if (res.ok) {
          setStatus({ status: 'UP' })
          setError(null)
        } else {
          setStatus({ status: 'DOWN' })
          setError(`Backend returned ${res.status}`)
        }
      } catch {
        setStatus({ status: 'DOWN' })
        setError('Cannot reach backend')
      }
      return
    }
    try {
      const result = await window.electronAPI.getBackendStatus()
      setStatus(result)
      setError(result.error ?? null)
      if (result.backendEvent) setBackendEvent(result.backendEvent)
    } catch {
      setError('Failed to reach backend')
      setStatus({ status: 'DOWN' })
    }
  }, [])

  useEffect(() => {
    check()
    const interval = setInterval(check, 10_000)
    return () => clearInterval(interval)
  }, [check])

  // Listen for backend status changes from main process
  useEffect(() => {
    if (!window.electronAPI) return
    const unsubscribe = window.electronAPI.onBackendStatusChange((event) => {
      setBackendEvent(event)
      if (event.status === 'running') {
        setStatus({ status: 'UP' })
        setError(null)
      } else if (event.status === 'crashed') {
        setStatus({ status: 'DOWN' })
        setError(event.error ?? 'Backend process crashed')
      } else if (event.status === 'error') {
        setStatus({ status: 'DOWN' })
        setError(event.error ?? 'Backend error')
      } else if (event.status === 'starting') {
        setStatus({ status: 'DOWN' })
        setError(null)
      } else if (event.status === 'stopped') {
        setStatus({ status: 'DOWN' })
        setError(null)
      }
    })
    return unsubscribe
  }, [])

  return { status, error, backendEvent, recheck: check }
}
