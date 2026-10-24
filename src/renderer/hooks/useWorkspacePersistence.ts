import { useCallback, useEffect, useRef } from 'react'
import { apiFetch } from '../utils/api'
import type { Tab } from '../types/tab'
import type { SessionWorkspaceState } from '../types/session'
import type { ExitIssue } from '../types/workspaceExit'

export function workspacePayload(ws: SessionWorkspaceState): string {
  return JSON.stringify({ sqlText: ws.sql, dialect: ws.dialect, connectionId: ws.connectionId,
    workspaceSnapshotJson: ws.workspaceSnapshotJson ?? null })
}
type Entry = { desired: string; saved: string | null; flight?: Promise<void>; failed?: string;
  loadEpoch: number; timer?: ReturnType<typeof setTimeout> }
export interface FlushResult { savedSessionIds: string[]; issues: ExitIssue[] }

/** One serial queue per session. Its Promise covers the actual HTTP write. */
export function useWorkspacePersistence(backendUrl: string | null, tabs: Tab[],
  onStatus: (id: string, payload: string, status: 'pending' | 'saved' | 'failed', error?: string) => void) {
  const queues = useRef(new Map<string, Entry>())
  const drainFlight = useRef<{ signal?: AbortSignal; promise: Promise<FlushResult> } | null>(null)
  const latest = useRef({ backendUrl, tabs, onStatus })
  latest.current = { backendUrl, tabs, onStatus }
  const reconcile = useCallback(() => {
    const ids = new Set(latest.current.tabs.map(t => t.sessionId))
    for (const [id, q] of queues.current) if (!ids.has(id)) { clearTimeout(q.timer); queues.current.delete(id) }
    for (const tab of latest.current.tabs) {
      const ws = tab.workspace
      if (ws.hydration === 'failed' || ws.hydration === 'loading' || ws.recoveryBlocked) continue
      const payload = workspacePayload(ws)
      let q = queues.current.get(tab.sessionId)
      // Never create a second queue while an older write can still commit.
      if (!q) {
        q = { desired: payload, saved: tab.isDirty ? null : payload, loadEpoch: ws.loadEpoch ?? 0 }
        queues.current.set(tab.sessionId, q)
      }
      q.loadEpoch = ws.loadEpoch ?? 0
      if (q.desired !== payload) { q.desired = payload; q.failed = undefined }
    }
  }, [])
  const pump = useCallback((id: string): Promise<void> => {
    const q = queues.current.get(id), now = latest.current
    if (!q) return Promise.resolve()
    if (q.flight) return q.flight
    if (q.desired === q.saved || q.failed) return Promise.resolve()
    const ws = now.tabs.find(t => t.sessionId === id)?.workspace
    if (!now.backendUrl || !ws || ws.hydration === 'failed' || ws.hydration === 'loading' || ws.recoveryBlocked) return Promise.resolve()
    const payload = q.desired
    now.onStatus(id, payload, 'pending')
    q.flight = (async () => {
      try {
        const res = await apiFetch(`${now.backendUrl}/api/sessions/${encodeURIComponent(id)}/workspace`, {
          method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: payload,
        })
        if (!res.ok) {
          const failure = await res.json().catch(() => null)
          throw new Error(failure?.code === 'STORAGE_MAINTENANCE' ? 'STORAGE_MAINTENANCE: Storage is being compacted; retry saving after cleanup finishes.' : failure?.message ?? `HTTP ${res.status}`)
        }
        q.saved = payload
        if (queues.current.get(id) === q) latest.current.onStatus(id, payload, 'saved')
      } catch (e) {
        q.failed = e instanceof Error ? e.message : String(e)
        if (queues.current.get(id) === q) latest.current.onStatus(id, payload, 'failed', q.failed)
      } finally {
        q.flight = undefined
        if (queues.current.get(id) === q && q.desired !== payload) {
          q.failed = undefined
          void pump(id)
        } else if (queues.current.get(id) === q && q.failed?.startsWith('STORAGE_MAINTENANCE:')) {
          // Keep the latest desired payload, and retry once the exclusive lease can end.
          clearTimeout(q.timer)
          q.timer = setTimeout(() => {
            q.timer = undefined
            if (queues.current.get(id) !== q) return
            q.failed = undefined
            void pump(id)
          }, 1500)
        }
      }
    })()
    return q.flight
  }, [])
  useEffect(() => {
    reconcile()
    for (const [id, q] of queues.current) {
      // Navigation can dirty transient workspace fields without changing the
      // persisted payload. Reuse the confirmed version only after every older
      // write has settled; an in-flight write could still overwrite a revert.
      if (q.saved === q.desired && !q.flight && !q.failed) {
        const ws=latest.current.tabs.find(t=>t.sessionId===id)?.workspace
        if (ws?.hydration === 'ready' && !ws.recoveryBlocked &&
            (ws.isDirty || ws.syncStatus === 'pending')) latest.current.onStatus(id,q.desired,'saved')
      }
      if (q.saved === q.desired || q.failed || q.flight || q.timer) continue
      q.timer = setTimeout(() => { q.timer = undefined; void pump(id) }, 400)
    }
  }, [tabs, backendUrl, pump, reconcile])
  useEffect(() => () => { for (const q of queues.current.values()) clearTimeout(q.timer); queues.current.clear() }, [])
  const retrySave = useCallback(async (id: string) => {
    reconcile()
    const q = queues.current.get(id)
    if (!q) return
    clearTimeout(q.timer); q.timer = undefined
    if (q.flight) await q.flight
    q.failed = undefined
    await pump(id)
  }, [pump, reconcile])
  const drainAll = useCallback(async (signal?: AbortSignal): Promise<FlushResult> => {
    reconcile()
    const targets = [...latest.current.tabs]
    const report: FlushResult = { savedSessionIds: [], issues: [] }
    for (const q of queues.current.values()) { clearTimeout(q.timer); q.timer = undefined }
    let cursor = 0
    const drain = async () => {
      while (cursor < targets.length && !signal?.aborted) {
        const tab = targets[cursor++], id = tab.sessionId, ws = tab.workspace
        const issue = (code: string, message: string) => report.issues.push({ sessionId: id, tabName: tab.name, code, message })
        if (ws.hydration === 'loading' || ws.hydration === 'failed' || ws.recoveryBlocked) {
          issue('WORKSPACE_UNAVAILABLE', ws.recoveryError ?? 'Workspace restoration could not be confirmed.'); continue
        }
        let q = queues.current.get(id)
        if (!q) { issue('QUEUE_UNAVAILABLE', 'Workspace save queue unavailable.'); continue }
        if (q.flight) await q.flight
        q.failed = undefined
        while (!signal?.aborted) {
          reconcile()
          q = queues.current.get(id)
          if (!q) { issue('WORKSPACE_GONE', 'Workspace was removed while saving.'); break }
          const currentWorkspace = latest.current.tabs.find(t => t.sessionId === id)?.workspace
          if (!currentWorkspace || currentWorkspace.hydration === 'loading' || currentWorkspace.hydration === 'failed' || currentWorkspace.recoveryBlocked) {
            issue('WORKSPACE_UNAVAILABLE', 'Workspace changed or could not be restored while saving.'); break
          }
          if (q.flight) { await q.flight; continue }
          if (q.failed) { issue('SAVE_FAILED', q.failed); break }
          if (q.saved === q.desired) { report.savedSessionIds.push(id); break }
          if (!latest.current.backendUrl) { issue('BACKEND_UNAVAILABLE', 'Backend connection unavailable.'); break }
          await pump(id)
        }
      }
    }
    await Promise.all([drain(), drain()])
    return report
  }, [pump, reconcile])
  const flushAll = useCallback(async (signal?: AbortSignal): Promise<FlushResult> => {
    const existing = drainFlight.current
    if (existing) {
      if (!existing.signal?.aborted) return existing.promise
      await existing.promise
      if (signal?.aborted) return { savedSessionIds: [], issues: [{ code: 'EXIT_CANCELLED', message: 'Saving confirmation was cancelled.' }] }
      // Another caller may already own the next drain.
      if (drainFlight.current) return flushAll(signal)
    }
    const flight = { signal, promise: Promise.resolve({ savedSessionIds: [], issues: [] } as FlushResult) }
    drainFlight.current = flight
    flight.promise = drainAll(signal).finally(() => { if (drainFlight.current === flight) drainFlight.current = null })
    return flight.promise
  }, [drainAll])
  const getSaveReport = useCallback((): FlushResult => {
    const report: FlushResult = { savedSessionIds: [], issues: [] }
    for (const tab of latest.current.tabs) {
      const q = queues.current.get(tab.sessionId), ws = tab.workspace
      if (ws.hydration !== 'failed' && ws.hydration !== 'loading' && !ws.recoveryBlocked
        && q && !q.flight && q.saved === workspacePayload(ws)) report.savedSessionIds.push(tab.sessionId)
      else report.issues.push({ sessionId: tab.sessionId, tabName: tab.name, code: 'SAVE_UNCONFIRMED',
        message: ws.recoveryError ?? q?.failed ?? 'The latest workspace save has not been confirmed.' })
    }
    return report
  }, [])
  // Callable compatibility for existing consumers, with an explicit drain contract.
  return Object.assign(retrySave, { retrySave, flushAll, getSaveReport })
}
