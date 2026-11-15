import { identityContext } from '../utils/workspaceIdentity'
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
  revision: number; instance?: string; conflict?: boolean; loadEpoch: number; timer?: ReturnType<typeof setTimeout> }
export interface FlushResult { savedSessionIds: string[]; issues: ExitIssue[]; workspaceRevisions?: Record<string, number> }

/** One serial queue per session. Its Promise covers the actual HTTP write. */
export function useWorkspacePersistence(backendUrl: string | null, tabs: Tab[],
  onStatus: (id: string, payload: string, status: 'pending' | 'saved' | 'failed', error?: string, revision?: number) => void) {
  const queues = useRef(new Map<string, Entry>())
  const drainFlight = useRef<{ signal?: AbortSignal; promise: Promise<FlushResult> } | null>(null)
  const latest = useRef({ backendUrl, tabs, onStatus })
  latest.current = { backendUrl, tabs, onStatus }
  const reconcile = useCallback(() => {
    const ids = new Set(latest.current.tabs.map(t => t.sessionId))
    for (const [id, q] of queues.current) if (!ids.has(id)) { clearTimeout(q.timer); queues.current.delete(id) }
    for (const tab of latest.current.tabs) {
      const ws = tab.workspace
      if (ws.hydration === 'failed' || ws.hydration === 'loading' || ws.recoveryBlocked || ws.readOnly) continue
      const payload = workspacePayload(ws)
      let q = queues.current.get(tab.sessionId)
      // Never create a second queue while an older write can still commit.
      if (!q || (q.loadEpoch !== (ws.loadEpoch ?? 0) || q.instance !== ws.workspaceInstanceId) && !q.flight) {
        q = { desired: payload, saved: tab.isDirty ? null : payload, loadEpoch: ws.loadEpoch ?? 0, revision: ws.workspaceRevision ?? 0, instance: ws.workspaceInstanceId }
        queues.current.set(tab.sessionId, q)
      }
      // Keep the old epoch while a write is in flight; the next reconcile resets its queue after reload.
      if (q.desired !== payload) { q.desired = payload; if (!q.conflict) q.failed = undefined }
    }
  }, [])
  const pump = useCallback((id: string): Promise<void> => {
    const q = queues.current.get(id), now = latest.current
    if (!q) return Promise.resolve()
    if (q.flight) return q.flight
    if (q.desired === q.saved || q.failed) return Promise.resolve()
    const ws = now.tabs.find(t => t.sessionId === id)?.workspace
    if (!now.backendUrl || !ws || ws.hydration === 'failed' || ws.hydration === 'loading' || ws.recoveryBlocked || ws.readOnly) return Promise.resolve()
    const payload = q.desired
    const ownsWorkspace = () => {
      const current = latest.current.tabs.find(t => t.sessionId === id)?.workspace
      return current?.workspaceInstanceId === q.instance && (current?.loadEpoch ?? 0) === q.loadEpoch
    }
    now.onStatus(id, payload, 'pending')
    q.flight = (async () => {
      try {
        const context = { ...identityContext(ws), workspaceRevision: q.revision }
        const res = await apiFetch(`${now.backendUrl}/api/sessions/${encodeURIComponent(id)}/workspace`, {
          method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...JSON.parse(payload), identityContext: context }),
        })
        if (!res.ok) {
          const failure = await res.json().catch(() => null)
          if (res.status === 409 || res.status === 404) q.conflict = true
          throw new Error(failure?.code === 'STORAGE_MAINTENANCE' ? 'STORAGE_MAINTENANCE: Storage is being compacted; retry saving after cleanup finishes.' : failure?.errorReason ?? failure?.message ?? `HTTP ${res.status}`)
        }
        const saved = await res.json()
        if (saved.id !== id || q.instance !== undefined && saved.workspaceInstanceId !== q.instance || saved.identityPolicy !== context.identityPolicy || !Number.isSafeInteger(saved.workspaceRevision) || saved.workspaceRevision !== q.revision + 1) {
          q.conflict = true
          throw new Error('Save acknowledgement is invalid; reload before saving again.')
        }
        q.revision = saved.workspaceRevision
        q.saved = payload
        if (queues.current.get(id) === q && ownsWorkspace()) latest.current.onStatus(id, payload, 'saved', undefined, q.revision)
      } catch (e) {
        q.failed = e instanceof Error ? e.message : String(e)
        if (queues.current.get(id) === q && ownsWorkspace()) latest.current.onStatus(id, payload, 'failed', q.failed)
      } finally {
        q.flight = undefined
        reconcile()
        if (queues.current.get(id) === q && q.desired !== payload && !q.failed) {
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
      // A conflict remains owned by the queue when later edits mark the UI pending.
      // Re-emit its failure for the current payload without starting another write.
      if (q.failed) {
        const ws = latest.current.tabs.find(t => t.sessionId === id)?.workspace
        if (ws && !ws.readOnly && ws.hydration === 'ready' && !ws.recoveryBlocked
            && (ws.syncStatus !== 'failed' || ws.syncError !== q.failed))
          latest.current.onStatus(id, q.desired, 'failed', q.failed)
        continue
      }
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
    if (q.conflict) return
    q.failed = undefined
    await pump(id)
  }, [pump, reconcile])
  const drainAll = useCallback(async (signal?: AbortSignal): Promise<FlushResult> => {
    reconcile()
    const targets = [...latest.current.tabs]
    const report: FlushResult = { savedSessionIds: [], issues: [], workspaceRevisions: {} }
    for (const q of queues.current.values()) { clearTimeout(q.timer); q.timer = undefined }
    let cursor = 0
    const drain = async () => {
      while (cursor < targets.length && !signal?.aborted) {
        const tab = targets[cursor++], id = tab.sessionId, ws = tab.workspace
        const issue = (code: string, message: string) => report.issues.push({ sessionId: id, tabName: tab.name, code, message })
        if (ws.hydration === 'loading' || ws.hydration === 'failed' || ws.recoveryBlocked) {
          issue('WORKSPACE_UNAVAILABLE', ws.recoveryError ?? 'Workspace restoration could not be confirmed.'); continue
        }
        if (ws.readOnly) { report.savedSessionIds.push(id); continue }
        let q = queues.current.get(id)
        if (!q) { issue('QUEUE_UNAVAILABLE', 'Workspace save queue unavailable.'); continue }
        if (q.flight) await q.flight
        if (!q.conflict) q.failed = undefined
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
          if (q.saved === q.desired) { report.savedSessionIds.push(id); report.workspaceRevisions![id] = q.revision; break }
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
    const report: FlushResult = { savedSessionIds: [], issues: [], workspaceRevisions: {} }
    for (const tab of latest.current.tabs) {
      const q = queues.current.get(tab.sessionId), ws = tab.workspace
      if (ws.readOnly) { report.savedSessionIds.push(tab.sessionId); continue }
      if (ws.hydration !== 'failed' && ws.hydration !== 'loading' && !ws.recoveryBlocked
        && q && !q.flight && !q.failed && !q.conflict && q.saved === workspacePayload(ws)) report.savedSessionIds.push(tab.sessionId)
      else report.issues.push({ sessionId: tab.sessionId, tabName: tab.name, code: 'SAVE_UNCONFIRMED',
        message: ws.recoveryError ?? q?.failed ?? 'The latest workspace save has not been confirmed.' })
    }
    return report
  }, [])
  // Callable compatibility for existing consumers, with an explicit drain contract.
  return Object.assign(retrySave, { retrySave, flushAll, getSaveReport })
}
