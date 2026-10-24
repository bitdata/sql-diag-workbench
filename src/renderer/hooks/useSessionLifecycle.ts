import { useCallback, useEffect, useRef } from 'react'
import type { ParseResult } from '../types/fragment'
import type { UseTabsApi } from './useTabs'
import type { DAGState } from './dagState'
import { snapshotWorkspace } from '../utils/workspaceSnapshot'

interface UseSessionLifecycleParams {
  backendUrl: string | null
  tabsApi: UseTabsApi
  activeSessionLoading: boolean
  currentSql: string
  result: ParseResult | null
  selectedNodeId: string | null
  dialect: string
  connectionId: string | null
  parse: (sql: string, dialect?: string, internal?: boolean) => void
  dag?: DAGState
  parsedSql?: string
  parsedDialect?: string
  treeEpoch?: number
}

/** Project graph changes into workspace state. useTabs owns all network writes. */
export function useSessionLifecycle({ backendUrl, tabsApi, activeSessionLoading, result, selectedNodeId,
  parse, dialect, dag, parsedSql = '', parsedDialect, treeEpoch = 0 }: UseSessionLifecycleParams) {
  const parseRef = useRef(parse)
  parseRef.current = parse
  const fingerprints = useRef(new Map<string, { value: string; epoch: number; loadEpoch: number }>())
  const activeOwner = useRef<string | null>(null)
  const attemptedEmptyCreation = useRef(false)
  const ws = tabsApi.activeWorkspace
  const checkpointState = useRef({ ws, dag, result, selectedNodeId, parsedSql, parsedDialect, tabsApi })
  checkpointState.current = { ws, dag, result, selectedNodeId, parsedSql, parsedDialect, tabsApi }
  const checkpoint = useCallback(() => {
    const now = checkpointState.current
    if (!now.ws || !now.dag || now.ws.hydration !== 'ready' || now.ws.recoveryBlocked) return
    now.tabsApi.updateWorkspace(now.ws.sessionId, {
      workspaceSnapshotJson: snapshotWorkspace(now.ws, now.result?.tree ?? null, now.dag,
        now.selectedNodeId, now.parsedSql, now.parsedDialect as typeof now.ws.dialect),
      fragmentTreeJson: now.result?.tree ? JSON.stringify(now.result.tree) : null,
      selectedNodeId: now.selectedNodeId,
    }, true)
  }, [])
  useEffect(() => {
    if (tabsApi.tabs.length > 0) attemptedEmptyCreation.current = false
    if (tabsApi.protectionReady !== false && backendUrl && tabsApi.tabs.length === 0 && tabsApi.activeSessionId === null && !activeSessionLoading && !tabsApi.bootError && !attemptedEmptyCreation.current) {
      attemptedEmptyCreation.current = true
      void tabsApi.createTab()
    }
  }, [backendUrl, tabsApi.protectionReady, tabsApi.tabs.length, tabsApi.activeSessionId, activeSessionLoading, tabsApi.bootError])

  useEffect(() => {
    if (ws?.sql.trim() && !ws.fragmentTreeJson && !ws.workspaceSnapshotJson && !result?.tree
        && ws.hydration !== 'failed' && !ws.recoveryBlocked)
      void parseRef.current(ws.sql, dialect, true)
  }, [ws?.sessionId, ws?.loadEpoch, backendUrl, dialect])

  useEffect(() => {
    if (!ws || !dag || ws.hydration === 'failed' || ws.hydration === 'loading') return
    const json = snapshotWorkspace(ws, result?.tree ?? null, dag, selectedNodeId, parsedSql, parsedDialect as typeof ws.dialect)
    const previous = fingerprints.current.get(ws.sessionId)
    const first = activeOwner.current !== ws.sessionId || !previous || previous.loadEpoch !== (ws.loadEpoch ?? 0)
    activeOwner.current = ws.sessionId
    fingerprints.current.set(ws.sessionId, { value: json, epoch: treeEpoch, loadEpoch: ws.loadEpoch ?? 0 })
    // Loading itself is not a user change. Fresh parsing or explicit reset is.
    if (first) return
    if (previous.value === json && previous.epoch === treeEpoch) return
    if (ws.recoveryBlocked && (previous.epoch === treeEpoch || (!result?.tree && ws.sql.trim()))) return
    const newTree = previous.epoch !== treeEpoch
    tabsApi.updateWorkspace(ws.sessionId, {
      workspaceSnapshotJson: json,
      fragmentTreeJson: result?.tree ? JSON.stringify(result.tree) : null,
      selectedNodeId,
      isDirty: true,
      ...(newTree ? { recoveryBlocked: false, recoveryError: null } : {}),
    }, true)
  }, [ws?.sessionId, ws?.loadEpoch, ws?.viewerMode, ws?.workspaceMode, result?.tree, dag, selectedNodeId, parsedSql, parsedDialect, treeEpoch])

  return {
    checkpoint,
    booting: tabsApi.booting,
    bootError: tabsApi.bootError,
    retryBoot: tabsApi.retryBoot,
    sessionSyncError: ws?.syncError ? 'Could not save workspace: ' + ws.syncError : null,
    retrySessionSync: async () => { if (ws) await tabsApi.retrySave?.(ws.sessionId) },
  }
}
