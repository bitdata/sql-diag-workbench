import { useCallback } from 'react'
import { isWorkspaceExitLocked } from '../utils/workspaceExitGate'
import type { FragmentNode } from '../types/fragment'
import type { WorkspaceMode } from '../types/mode'
import type { SqlDialect } from '../types/session'
import type { UseTabsApi } from './useTabs'
import type { DiagnosticsHook } from './useDiagnostics'
import { formatSql } from '../utils/formatSql'

/** Minimal structural type for the execution hook — avoids circular imports. */
interface ExecutionActions {
  loading: boolean
  execute: (sql: string, connectionId: string) => void
  analyze: (sql: string, connectionId: string, mode?: 'estimated' | 'actual') => void
  clearResult: () => void
  reset: () => void
}

interface UseEditorActionsParams {
  tabsApi: UseTabsApi
  execution: ExecutionActions
  diagnostics: DiagnosticsHook
  selectedNode: FragmentNode | null
  selectedNodeId: string | null
  selectedConnectionId: string | null
  currentMode: WorkspaceMode
  dialect: SqlDialect
  parse: (sql: string, dialect?: string) => void
  clearParseResult: () => void
  fetchPositions: (sql: string, dialect: SqlDialect) => void
  clearPositions: () => void
  selectNode: (id: string) => void
}

interface UseEditorActionsReturn {
  viewerMode: 'input' | 'browse'
  currentSql: string
  setCurrentSql: (next: string | ((prev: string) => string)) => void
  handleParse: () => void
  handleRunCurrent: () => void
  handleAnalyzeWithMode: (mode: 'estimated' | 'actual') => void
  handleSqlChange: (sql: string) => void
  handleNewSession: () => void
  handleVerifyFinding: (findingId: string, verificationSql: string) => void
  handleFormat: () => void
  handleNodeSelect: (nodeId: string | null) => void
  viewerSql: string
  viewerReadOnly: boolean
  canExecute: boolean
  isExecuting: boolean
}

/**
 * Editor state (viewerMode, SQL buffer) and user action handlers.
 *
 * `currentSql` is derived from the active tab's workspace — the per-tab
 * workspace is the single source of truth for the editor buffer.
 */
export function useEditorActions({
  tabsApi,
  execution,
  diagnostics,
  selectedNode,
  selectedNodeId,
  selectedConnectionId,
  currentMode,
  dialect,
  parse,
  clearParseResult,
  fetchPositions,
  clearPositions,
  selectNode,
}: UseEditorActionsParams): UseEditorActionsReturn {
  // Viewer mode lives in the active tab's workspace (spec §5.2): switching tabs
  // restores that tab's mode, and a newly created tab is writable by default.
  const viewerMode = tabsApi.activeWorkspace?.viewerMode ?? 'input'

  const setViewerMode = useCallback(
    (next: 'input' | 'browse') => {
      if (!tabsApi.activeSessionId) return
      tabsApi.updateWorkspace(tabsApi.activeSessionId, { viewerMode: next })
    },
    [tabsApi]
  )

  // ── SQL buffer (derived from workspace) ──
  const currentSql = tabsApi.activeWorkspace?.sql ?? ''
  const setCurrentSql = useCallback(
    (next: string | ((prev: string) => string)) => {
      if (!tabsApi.activeSessionId) return
      const prev = tabsApi.activeWorkspace?.sql ?? ''
      const v = typeof next === 'function' ? next(prev) : next
      tabsApi.updateWorkspace(tabsApi.activeSessionId, { sql: v, isDirty: true })
    },
    [tabsApi]
  )

  const handleNodeSelect = useCallback(
    (nodeId: string | null) => {
      if (nodeId == null) return
      selectNode(nodeId)
      setViewerMode('browse')
    },
    [selectNode, setViewerMode]
  )

  const handleParse = useCallback(() => {
    if (isWorkspaceExitLocked()) return
    if (tabsApi.activeWorkspace?.hydration === 'failed' || tabsApi.activeWorkspace?.hydration === 'loading') return
    if (currentSql.trim()) {
      parse(currentSql, dialect)
      execution.clearResult()
      fetchPositions(currentSql, dialect)
    }
  }, [currentSql, parse, execution, fetchPositions, dialect, tabsApi.activeWorkspace?.hydration])

  const runSql = viewerMode === 'browse' ? selectedNode?.sqlFragment ?? '' : currentSql
  const executable = viewerMode === 'browse' ? !!selectedNode?.executable : !!currentSql.trim()

  const handleRunCurrent = useCallback(() => {
    if (isWorkspaceExitLocked()) return
    if (!executable || !selectedConnectionId || execution.loading || tabsApi.booting || tabsApi.activeWorkspace?.hydration !== 'ready') return
    if (currentMode === 'performance') {
      execution.analyze(runSql, selectedConnectionId)
    } else {
      execution.execute(runSql, selectedConnectionId)
    }
  }, [executable, runSql, selectedConnectionId, currentMode, execution, tabsApi])

  const handleAnalyzeWithMode = useCallback(
    (mode: 'estimated' | 'actual') => {
      if (isWorkspaceExitLocked()) return
      if (!executable || !selectedConnectionId || execution.loading || tabsApi.booting || tabsApi.activeWorkspace?.hydration !== 'ready') return
      execution.analyze(runSql, selectedConnectionId, mode)
    },
    [executable, runSql, selectedConnectionId, execution, tabsApi]
  )

  const handleVerifyFinding = useCallback(
    (findingId: string, verificationSql: string) => {
      if (isWorkspaceExitLocked()) return
      if (selectedConnectionId) {
        diagnostics.verify(findingId, verificationSql, selectedConnectionId)
      }
    },
    [selectedConnectionId, diagnostics]
  )

  const handleSqlChange = useCallback((sql: string) => {
    setCurrentSql(sql)
  }, [setCurrentSql])

  const handleNewSession = useCallback(() => {
    if (isWorkspaceExitLocked()) return
    void tabsApi.createTab()
  }, [tabsApi])

  const handleFormat = useCallback(() => {
    if (isWorkspaceExitLocked() || viewerMode === 'browse') return
    const formatted = formatSql(currentSql, {
      dialect,
      tabWidth: 2,
      keywordCase: 'upper',
    })
    setCurrentSql(formatted)
  }, [currentSql, dialect, setCurrentSql, viewerMode])

  // ── Derived values ──
  const viewerSql = viewerMode === 'browse' && selectedNode
    ? selectedNode.sqlFragment
    : currentSql
  // PR-2 (boot race, rule #1 exhaustive sweep): OR `booting` so the editor
  // is disabled while the first-tab POST is in flight. Prevents users from
  // typing into a tab whose session doesn't exist yet on the backend.
  const viewerReadOnly = viewerMode === 'browse' || tabsApi.booting || tabsApi.activeWorkspace?.hydration === 'failed' || tabsApi.activeWorkspace?.hydration === 'loading'
  const canExecute = executable && !!selectedConnectionId && !execution.loading && !tabsApi.booting && tabsApi.activeWorkspace?.hydration === 'ready'
  const isExecuting = execution.loading

  return {
    viewerMode,
    currentSql,
    setCurrentSql,
    handleParse,
    handleRunCurrent,
    handleAnalyzeWithMode,
    handleSqlChange,
    handleNewSession,
    handleVerifyFinding,
    handleFormat,
    handleNodeSelect,
    viewerSql,
    viewerReadOnly,
    canExecute,
    isExecuting,
  }
}
