import { useCallback, useEffect, useState } from 'react'
import { apiFetch } from '../utils/api'
import { addEditLink, createEditLinks, type EditLink, type EditLinksState } from '../utils/editLinks'
import type { Tab } from '../types/tab'

/** Persisted links are loaded independently, so a failed link read cannot erase the graph. */
export function useEditLinks(backendUrl: string | null, tabs: Tab[]) {
  const [links, setLinks] = useState<EditLinksState>(createEditLinks)
  const [error, setError] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)
  const ids = tabs.map(t => t.sessionId).sort().join(',')
  // A rename changes the live label without changing session identity.
  const names = tabs.map(t => t.name).join('\n')
  useEffect(() => {
    if (!backendUrl) return
    let cancelled = false
    setLinks(prev => ({ ...prev, degraded: true, reason: 'LOADING' }))
    void (async () => {
      try {
        const res = await apiFetch(`${backendUrl}/api/session-edit-links?sessionIds=${encodeURIComponent(ids)}`)
        if (!res.ok) throw new Error('HTTP ' + res.status)
        const data = await res.json() as EditLink[]
        if (!Array.isArray(data)) throw new Error('Invalid edit links response')
        if (cancelled) return
        let next: EditLinksState = { ...createEditLinks(), degraded: false, reason: null }
        for (const link of data.sort((a,b) => (a.sequence ?? 0) - (b.sequence ?? 0))) next = addEditLink(next, link)
        setLinks(next); setError(null)
      } catch (e) {
        if (cancelled) return
        setLinks(prev => ({ ...prev, degraded: true, reason: 'LOAD_FAILED' }))
        setError('Could not restore edit origins: ' + (e instanceof Error ? e.message : String(e)))
      }
    })()
    return () => { cancelled = true }
  }, [backendUrl, ids, names, revision])
  const record = useCallback((link: EditLink) => {
    setLinks(prev => addEditLink(prev, link))
    setRevision(n => n + 1)
  }, [])
  return { links, record, error, retry: () => setRevision(n => n + 1) }
}
