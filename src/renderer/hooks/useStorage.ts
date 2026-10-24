import { useCallback, useEffect, useRef, useState } from 'react'
import { apiFetch } from '../utils/api'

export interface StoragePolicy { enabled: boolean; retentionDays: number; maxStorageMiB: number; revision: number }
export interface StorageSize { mainBytes: number; walBytes: number; shmBytes: number; totalBytes: number; reusableBytes: number; degraded: boolean; errorReason?: string | null }
export interface StorageJob { id: string; status: string; phase: string; deleted: Record<string, number>; before: StorageSize; after?: StorageSize; targetMet: boolean; errorReason?: string; skippedReasons: string[] }
export interface StorageStatus { policy: StoragePolicy; size: StorageSize; protectionReady: boolean; job?: StorageJob | null; degraded: boolean; errorReason?: string }
export interface StoragePreview { revision: number; protectedSessions: number; unknownSessions: number; expiredCounts: Record<string, number>; unknown: { id: string; name: string; updatedAt: string; estimatedBytes: number }[]; degraded: boolean; errorReason?: string }
export function useStorage(backendUrl: string | null) {
  const [status, setStatus] = useState<StorageStatus | null>(null)
  const [preview, setPreview] = useState<StoragePreview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const scope = useRef(0), revision = useRef(0), requestBusy = useRef(false)
  const read = useCallback(async (path: string, init?: RequestInit) => {
    if (!backendUrl) throw new Error('Storage backend unavailable.')
    const response = await apiFetch(`${backendUrl}/api/storage/${path}`, init)
    const data = await response.json()
    if (!response.ok) throw new Error(data.message ?? `Storage request failed (HTTP ${response.status}).`)
    return data
  }, [backendUrl])
  const refresh = useCallback(async () => {
    const owner = scope.current, run = ++revision.current
    try {
      const data: StorageStatus = await read('status')
      if (!data.policy || !data.size || typeof data.protectionReady !== 'boolean') throw new Error('Invalid storage status response.')
      if (scope.current === owner && revision.current === run) { setStatus(data); setError(null) }
    } catch (cause) { if (scope.current === owner && revision.current === run) setError(cause instanceof Error ? cause.message : String(cause)) }
  }, [read])
  useEffect(() => {
    scope.current++; revision.current++; setStatus(null); setPreview(null); setError(null); setActionError(null); setPending(false)
    if (!backendUrl) return
    void refresh()
    const timer = setInterval(() => { void refresh() }, 1500)
    return () => { clearInterval(timer); scope.current++; revision.current++ }
  }, [backendUrl, refresh])
  const action = useCallback(async (path: string, method: string, body?: unknown) => {
    if (requestBusy.current) return false
    requestBusy.current = true; setPending(true)
    const owner = scope.current
    try {
      const data = await read(path, { method, ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) })
      if (scope.current !== owner) return false
      if (path === 'preview') {
        if (!Array.isArray(data.unknown) || typeof data.revision !== 'number') throw new Error('Invalid cleanup preview.')
        setPreview(data)
      } else { setPreview(null); await refresh() }
      setActionError(null); return true
    } catch (cause) {
      if (scope.current === owner) setActionError(cause instanceof Error ? cause.message : String(cause))
      return false
    } finally { requestBusy.current = false; if (scope.current === owner) setPending(false) }
  }, [read, refresh])
  const busy = !!status?.job && ['queued', 'running'].includes(status.job.status)
  return { status, preview, error: actionError ?? error, pending, busy, refresh: async () => { setActionError(null); await refresh() },
    apply: (policy: StoragePolicy) => action('policy', 'PUT', { ...policy, expectedRevision: policy.revision }),
    loadPreview: () => action('preview', 'GET'),
    mark: (sessionIds: string[]) => action('reclaimable', 'POST', { sessionIds, revision: preview?.revision }),
    clean: () => action('cleanup', 'POST', { expectedPolicyRevision: status?.policy.revision }),
    cancel: () => status?.job ? action(`jobs/${status.job.id}`, 'DELETE') : Promise.resolve(false) }
}
export type StorageHook = ReturnType<typeof useStorage>
