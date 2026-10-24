import { useLocale, getLocale } from '../i18n'
import { apiFetch } from '../utils/api'
import { useEffect, useState, useCallback, useMemo, useRef } from 'react'
import type { Tab } from '../types/tab'
import type { WorkspaceMode } from '../types/mode'
import type { SessionWorkspaceState, SqlDialect } from '../types/session'
import { useLocalStorage } from './useLocalStorage'
import { readSnapshot, legacyDAG } from '../utils/workspaceSnapshot'
import { useWorkspacePersistence, workspacePayload, type FlushResult } from './useWorkspacePersistence'
import { isWorkspaceExitLocked } from '../utils/workspaceExitGate'
import type { EditLink } from '../utils/editLinks'
import { confirmProtection, workspaceProfile } from '../utils/workspaceProtection'

const STORAGE_KEY = 'tabs.v1'

const VALID_DIALECTS = new Set<SqlDialect>(['postgresql', 'mysql', 'sqlserver', 'oracle'])

interface TabsPersisted {
  tabSessionIds: string[]
  activeSessionId: string | null
  /**
   * Per-tab workspace mode (Data vs Performance). Each tab remembers the
   * mode it was last in so switching back to a tab restores the same view.
   * Tabs not present in this map default to 'data' on first read.
   */
  modeBySession: Record<string, WorkspaceMode>
}

interface UseTabsOptions {
  connectionId?: string | null
  /** App requires a durable inventory acknowledgement before reading saved workspaces. */
  requireProtection?: boolean
  /** Current dialect — needed when creating a new session. */
  dialect?: string
  /**
   * True once backend-URL resolution has SETTLED (success or failure).
   *
   * `booting` starts true (spec §6.1) and every in-flow termination of it —
   * the hydration success branch, `createTab`/`openInNewTab`'s `finally` —
   * is guarded on `backendUrl`. So a permanently-null `backendUrl` would
   * strand boot at true forever, permanently disabling the editor with no
   * banner (PR-2 review I-2: a regression against the pre-PR-2 baseline).
   * This flag is the missing "no URL is ever coming" signal.
   *
   * Defaults to false: callers that don't pass it keep the previous contract.
   */
  backendUrlSettled?: boolean
  /** Error from a failed backend-URL resolution, if any (paired with the above). */
  backendUrlError?: string | null
}

/** Boot degradation message for a backend URL that will never arrive. */
const BACKEND_URL_UNAVAILABLE =
  'Backend URL unavailable — the app is running without a backend connection.'

export interface OpenTabOptions {
  connectionId?: string | null
  /** Continuation of an edit already started before exit was requested. */
  exitContinuation?: boolean
  editOrigin?: Pick<EditLink, 'sourceSessionId' | 'sourceNodeId' | 'sourceTabName' | 'targetNodeId'>
  name?: string
  dialect?: string
  /** A caller owning a recovery stage also owns its observable failure. */
  onError?: (reason: string, code?: string) => void
  intent?: TabCreationIntent
}

/** The serialized body is immutable across retries, including its operation identity. */
export type TabCreationIntent = Readonly<{ body: string }>
export function createTabIntent(sql: string, opts: OpenTabOptions = {}): TabCreationIntent {
  return Object.freeze({ body: JSON.stringify({ creationRequestId: crypto.randomUUID(),
    workspaceProfileId: workspaceProfile().profileId,
    name: opts.name?.trim() || 'From history', sqlText: sql, dialect: opts.dialect ?? 'postgresql',
    connectionId: opts.connectionId ?? null, fragmentTreeJson: null, editOrigin: opts.editOrigin }) })
}
class TabCreationError extends Error {
  constructor(message: string, readonly code?: string) { super(message) }
}
const TERMINAL_CREATION_CODES = new Set(['CREATION_VALIDATION_ERROR', 'CREATION_REQUEST_CONFLICT', 'CREATION_TARGET_GONE'])

export interface UseTabsApi {
  protectionReady?: boolean
  refreshProtection?: () => Promise<void>
  tabs: Tab[]
  activeSessionId: string | null
  /**
   * True while the first-tab POST (boot) is in flight OR has not yet
   * resolved. Per spec §6.1: boot state is observable from mount, so
   * downstream consumers can disable typing immediately.
   */
  booting: boolean
  /**
   * Error message from a failed createTab (boot POST). null when no
   * failure. Rule #2 observable degradation — the failure is surfaced
   * here, not only logged.
   */
  bootError: string | null
  bootErrorLabel: string
  bootErrorRetryable?: boolean
  dismissBootError?: () => void
  /**
   * Re-attempt the boot createTab. No-op when there is no bootError to
   * recover from.
   */
  retryBoot: () => Promise<void>
  createTab: () => Promise<void>
  closeTab: (sessionId: string, opts?: { force?: boolean }) => Promise<void>
  renameTab: (sessionId: string, name: string) => Promise<void>
  switchTab: (sessionId: string) => void
  markDirty: (sessionId: string) => void
  markClean: (sessionId: string) => void
  /**
   * Create a tab holding `sql` and activate it. Returns the new backend
   * session id, or null when nothing was created (failure is reported through
   * `bootError` or the caller's `opts.onError`, rule #2). `opts.name` labels the tab for what it
   * is — an edit's new SQL, not history.
   */
  openInNewTab: (sql: string, opts?: OpenTabOptions) => Promise<string | null>
  /**
   * Read the persisted workspace mode for a given tab. Tabs that have
   * never been toggled return 'data' (the default).
   */
  getMode: (sessionId: string) => WorkspaceMode
  /** Persist the active mode for a given tab. */
  setMode: (sessionId: string, mode: WorkspaceMode) => void
  /** Workspace state of the currently active tab, or null if no tab is active. */
  activeWorkspace: SessionWorkspaceState | null
  /** Merge a partial workspace update into the tab identified by sessionId. */
  updateWorkspace: (sessionId: string, partial: Partial<SessionWorkspaceState>, internal?: boolean) => void
  flushWorkspaces?: (signal?: AbortSignal) => Promise<FlushResult>
  getExitState?: () => { pending: boolean; error: string | null }
  getExitSaveReport?: () => FlushResult
  retryWorkspace?: (sessionId: string) => Promise<void>
  retrySave?: (sessionId: string) => Promise<void>
}

/** Build a default workspace state for a newly created or hydrated tab. */
function buildDefaultWorkspace(params: {
  sessionId: string
  name: string
  sql?: string
  connectionId?: string | null
  dialect?: string
  fragmentTreeJson?: string | null
  workspaceSnapshotJson?: string | null
  lastSavedAt?: number | null
}): SessionWorkspaceState {
  const workspace: SessionWorkspaceState = {
    sessionId: params.sessionId,
    tabName: params.name,
    sql: params.sql ?? '',
    isDirty: false,
    lastSavedAt: params.lastSavedAt ?? null,
    connectionId: params.connectionId ?? null,
    dialect: VALID_DIALECTS.has(params.dialect as SqlDialect) ? (params.dialect as SqlDialect) : 'postgresql',
    fragmentTreeJson: params.fragmentTreeJson ?? null,
    selectedNodeId: null,
    workspaceMode: 'data',
    viewerMode: 'input',
    workspaceSnapshotJson: params.workspaceSnapshotJson ?? null,
    hydration: 'ready',
    syncStatus: 'saved',
  }
  try {
    if (params.workspaceSnapshotJson) {
      const { snapshot, selectionReason } = readSnapshot(params.workspaceSnapshotJson)
      workspace.selectedNodeId = snapshot.selectedNodeId
      workspace.viewerMode = snapshot.viewerMode
      workspace.workspaceMode = snapshot.workspaceMode
      workspace.fragmentTreeJson = snapshot.treeProjection ? JSON.stringify(snapshot.treeProjection) : null
      workspace.recoveryError = selectionReason ?? (snapshot.graph?.completeness === 'legacy-tree' ? 'Restored a legacy tree; some graph information may be missing.' : null)
    } else if (params.fragmentTreeJson) {
      legacyDAG(JSON.parse(params.fragmentTreeJson))
      workspace.recoveryError = 'Restored a legacy tree; some graph information and edit origins may be missing.'
    }
  } catch (e) {
    workspace.recoveryError = 'Could not restore workspace: ' + (e instanceof Error ? e.message : String(e))
    workspace.recoveryBlocked = true
  }
  return workspace
}

/**
 * Tab list manager. Persists tab ordering + active tab to localStorage.
 * Each tab is backed by a backend Session (1:1, see spec §2.1).
 */
export function useTabs(
  backendUrl: string | null,
  options: UseTabsOptions = {}
): UseTabsApi {
  useLocale()
  const [persisted, setPersisted] = useLocalStorage<TabsPersisted>(STORAGE_KEY, {
    tabSessionIds: [],
    activeSessionId: null,
    modeBySession: {},
  })
  const [tabs, setTabs] = useState<Tab[]>([])
  const tabsRef = useRef(tabs)
  tabsRef.current = tabs
  const persistedRef = useRef(persisted)
  persistedRef.current = persisted
  const metadataPending = useRef(0)
  const saveAbortRef = useRef<Map<string, AbortController>>(new Map())
  // PR-2 (boot race): expose observable boot state so consumers can disable
  // typing during the first-tab POST and surface a banner on failure.
  // `booting` starts true per spec §6.1 — the boot-tab POST is the first
  // createTab call, observable from mount.
  const [booting, setBooting] = useState<boolean>(true)
  const [bootError, setBootError] = useState<string | null>(null)
  const [bootErrorLabel, setBootErrorLabel] = useState('Could not create the first tab')
  const tabRequestBusy = useRef(false)
  const recoveryRef = useRef<{ intent: TabCreationIntent; label: string } | null>(null)
  const [bootErrorRetryable, setBootErrorRetryable] = useState(true)
  const closedSessions = useRef(new Set<string>())
  const closingSessions = useRef(new Set<string>())
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const metadataRecovery = useRef<(() => Promise<void>) | null>(null)
  const loadRequests = useRef(new Map<string, number>())
  const [protectionReady, setProtectionReady] = useState(!options.requireProtection)
  const [protectionError, setProtectionError] = useState<string | null>(null)
  const [protectionAttempt, setProtectionAttempt] = useState(0)
  const refreshProtection = useCallback(async () => {
    if (!options.requireProtection || !backendUrl) return
    try {
      await confirmProtection(backendUrl, persistedRef.current.tabSessionIds)
      if (mounted.current) { setProtectionError(null); setProtectionReady(true) }
    } catch (error) {
      if (mounted.current) setProtectionError(error instanceof Error ? error.message : String(error))
    }
  }, [backendUrl, options.requireProtection])
  useEffect(() => {
    if (!options.requireProtection || !backendUrl) return
    let live = true
    setProtectionReady(false)
    void confirmProtection(backendUrl, persistedRef.current.tabSessionIds).then(() => {
      if (live) { setProtectionError(null); setProtectionReady(true) }
    }).catch(error => { if (live) { setProtectionError(String(error.message ?? error)); setBooting(false) } })
    return () => { live = false }
  }, [backendUrl, options.requireProtection, protectionAttempt])

  // PR-2 (I-2 fail-safe, rule #2): terminate boot when the backend URL will
  // never arrive. Without this, `booting` (which starts true) has no reachable
  // clearing path in that state — the hydration effect early-returns on
  // `!backendUrl`, Effect 1's auto-createTab guard is false, and createTab's
  // own early return sits BEFORE its `finally`. Result was a permanently
  // read-only editor with bootError null and no banner.
  useEffect(() => {
    if (backendUrl) return
    if (!options.backendUrlSettled) return
    setBooting(false)
    setBootErrorRetryable(true)
    setBootErrorLabel('Backend connection unavailable')
    setBootError(options.backendUrlError ?? BACKEND_URL_UNAVAILABLE)
  }, [backendUrl, options.backendUrlSettled, options.backendUrlError])

  // Hydrate tabs from backend on boot: confirm each persisted sessionId
  // still exists; drop ones that 404.
  useEffect(() => {
    if (!backendUrl || !protectionReady) return
    if (persisted.tabSessionIds.length === 0) return
    let cancelled = false
    ;(async () => {
      const hydrated: Tab[] = []
      for (const id of persisted.tabSessionIds) {
        try {
          const res = await apiFetch(`${backendUrl}/api/sessions/${id}`)
          if (cancelled) return
          if (res.ok) {
            const data = await res.json()
            const lastSavedAt = Date.parse(data.updatedAt ?? new Date().toISOString())
            hydrated.push({
              sessionId: id,
              name: data.name ?? 'Untitled',
              isDirty: false,
              lastSavedAt,
              workspace: buildDefaultWorkspace({
                sessionId: id,
                name: data.name ?? 'Untitled',
                sql: data.sqlText ?? '',
                connectionId: data.connectionId,
                dialect: data.dialect,
                fragmentTreeJson: data.fragmentTreeJson,
                workspaceSnapshotJson: data.workspaceSnapshotJson,
                lastSavedAt,
              }),
            })
          } else if (res.status === 404) {
            // drop — tab no longer exists
          } else {
            // keep — unknown error, don't lose user context
            const lastSavedAt = Date.now()
            hydrated.push({
              sessionId: id,
              name: 'Untitled',
              isDirty: false,
              lastSavedAt,
              workspace: { ...buildDefaultWorkspace({ sessionId: id, name: 'Untitled', lastSavedAt }), hydration: 'failed', recoveryError: 'Could not load workspace: HTTP ' + res.status },
            })
          }
        } catch (e) {
          // network error — keep tab so user can retry
          const lastSavedAt = Date.now()
          hydrated.push({
            sessionId: id,
            name: 'Untitled',
            isDirty: false,
            lastSavedAt,
            workspace: { ...buildDefaultWorkspace({ sessionId: id, name: 'Untitled', lastSavedAt }), hydration: 'failed', recoveryError: 'Could not load workspace: ' + (e instanceof Error ? e.message : String(e)) },
          })
        }
      }
      if (!cancelled) {
        setTabs(hydrated)
        setPersisted((p) => ({
          tabSessionIds: hydrated.map((t) => t.sessionId),
          activeSessionId:
            persisted.activeSessionId && hydrated.find((t) => t.sessionId === persisted.activeSessionId)
              ? persisted.activeSessionId
              : (hydrated[0]?.sessionId ?? null),
          modeBySession: p.modeBySession ?? {},
        }))
        // PR-2 (boot race): hydration succeeded — boot path complete. Clear
        // `booting` so consumers (BootErrorBanner, viewerReadOnly) don't
        // sit at "loading" forever when localStorage had existing tabs.
        setBooting(false)
      }
    })()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backendUrl, protectionReady])

  const switchTab = useCallback((sessionId: string) => {
    if (isWorkspaceExitLocked()) return
    setPersisted((p) => ({ ...p, activeSessionId: sessionId }))
  }, [setPersisted])

  // intent -> POST -> current Session -> attach once. A failed request retains intent.
  const submitCreation = useCallback(async (intent: TabCreationIntent, label: string,
    onError?: OpenTabOptions['onError']): Promise<string | null> => {
    const reportError = (reason: string, code?: string) => {
      if (!mounted.current) return
      if (onError) { if (code) onError(reason, code); else onError(reason) }
      else {
        recoveryRef.current = { intent, label }
        setBootErrorLabel(label)
        setBootErrorRetryable(!code || !TERMINAL_CREATION_CODES.has(code))
        setBootError(reason)
      }
    }
    if (tabRequestBusy.current) {
      reportError('Another tab request is still running. Wait for it to finish and retry.')
      return null
    }
    if (options.requireProtection && (!protectionReady || !JSON.parse(intent.body).workspaceProfileId)) {
      reportError('Workspace protection could not be confirmed. Retry protection before creating a tab.')
      return null
    }
    metadataRecovery.current = null
    if (!backendUrl) {
      reportError(BACKEND_URL_UNAVAILABLE)
      return null
    }
    tabRequestBusy.current = true
    setBooting(true)
    if (!onError) { recoveryRef.current = { intent, label }; setBootError(null); setBootErrorRetryable(true) }
    try {
      const res = await apiFetch(`${backendUrl}/api/sessions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: intent.body,
      })
      if (!res.ok) {
        let reason = `HTTP ${res.status}`
        let code = res.status === 400 ? 'CREATION_VALIDATION_ERROR' : res.status === 409 ? 'CREATION_REQUEST_CONFLICT'
          : res.status === 410 ? 'CREATION_TARGET_GONE' : undefined
        try {
          const error = await res.json()
          if (typeof error.message === 'string') reason = error.message
          if (typeof error.code === 'string' && code === undefined) code = error.code
        } catch { /* HTTP status remains the observable fallback for malformed error bodies. */ }
        throw new TabCreationError(reason, code)
      }
      const created = await res.json()
      if (!created || typeof created.id !== 'string' || !created.id || typeof created.sqlText !== 'string')
        throw new TabCreationError('The tab creation response was invalid. Retry this same operation.')
      if (!mounted.current) return null
      if (closedSessions.current.has(created.id)) throw new TabCreationError('The created tab has been closed. Start a new operation.', 'CREATION_TARGET_GONE')
      if (closingSessions.current.has(created.id)) throw new TabCreationError('The created tab is being closed. Wait for closing to finish.')
      const lastSavedAt = Date.parse(created.updatedAt ?? new Date().toISOString())
      const tab: Tab = {
        sessionId: created.id,
        name: created.name ?? 'Untitled',
        isDirty: false,
        lastSavedAt,
        workspace: buildDefaultWorkspace({
          sessionId: created.id,
          name: created.name ?? 'Untitled',
          sql: created.sqlText,
          connectionId: created.connectionId,
          dialect: created.dialect,
          fragmentTreeJson: created.fragmentTreeJson,
          workspaceSnapshotJson: created.workspaceSnapshotJson,
          lastSavedAt,
        }),
      }
      setTabs((prev) => closedSessions.current.has(tab.sessionId) || prev.some(t => t.sessionId === tab.sessionId) ? prev : [...prev, tab])
      setPersisted((p) => closedSessions.current.has(tab.sessionId) ? p : ({
        ...p,
        tabSessionIds: [...new Set([...p.tabSessionIds, tab.sessionId])],
        activeSessionId: tab.sessionId,
        modeBySession: p.modeBySession ?? {},
      }))
      if (!onError) { recoveryRef.current = null; setBootError(null) }
      return tab.sessionId
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      reportError(msg, e instanceof TabCreationError ? e.code : undefined)
      return null
    } finally {
      tabRequestBusy.current = false
      if (mounted.current) setBooting(false)
    }
  }, [backendUrl, setPersisted, options.requireProtection, protectionReady])

  const createTab = useCallback(async () => {
    if (options.requireProtection && !protectionReady) return
    if (isWorkspaceExitLocked()) return
    if (tabRequestBusy.current) return
    const current = tabsRef.current.find(t => t.sessionId === persistedRef.current.activeSessionId)?.workspace
    const intent = createTabIntent('', { name: `Untitled ${tabsRef.current.length + 1}`, dialect: current?.dialect ?? options.dialect,
      connectionId: current ? current.connectionId : options.connectionId })
    await submitCreation(intent, tabsRef.current.length ? 'Could not create a new tab' : 'Could not create the first tab')
  }, [options.dialect, options.connectionId, options.requireProtection, protectionReady, submitCreation])

  const openInNewTab = useCallback(async (sql: string, opts: OpenTabOptions = {}): Promise<string | null> => {
    if (isWorkspaceExitLocked() && !opts.exitContinuation) {
      opts.onError?.('Application exit is in progress. Cancel exit to open a tab.')
      return null
    }
    const intent = opts.intent ?? createTabIntent(sql, { ...opts, dialect: opts.dialect ?? options.dialect })
    return submitCreation(intent, 'Could not open SQL in a new tab', opts.onError)
  }, [options.dialect, submitCreation])

  const retryBoot = useCallback(async (): Promise<void> => {
    if (protectionError) { setProtectionAttempt(n => n + 1); return }
    if (isWorkspaceExitLocked()) return
    if (!bootError || !bootErrorRetryable || tabRequestBusy.current) return
    const recovery = recoveryRef.current
    if (metadataRecovery.current) await metadataRecovery.current()
    else if (recovery) await submitCreation(recovery.intent, recovery.label)
    else await createTab()
  }, [protectionError, bootError, bootErrorRetryable, createTab, submitCreation])

  const dismissBootError = useCallback(() => {
    if (tabRequestBusy.current) return
    recoveryRef.current = null
    metadataRecovery.current = null
    setBootError(null)
    setBootErrorRetryable(true)
  }, [])

  const closeTab = useCallback(
    async (sessionId: string, opts: { force?: boolean } = {}) => {
      if (isWorkspaceExitLocked()) return
      const tab = tabs.find((t) => t.sessionId === sessionId)
      if (!tab) return
      if (tab.isDirty && !opts.force) {
        console.warn('closeTab: tab is dirty, refusing without force=true')
        return
      }
      loadRequests.current.set(sessionId, (loadRequests.current.get(sessionId) ?? 0) + 1)
      if (!backendUrl) {
        setBootErrorRetryable(true)
        setBootErrorLabel('Could not close the tab'); setBootError(BACKEND_URL_UNAVAILABLE)
        metadataRecovery.current = () => closeTab(sessionId, opts)
        return
      }
      if (backendUrl) {
        closingSessions.current.add(sessionId)
        try {
          const res = await apiFetch(`${backendUrl}/api/sessions/${sessionId}`, {
            method: 'DELETE',
          })
          if (!res.ok && res.status !== 404) throw new Error('HTTP ' + res.status)
        } catch (e) {
          closingSessions.current.delete(sessionId)
          setBootErrorRetryable(true)
          setBootErrorLabel('Could not close the tab')
          setBootError(e instanceof Error ? e.message : String(e))
          metadataRecovery.current = () => closeTab(sessionId, opts)
          return
        }
        closingSessions.current.delete(sessionId)
        closedSessions.current.add(sessionId)
      }
      // Cancel any pending save for this tab.
      saveAbortRef.current.get(sessionId)?.abort()
      saveAbortRef.current.delete(sessionId)
      metadataRecovery.current = null
      setBootError(null)
      const removedIdx = tabsRef.current.findIndex((t) => t.sessionId === sessionId)
      const nextTabs = tabsRef.current.filter((t) => t.sessionId !== sessionId)
      setTabs(prev => prev.filter(t => t.sessionId !== sessionId))
      setPersisted((p) => {
        // Also drop the closed tab's mode so the slot doesn't linger forever.
        const { [sessionId]: _gone, ...remaining } = p.modeBySession ?? {}
        void _gone
        return {
          tabSessionIds: p.tabSessionIds.filter(id => id !== sessionId),
          activeSessionId: p.activeSessionId === sessionId
            ? (nextTabs[Math.min(Math.max(removedIdx, 0), nextTabs.length - 1)]?.sessionId ?? null)
            : p.activeSessionId,
          modeBySession: remaining,
        }
      })
    },
    [backendUrl, tabs, persisted.activeSessionId, setPersisted]
  )

  const renameTab = useCallback(
    async (sessionId: string, name: string) => {
      if (isWorkspaceExitLocked()) return
      if (!backendUrl) return
      metadataPending.current++
      setTabs((prev) => prev.map((t) => (t.sessionId === sessionId ? { ...t, name } : t)))
      try {
        const res = await apiFetch(`${backendUrl}/api/sessions/${sessionId}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name }),
        })
        if (!res.ok) throw new Error('HTTP ' + res.status)
        setTabs(prev => prev.map(t => t.sessionId === sessionId ? { ...t, workspace: { ...t.workspace, tabName: name } } : t))
        metadataRecovery.current = null
        setBootError(null)
      } catch (e) {
        setBootErrorRetryable(true)
        setBootErrorLabel('Could not rename the tab')
        setBootError(e instanceof Error ? e.message : String(e))
        metadataRecovery.current = () => renameTab(sessionId, name)
      } finally {
        metadataPending.current--
      }
    },
    [backendUrl]
  )

  const markDirty = useCallback((sessionId: string) => {
    setTabs((prev) =>
      prev.map((t) => (t.sessionId === sessionId ? { ...t, isDirty: true } : t))
    )
  }, [])

  const markClean = useCallback((sessionId: string) => {
    setTabs((prev) =>
      prev.map((t) =>
        t.sessionId === sessionId ? { ...t, isDirty: false, lastSavedAt: Date.now() } : t
      )
    )
  }, [])

  const getMode = useCallback(
    (sessionId: string): WorkspaceMode => {
      return tabs.find(t => t.sessionId === sessionId)?.workspace.workspaceMode ?? persisted.modeBySession?.[sessionId] ?? 'data'
    },
    [persisted.modeBySession, tabs]
  )

  const setMode = useCallback(
    (sessionId: string, mode: WorkspaceMode) => {
      if (isWorkspaceExitLocked()) return
      setTabs(prev => prev.map(t => t.sessionId === sessionId && t.workspace.workspaceMode !== mode
        ? { ...t, isDirty: true, workspace: { ...t.workspace, workspaceMode: mode, isDirty: true, syncStatus: 'pending' } } : t))
      setPersisted((p) => {
        const safe = p.modeBySession ?? {}
        if (safe[sessionId] === mode) return p
        return { ...p, modeBySession: { ...safe, [sessionId]: mode } }
      })
    },
    [setPersisted]
  )

  // Compute activeWorkspace from the active tab
  const activeWorkspace = useMemo<SessionWorkspaceState | null>(() => {
    const activeTab = tabs.find((t) => t.sessionId === persisted.activeSessionId)
    return activeTab?.workspace ?? null
  }, [tabs, persisted.activeSessionId, getLocale()])

  const updateWorkspace = useCallback(
    (sessionId: string, partial: Partial<SessionWorkspaceState>, internal = false) => {
      if (isWorkspaceExitLocked() && !internal) return
      // Explicit reset supersedes an in-flight reload of the same workspace.
      if (partial.hydration === 'ready' && tabsRef.current.find(t => t.sessionId === sessionId)?.workspace.hydration === 'loading') {
        loadRequests.current.set(sessionId, (loadRequests.current.get(sessionId) ?? 0) + 1)
      }
      setTabs((prev) =>
        prev.map((tab) => {
          if (tab.sessionId !== sessionId) return tab
          const changed = Object.entries(partial).some(([key, value]) => key !== 'isDirty'
            && key !== 'syncStatus' && tab.workspace[key as keyof SessionWorkspaceState] !== value)
          if (!changed) return tab
          return {
                ...tab,
                workspace: { ...tab.workspace, isDirty: true, syncStatus: 'pending', ...partial },
                // Sync top-level fields from workspace changes
                name: partial.tabName ?? tab.name,
                isDirty: partial.isDirty !== undefined ? partial.isDirty : true,
                lastSavedAt:
                  partial.lastSavedAt !== undefined ? (partial.lastSavedAt ?? tab.lastSavedAt) : tab.lastSavedAt,
              }
        })
      )
    },
    []
  )

  const onSyncStatus = useCallback((id: string, payload: string, status: 'pending' | 'saved' | 'failed', error?: string) => {
    setTabs(prev => prev.map(t => {
      if (t.sessionId !== id || workspacePayload(t.workspace) !== payload) return t
      const clean = status === 'saved'
      return { ...t, isDirty: !clean, lastSavedAt: clean ? Date.now() : t.lastSavedAt,
        workspace: { ...t.workspace, isDirty: !clean, syncStatus: status, syncError: error ?? null } }
    }))
  }, [])
  const retrySave = useWorkspacePersistence(backendUrl, tabs, onSyncStatus)
  const flushWorkspaces = useCallback(async (signal?: AbortSignal): Promise<FlushResult> => {
    const result = await retrySave.flushAll(signal)
    if (signal?.aborted) return result
    try {
      const body = JSON.stringify(persistedRef.current)
      localStorage.setItem(STORAGE_KEY, body)
      if (localStorage.getItem(STORAGE_KEY) !== body) throw new Error('Tab list was not persisted.')
    } catch (e) {
      result.issues.push({ code: 'TAB_LIST_SAVE_FAILED', message: e instanceof Error ? e.message : String(e) })
    }
    return result
  }, [retrySave])
  const exitState = useRef({ pending: booting, error: bootError })
  exitState.current = { pending: booting || (!!options.requireProtection && !protectionReady && !protectionError), error: protectionError ?? bootError }
  const getExitState = useCallback(() => ({
    pending: exitState.current.pending || tabRequestBusy.current || metadataPending.current > 0 || closingSessions.current.size > 0
      || tabsRef.current.some(t => t.workspace.hydration === 'loading'),
    error: exitState.current.error,
  }), [])
  const retryWorkspace = useCallback(async (id: string) => {
    if (isWorkspaceExitLocked()) return
    if (!backendUrl) return
    const epoch = (loadRequests.current.get(id) ?? 0) + 1
    loadRequests.current.set(id, epoch)
    setTabs(prev => prev.map(t => t.sessionId === id ? { ...t, workspace: { ...t.workspace, hydration: 'loading' } } : t))
    try {
      const res = await apiFetch(`${backendUrl}/api/sessions/${encodeURIComponent(id)}`)
      if (!res.ok) throw new Error('HTTP ' + res.status)
      const data = await res.json()
      if (loadRequests.current.get(id) !== epoch) return
      setTabs(prev => prev.map(t => t.sessionId === id ? { ...t, isDirty: false,
        workspace: { ...buildDefaultWorkspace({ sessionId: id, name: data.name, sql: data.sqlText,
          dialect: data.dialect, connectionId: data.connectionId, fragmentTreeJson: data.fragmentTreeJson,
          workspaceSnapshotJson: data.workspaceSnapshotJson }), loadEpoch: (t.workspace.loadEpoch ?? 0) + 1 } } : t))
    } catch (e) {
      if (loadRequests.current.get(id) !== epoch) return
      setTabs(prev => prev.map(t => t.sessionId === id ? { ...t, workspace: { ...t.workspace,
        hydration: 'failed', recoveryError: 'Could not load workspace: ' + (e instanceof Error ? e.message : String(e)) } } : t))
    }
  }, [backendUrl])

  return {
    protectionReady,
    refreshProtection,
    tabs,
    activeSessionId: persisted.activeSessionId,
    booting,
    bootError: protectionError ?? bootError,
    retryBoot,
    createTab,
    closeTab,
    renameTab,
    switchTab,
    markDirty,
    markClean,
    openInNewTab,
    bootErrorLabel: protectionError ? 'Workspace protection could not be confirmed' : bootErrorLabel,
    bootErrorRetryable,
    dismissBootError,
    getMode,
    setMode,
    activeWorkspace,
    updateWorkspace,
    retryWorkspace,
    retrySave,
    flushWorkspaces,
    getExitSaveReport: retrySave.getSaveReport,
    getExitState,
  }
}
