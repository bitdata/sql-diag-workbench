import { apiFetch } from './api'

export function workspaceProfile(): { profileId: string | null; errorReason: string | null } {
  try {
    const key = 'workspace-profile.v1'
    let id = localStorage.getItem(key)
    if (id && !/^[a-f\d-]{36}$/i.test(id)) throw new Error('Stored workspace profile is invalid.')
    if (!id) { id = crypto.randomUUID(); localStorage.setItem(key, id) }
    if (localStorage.getItem(key) !== id) throw new Error('Workspace profile could not be persisted.')
    return { profileId: id, errorReason: null }
  } catch (error) { return { profileId: null, errorReason: error instanceof Error ? error.message : 'Workspace profile unavailable.' } }
}
export async function confirmProtection(backendUrl: string, ids: string[]) {
  const profile = workspaceProfile()
  if (!profile.profileId) throw new Error(profile.errorReason ?? 'Workspace profile unavailable.')
  // Validate the original persisted inventory, before useLocalStorage can substitute an empty value.
  const raw = localStorage.getItem('tabs.v1')
  if (raw !== null) {
    const inventory = JSON.parse(raw)
    if (!Array.isArray(inventory.tabSessionIds) || inventory.tabSessionIds.some((id: unknown) => typeof id !== 'string')) throw new Error('Stored tab inventory is invalid; cleanup is blocked.')
    ids = [...new Set([...ids, ...inventory.tabSessionIds])]
  }
  const response = await apiFetch(`${backendUrl}/api/storage/protection/${profile.profileId}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sessionIds: ids }),
  })
  if (!response.ok || (await response.json()).confirmed !== true) throw new Error(`Workspace protection was not confirmed (HTTP ${response.status}).`)
}
