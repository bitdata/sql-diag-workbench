import { tr, useLocale, getLocale } from './i18n'
import { useState, useCallback, useEffect, useMemo, useRef } from 'react'
import { flushSync } from 'react-dom'
import { useBackendStatus } from './hooks/useBackendStatus'
import { useBackendUrl, useBackendUrlState } from './hooks/useBackendUrl'
import { useLocalStorage } from './hooks/useLocalStorage'
import { useFragmentTree } from './hooks/useFragmentTree'
import { ConnectionProvider, useConnection } from './context/ConnectionContext'
import { useExecution } from './hooks/useExecution'
import { useFragmentCompare } from './hooks/useFragmentCompare'
import CompareView, { type CompareHandle } from './components/Compare/CompareView'
import { useTaskNotices, dismissTaskNotice } from './utils/taskNotices'
import { useDiagnostics } from './hooks/useDiagnostics'
import { useSessions } from './hooks/useSessions'
import { useHistory } from './hooks/useHistory'
import { useKeyboardShortcuts } from './hooks/useKeyboardShortcuts'
import { usePanelResize } from './hooks/usePanelResize'
import { useNodeSelection } from './hooks/useNodeSelection'
import { useTabs } from './hooks/useTabs'
import { useRowTrace } from './hooks/useRowTrace'
import { useEditorActions } from './hooks/useEditorActions'
import { useSessionLifecycle } from './hooks/useSessionLifecycle'
import { useWorkspaceExit } from './hooks/useWorkspaceExit'
import { useStorage } from './hooks/useStorage'
import { isWorkspaceExitLocked } from './utils/workspaceExitGate'
import WorkspaceExitDialog from './components/WorkspaceExitDialog'
import { useLineageChain } from './hooks/useLineageChain'
import { useEditLinks } from './hooks/useEditLinks'
import { useFragmentEditFeedback } from './hooks/useFragmentEditFeedback'
import { resolveEditMark, resolveEditOrigin, type EditMark } from './utils/editLinks'
import FragmentTree from './components/TreeView/FragmentTree'
import BuildPanel from './components/BuildPanel/BuildPanel'
import { useAddBackExploration } from './hooks/useAddBackExploration'
import { computeAddBackCandidates } from './utils/addBackProjection'
import { ExperimentContext } from './components/Editor/ExperimentContext'
import SqlViewer from './components/Editor/SqlViewer'
import ConnectionManager from './components/Connection/ConnectionManager'
import ResultsPanel from './components/Results/ResultsPanel'
import HistoryDock from './components/History/HistoryDock'
import LineageChip from './components/Lineage/LineageChip'
import TabBar from './components/Tabs/TabBar'
import OverlayHost, { type OverlayHandle } from './components/Overlay/OverlayHost'
import ModeToggle from './components/ModeToggle/ModeToggle'
import ExecutionStatusInline from './components/Editor/ExecutionStatusInline'
import { AnalyzeSplitButton } from './components/Editor/AnalyzeSplitButton'
import { BootErrorBanner } from './components/BootErrorBanner'
import type { Tab } from './types/tab'
import type { WorkspaceMode } from './types/mode'
import type { AppSettings, SqlDialect } from './types/session'
import { ExperimentSourcesContext } from './components/Editor/ExperimentSources'
import { ConnectionRequest } from './types/connection'
import { FragmentNode } from './types/fragment'
import { LineageNode } from './types/lineage'
import './App.css'
import './components/Editor/EditorToolbar.css'

// Helper: immutably update a single node's executionSummary in the tree.
// Only clones nodes on the path from root to target; returns the original
// tree reference when nothing changed so callers can short-circuit.
function updateNodeExecutionSummary(
  tree: FragmentNode,
  nodeId: string,
  summary: import('./hooks/useExecution').ExecutionSummaryLike
): FragmentNode {
  if (tree.id === nodeId) {
    return { ...tree, executionSummary: summary }
  }
  let changed = false
  const nextChildren = tree.children.map((c) => {
    const result = updateNodeExecutionSummary(c, nodeId, summary)
    if (result !== c) changed = true
    return result
  })
  if (!changed) return tree
  return { ...tree, children: nextChildren }
}

// Helper: collect all nodes from tree as LineageNode[]
// Defined outside component to avoid re-creation on every render
function collectLineageNodes(tree: FragmentNode): LineageNode[] {
  const nodes: LineageNode[] = []

  function walk(node: FragmentNode) {
    nodes.push({
      id: node.id,
      label: node.label,
      type: node.type,
      parentIds: [],
      transform: node.ruleName ? { kind: 'DECOMPOSE' as const, description: node.label } : undefined,
    })
    node.children.forEach(walk)
  }

  walk(tree)
  return nodes
}

// Helper: collect all FragmentNode instances from a tree (preserves lineage
// fields like canonicalSql / editParentId / createdAt / executionSummary).
function collectLineageNodesAsFragments(tree: FragmentNode): FragmentNode[] {
  const out: FragmentNode[] = []
  function walk(node: FragmentNode) {
    out.push(node)
    node.children.forEach(walk)
  }
  walk(tree)
  return out
}

// ─── App Component ─────────────────────────────────────────────────────────

export default function App() {
  useLocale()
  const backendUrl = useBackendUrl()
  return (
    <ConnectionProvider backendUrl={backendUrl}>
      <AppInner />
    </ConnectionProvider>
  )
}

function AppInner() {
  useLocale()
  const { status, error: backendError } = useBackendStatus()
  // PR-2 (I-2 fail-safe): AppInner needs the resolution lifecycle, not just
  // the URL — `useTabs` must be able to tell "no URL yet" from "no URL ever".
  const { url: backendUrl, settled: backendUrlSettled, error: backendUrlError } = useBackendUrlState()
  const {
    connections,
    connError,
    selectedConnectionId: defaultConnectionId,
    setSelectedConnectionId,
    createConnection,
    updateConnection,
    deleteConnection,
    testConnection,
    fetchConnections,
  } = useConnection()
  // ── App settings ──
  // App-level preferences persisted in localStorage. Per-tab state (SQL
  // buffer, selectedNodeId, sessionId) lives in SessionWorkspaceState via
  // useTabs — it is NOT part of app settings.
  const [appSettings, setAppSettings] = useLocalStorage<AppSettings>('app-settings', {
    theme: 'dark',
    dialect: 'postgresql',
    rowLimit: 1000,
    queryTimeout: 30,
    autoCleanupDays: 30,
  })

  // Editor / UI settings. Declared before execution and multi-tab hooks
  // because they need dialect for new backend sessions and rowLimit /
  // queryTimeout for the execution hook. Initialized from appSettings
  // (localStorage) so user preferences survive app restarts.
  const [settings, setSettings] = useState<{
    dialect: SqlDialect
    rowLimit: number
    queryTimeout: number
    autoCleanupDays: number
    theme: 'dark' | 'light' | 'high-contrast'
  }>({
    dialect: appSettings.dialect,
    rowLimit: appSettings.rowLimit,
    queryTimeout: appSettings.queryTimeout,
    autoCleanupDays: appSettings.autoCleanupDays,
    theme: appSettings.theme,
  })

  // Multi-tab: tabsApi owns the tab list + activeSessionId; activeSession
  // loads the active session's payload (sqlText, fragmentTreeJson, ...).
  // currentSql is derived from tabsApi.activeWorkspace.sql — the per-tab
  // workspace is the single source of truth for the editor buffer.
  const tabsApi = useTabs(backendUrl, {
    requireProtection: true,
    dialect: settings.dialect,
    connectionId: defaultConnectionId,
    // PR-2 (I-2 fail-safe): lets useTabs terminate boot when the URL never
    // arrives, instead of leaving `booting` stuck true (editor locked forever).
    backendUrlSettled,
    backendUrlError,
  })

  const selectedConnectionId = tabsApi.activeWorkspace?.connectionId ?? null
  const queryConnection = connections.find(c => c.id === selectedConnectionId)
  const dialects = ['postgresql', 'mysql', 'sqlserver', 'oracle'] as const
  const effectiveDialect: SqlDialect = dialects.includes(queryConnection?.dialect as SqlDialect)
    ? queryConnection!.dialect as SqlDialect : tabsApi.activeWorkspace?.dialect ?? settings.dialect
  const isBrowsing = tabsApi.activeWorkspace?.viewerMode === 'browse'
  useEffect(() => {
    const ws = tabsApi.activeWorkspace
    if (ws?.hydration === 'ready' && ws.dialect !== effectiveDialect) {
      tabsApi.updateWorkspace(ws.sessionId, { dialect: effectiveDialect, isDirty: true })
    }
  }, [tabsApi.activeWorkspace?.sessionId, tabsApi.activeWorkspace?.hydration, tabsApi.activeWorkspace?.dialect, effectiveDialect])

  const {
    result,
    loading: parseLoading,
    error: parseError,
    warning: decompositionWarning,
    selectedNodeId,
    selectedNode,
    optionsCache,
    optionsLoading,
    decomposing,
    parse,
    selectNode,
    clearResult: clearParseResult,
    decomposeNode,
    submitUserEdit,
    dag,
    treeEpoch,
    parsedSql,
    parsedDialect,
    setResult,
  } = useFragmentTree(backendUrl, tabsApi.activeWorkspace)

  const execution = useExecution(
    backendUrl,
    isBrowsing ? selectedNodeId : '$editor',
    (summary, owner) => {
      if (isWorkspaceExitLocked()) return
      if (owner.sessionId !== tabsApi.activeSessionId || owner.revision !== treeEpoch || owner.connectionId !== selectedConnectionId
          || dag.nodeMap.get(owner.nodeId)?.sqlFragment !== owner.sql) return
      // Runtime cache for LineagePanel fallback (legacy nodes without the field).
      setExecutionSummaries((prev) => ({ ...prev, [owner.nodeId]: summary }))
      // Write executionSummary into the FragmentNode tree so it persists with
      // the session auto-save. The lifecycle projection feeds the unified
      // workspace queue, which saves SQL and graph in the same transaction.
      setResult((prev) => {
        if (!prev?.tree) return prev
        const updatedTree = updateNodeExecutionSummary(prev.tree, owner.nodeId, summary)
        if (updatedTree === prev.tree) return prev
        return { ...prev, tree: updatedTree }
      })
    },
    { maxRows: settings.rowLimit, queryTimeout: settings.queryTimeout },
    { sessionId: tabsApi.activeSessionId, dialect: effectiveDialect, connectionId: selectedConnectionId,
      sql: isBrowsing ? selectedNode?.sqlFragment : tabsApi.activeWorkspace?.sql, revision: treeEpoch }
  )

  // Diagnostics: lifted out of ResultsPanel so App can auto-trigger an
  // analyze() whenever a plan lands in Performance mode. The hook is still
  // stable per render — ResultsPanel only consumes the returned state.
  const diagnosticScope = JSON.stringify([tabsApi.activeSessionId, selectedNodeId, selectedConnectionId, treeEpoch])
  const diagnostics = useDiagnostics(backendUrl, diagnosticScope)

  // Row trace: async polling hook for Expected Row Trace feature.
  const rowTrace = useRowTrace(backendUrl, JSON.stringify([tabsApi.activeSessionId, selectedConnectionId, treeEpoch]))

  const sessions = useSessions(backendUrl)
  const history = useHistory(backendUrl)
  const storage = useStorage(backendUrl)
  useEffect(() => {
    if (storage.status?.protectionReady === false && tabsApi.protectionReady && !tabsApi.bootError) {
      void tabsApi.refreshProtection?.()
    }
  }, [storage.status?.protectionReady, tabsApi.protectionReady, tabsApi.bootError, tabsApi.refreshProtection])
  useEffect(() => {
    if (!backendUrl) return
    const timer = setInterval(() => { void history.fetchHistory() }, 5000)
    return () => clearInterval(timer)
  }, [backendUrl, history.fetchHistory])

  // Workspace mode is per-tab (see memory 2026-06-25 / useTabs.getMode).
  // Switching tabs restores the mode that was active when the tab was
  // last used; new tabs default to 'data'.
  const currentMode: WorkspaceMode = tabsApi.activeSessionId
    ? tabsApi.getMode(tabsApi.activeSessionId)
    : 'data'
  const handleModeChange = useCallback(
    (next: WorkspaceMode) => {
      if (!tabsApi.activeSessionId) return
      tabsApi.setMode(tabsApi.activeSessionId, next)
    },
    [tabsApi]
  )

  // Dedup ref (spec work item 4): the diagnostics auto-trigger must not
  // re-fire for the same (node, plan) pair. planResult is double-cached
  // (dataResultCache / planResultCache) and retains its reference across
  // data↔performance mode switches, so without this guard a
  // Performance→Data→Performance round-trip re-POSTs /api/diagnostics/analyze
  // for an unchanged plan. Plan identity is compared by reference (===): a
  // new analyze() produces a new plan object; a mode round-trip reuses it.
  const lastDiagRef = useRef<{ nodeId: string; plan: unknown; scope: string } | null>(null)

  // Auto-trigger: when in Performance mode and a fresh plan has arrived
  // for the active node, ask the backend to diagnose it. Re-runs only when
  // the plan reference changes (a new analyze), not on every render.
  useEffect(() => {
    if (currentMode !== 'performance') return
    const plan = execution.planResult?.plan
    const diagnosticNode = isBrowsing ? selectedNode : parsedSql === tabsApi.activeWorkspace?.sql ? result?.tree : null
    if (!plan || !diagnosticNode || parsedDialect !== effectiveDialect) return
    const last = lastDiagRef.current
    if (last && last.nodeId === diagnosticNode.id && last.plan === plan && last.scope === diagnosticScope) return
    lastDiagRef.current = { nodeId: diagnosticNode.id, plan, scope: diagnosticScope }
    void diagnostics.analyze(diagnosticNode, plan, execution.planResult?.timing ?? null, tabsApi.activeWorkspace?.sessionId ?? null, { executionId: execution.planResult!.executionId, connectionId: selectedConnectionId })
    // Intentionally omit `diagnostics` from deps — it's a stable useCallback
    // identity inside useDiagnostics, so re-running on identity churn would
    // re-fire the request every render. We only want to fire when the plan
    // itself changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentMode, execution.planResult, selectedNode, diagnosticScope, isBrowsing, parsedDialect, effectiveDialect, parsedSql, tabsApi.activeWorkspace?.sql, result?.tree])

  // ── 加回探索（spec §4.3）──
  // 投影是**渲染期视图**：§4.3.7 —— 它从不写 result.tree，故 useSessionLifecycle
  // 不可能把换根后的（或含合成 id 的）树持久化成会话树。
  //
  // 探索态的失效判据是「树**实例**」而不是「树内容」：切了 Tab（不同 session）
  // 或本 Tab 重新解析（treeEpoch +1）都算换了实例，哪怕新树的根 id 与旧树逐字
  // 相同（id 是内容派生的，见 useFragmentTree 的 treeEpoch 注释；规则 #1 同类
  // 站点）。之所以不用 `dag` 对象身份：`decomposeNode` 也会克隆出一棵新 dag，
  // 那是「在当前树上继续拆」，按既有契约不该踢出探索态。
  const treeInstanceKey = `${tabsApi.activeSessionId ?? ''}#${treeEpoch}`

  const {
    active: addBackActive,
    seedNodeId: addBackSeedId,
    tree: addBackTree,
    breadcrumb: addBackBreadcrumb,
    reachesOrigin: addBackReachesOrigin,
    isOriginRoot: addBackIsOriginRoot,
    skipped: addBackSkipped,
    degraded: addBackDegraded,
    reason: addBackReason,
    index: addBackIndex,
    enter: enterAddBack,
    exit: exitAddBack,
    toggle: toggleAddBack,
    isRevealed: isEdgeRevealed,
  } = useAddBackExploration({
    dag,
    tree: result?.tree ?? null,
    treeInstanceKey,
    selectNode,
  })

  const [buildPanelOpen, setBuildPanelOpen] = useState(false)
  const [evidenceReveal, setEvidenceReveal] = useState<{ id: string; sequence: number } | null>(null)
  const displayedTree = addBackActive ? addBackTree : (result?.tree ?? null)

  const handleBuildUpward = useCallback((nodeId: string) => {
    // enter() 内部已 selectNode(seed)；已激活时只切换选中面。
    if (!addBackActive) enterAddBack(nodeId)
    else selectNode(nodeId)
    setBuildPanelOpen(true)
  }, [addBackActive, enterAddBack, selectNode])

  const closeBuildPanel = useCallback(() => setBuildPanelOpen(false), [])

  const panelCandidates = useMemo(
    () => (buildPanelOpen && selectedNodeId
      ? computeAddBackCandidates(selectedNodeId, dag, addBackIndex)
      : null),
    [buildPanelOpen, selectedNodeId, dag, addBackIndex, getLocale()]
  )

  // 面板只对一个切面、一棵树有效：换 Tab / 重新解析会让探索态自动退出
  // （useAddBackExploration 的树**实例**失效判定），此时面板必须跟着关。
  useEffect(() => {
    if (buildPanelOpen && !addBackActive) setBuildPanelOpen(false)
  }, [buildPanelOpen, addBackActive])

  // Spec §4.2 — runtime executionSummary cache, keyed by nodeId. Populated
  // when a node's SQL finishes executing; injected into LineagePanel as a
  // fallback for nodes whose backend record doesn't carry it (e.g. legacy
  // sessions, fragments generated before the field was added).
  const [executionSummaries, setExecutionSummaries] = useState<
    Record<string, import('./hooks/useExecution').ExecutionSummaryLike>
  >({})
  // Overlay ref — dialogs/modals are managed by OverlayHost via imperative handle
  const overlayRef = useRef<OverlayHandle>(null)

  // ── Panel resize ──
  const {
    leftWidth,
    resizingVertical,
    resizingHorizontal,
    rightAreaRef,
    startVerticalResize,
    startHorizontalResize,
    setLeftWidth,
  } = usePanelResize()

  // Ref-based indirection to break the circular dependency:
  // useEditorActions needs fetchPositions/clearPositions (from useNodeSelection),
  // but useNodeSelection needs handleNodeSelect (from useEditorActions).
  const fetchPositionsRef = useRef<(sql: string, dialect: SqlDialect) => void>(() => {})
  const clearPositionsRef = useRef<() => void>(() => {})

  // ── Editor state + action handlers ──
  const {
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
  } = useEditorActions({
    tabsApi,
    execution,
    diagnostics,
    selectedNode,
    selectedNodeId,
    selectedConnectionId,
    currentMode,
    dialect: effectiveDialect,
    parse,
    clearParseResult,
    fetchPositions: (sql, dialect) => fetchPositionsRef.current(sql, dialect),
    clearPositions: () => clearPositionsRef.current(),
    selectNode,
  })

  // Backward-compat alias for keyboard-shortcut wiring (F5 / Ctrl+Enter).
  const handleExecute = handleRunCurrent

  // ── Node selection (needs handleNodeSelect from useEditorActions) ──
  const {
    positions: nodePositions,
    fetchPositions,
    clearPositions,
    selectNodeAtOffset,
  } = useNodeSelection({ backendUrl: backendUrl ?? '', onSelectNode: handleNodeSelect })

  // Populate the refs so useEditorActions callbacks can reach fetchPositions/clearPositions
  fetchPositionsRef.current = fetchPositions
  clearPositionsRef.current = clearPositions

  // ── Session lifecycle effects ──
  const { booting, bootError, retryBoot, sessionSyncError, retrySessionSync, checkpoint } = useSessionLifecycle({
    backendUrl,
    tabsApi,
    activeSessionLoading: tabsApi.activeWorkspace?.hydration === 'loading',
    currentSql,
    result,
    selectedNodeId,
    dialect: effectiveDialect,
    connectionId: selectedConnectionId,
    parse,
    dag,
    parsedSql,
    parsedDialect,
    treeEpoch,
  })

  // Edit lineage index (spec §6.4/§6.5) — App-level so marks survive tab switches.
  const { links: editLinks, record: recordEditLink, error: editLinksError, retry: retryEditLinks } = useEditLinks(backendUrl, tabsApi.tabs)
  const edit = useFragmentEditFeedback({ tabs: tabsApi, tree: result?.tree ?? null,
    nodeMap: dag.nodeMap, dialect: effectiveDialect, submit: submitUserEdit, record: recordEditLink })
  const workspaceExit = useWorkspaceExit({ tabs: tabsApi, checkpoint,
    pending: parseLoading || Object.values(decomposing).some(Boolean) || edit.pending || storage.busy || storage.pending,
    blockers: () => [...(overlayRef.current?.getExitBlockers?.() ?? []),
      ...(edit.feedback?.recovery ? ['An edit recovery operation has not been completed.'] : [])],
  })
  const compare = useFragmentCompare(tabsApi.tabs, { sessionId: tabsApi.activeSessionId, dag, parsedSql,
    dialect: parsedDialect, epoch: treeEpoch }, selectedNodeId)
  const compareRef = useRef<CompareHandle>(null)
  const taskNotices = useTaskNotices()
  const openCompare = (secondNodeId?: string) => {
    if (workspaceExit.locked || execution.loading || execution.submitting || diagnostics.verifyingIds.size || parseLoading) return
    flushSync(() => checkpoint())
    compare.open(secondNodeId)
  }

  // Mirror settings into appSettings (localStorage) so reopening the app
  // restores the user's preferences (theme, dialect, rowLimit, etc.).
  useEffect(() => {
    setAppSettings((prev) => {
      if (
        prev.theme === settings.theme &&
        prev.dialect === settings.dialect &&
        prev.rowLimit === settings.rowLimit &&
        prev.queryTimeout === settings.queryTimeout &&
        prev.autoCleanupDays === settings.autoCleanupDays
      ) {
        return prev
      }
      return { ...prev, ...settings }
    })
  }, [settings, setAppSettings])

  useEffect(() => {
    if (!backendUrl) return
    sessions.fetchSessions()
    history.fetchHistory()
  }, [backendUrl])

  const traceTreeJson = useMemo(() => {
    if (!result?.tree) return ''
    const build = (id: string, ancestors: Set<string>): FragmentNode => {
      const n = dag.nodeMap.get(id)!
      if (ancestors.has(id)) throw new Error('Cyclic trace graph')
      const next = new Set(ancestors).add(id)
      const ids = [...new Set([...dag.ruleResults.values()].filter(r => r.sourceNodeId === id).flatMap(r => r.childIds))]
      return { ...n, children: ids.map(child => build(child, next)) }
    }
    return JSON.stringify(build(result.tree.id, new Set()))
  }, [result?.tree, dag, getLocale()])

  const hasResult = result !== null && (result.tree !== null || result.errors.length > 0)
  const backendDown = status?.status === 'DOWN'

  // Source path combines decomposition ancestry and explicit edit links.
  // Include DAG-only nodes as well as the top-down tree, independently of
  // the current add-back projection. Tree records retain their children.
  const allLineageNodes: FragmentNode[] = useMemo(
    () => {
      const nodes = new Map(Array.from(dag.nodeMap.values(), n => [n.id, n] as const))
      if (result?.tree) {
        for (const n of collectLineageNodesAsFragments(result.tree)) nodes.set(n.id, n)
      }
      return [...nodes.values()]
    },
    [result?.tree, dag.nodeMap, getLocale()]
  )
  const { chain: lineageChain, pathNotice: lineagePathNotice } = useLineageChain(allLineageNodes, selectedNodeId)

  const openSessionIds = useMemo(() => tabsApi.tabs.map((t) => t.sessionId), [tabsApi.tabs, getLocale()])

  // 编辑标记按**节点 id** 索引，扫描面＝顶层树 ∪ DAG nodeMap：加回投影复用真 id，
  // 所以自上而下 ↔ 加回切换时标记不会丢；DAG 一侧还覆盖「只活在 nodeMap 里的
  // 存量 USER_EDITED 产物」（spec §6.5）。
  const editMarks = useMemo(() => {
    const marks = new Map<string, EditMark>()
    const seen = new Set<string>()
    const scan = (nodes: FragmentNode[]) => {
      for (const n of nodes) {
        if (seen.has(n.id)) continue
        seen.add(n.id)
        const mark = resolveEditMark(n, editLinks, openSessionIds, tabsApi.activeSessionId ?? '')
        if (mark) marks.set(n.id, mark)
        scan(n.children)
      }
    }
    if (result?.tree) scan([result.tree])
    scan(Array.from(dag.nodeMap.values()))
    return marks
  }, [result?.tree, dag, editLinks, openSessionIds, tabsApi.activeSessionId, getLocale()])

  // 反向桩：本 Tab 的选中切面若是某次编辑的产物，指回它的源 Tab。
  const lineageOrigin = useMemo(
    () => (selectedNode ? resolveEditOrigin(selectedNode, editLinks, openSessionIds, tabsApi.activeSessionId ?? '') : null),
    [selectedNode, editLinks, openSessionIds, tabsApi.activeSessionId, getLocale()]
  )

  const jumpToSession = useCallback((sessionId: string) => {
    tabsApi.switchTab(sessionId)
  }, [tabsApi])

  const handleDecompose = useCallback(
    (nodeId: string, ruleName: string) => {
      decomposeNode(nodeId, ruleName)
    },
    [decomposeNode]
  )

  // F: Handlers for FragmentEditor (guided edit)
  const handleEditNode = useCallback((nodeId: string, sql: string, label: string) => {
    // OverlayHost tracks the editing state internally; App just triggers open
    overlayRef.current?.openFragmentEditor({ id: nodeId, sqlFragment: sql, label } as FragmentNode)
  }, [])

  const handleSaveFragmentEdit = edit.save

  const handleViewLineage = useCallback((nodeId: string) => {
    selectNode(nodeId)
    overlayRef.current?.openLineage()
  }, [selectNode])

  // F: Tab system handlers (spec §3)
  const handleCloseTab = useCallback(
    (sessionId: string) => {
      const tab = tabsApi.tabs.find((t) => t.sessionId === sessionId)
      if (!tab) return
      if (tab.isDirty) {
        overlayRef.current?.openCloseConfirm(tab)
      } else {
        void tabsApi.closeTab(sessionId, { force: true })
      }
    },
    [tabsApi]
  )

  const handleTabContextMenu = useCallback((tab: Tab, x: number, y: number) => {
    overlayRef.current?.openTabContextMenu(tab, x, y)
  }, [])

  const handleOpenInNewTab = useCallback(
    (sql: string) => {
      void tabsApi.openInNewTab(sql, { connectionId: null })
    },
    [tabsApi]
  )

  const handleConnectionSelect = useCallback((id: string | null) => {
    if (workspaceExit.locked || !tabsApi.activeSessionId) return
    const connection = connections.find(c => c.id === id)
    tabsApi.updateWorkspace(tabsApi.activeSessionId, { connectionId: id, isDirty: true,
      ...(connection && dialects.includes(connection.dialect as SqlDialect) ? { dialect: connection.dialect as SqlDialect } : {}) })
    setSelectedConnectionId(id)
  }, [tabsApi, connections, workspaceExit.locked, setSelectedConnectionId])

  const handleSaveConnection = useCallback(async (id: string | undefined, request: ConnectionRequest): Promise<boolean> => {
    if (id) {
      const result = await updateConnection(id, request)
      if (!result) return false
    } else {
      const result = await createConnection(request)
      if (!result) return false
    }
    return true
  }, [createConnection, updateConnection])

  const handleDeleteConnection = useCallback(async (id: string) => {
    const deleted = await deleteConnection(id)
    if (!deleted) return false
    if (selectedConnectionId === id) {
      handleConnectionSelect(null)
    }
    return true
  }, [deleteConnection, selectedConnectionId, handleConnectionSelect])

  const handleEditConnection = useCallback((id: string) => {
    overlayRef.current?.openConnectionDialog(id)
  }, [])

  const handleManageConnections = useCallback(() => {
    overlayRef.current?.openConnectionDialog()
  }, [])

  useKeyboardShortcuts({
    onParse: compare.state ? () => {} : handleParse,
    onExecute: compare.state ? () => compareRef.current?.run() : () => { if (queryConnection && isBrowsing && parsedDialect === effectiveDialect && parsedSql === currentSql) handleExecute() },
    onExecuteCurrent: compare.state ? () => compareRef.current?.run() : () => { if (queryConnection && (!isBrowsing || (parsedDialect === effectiveDialect && parsedSql === currentSql))) handleRunCurrent() },
    onFormat: handleFormat,
    onCancel: compare.state ? () => { if (execution.loading) void compareRef.current?.cancel(); else compare.close() } : () => execution.cancel(),
    onNewSession: compare.state ? () => {} : handleNewSession
  }, !workspaceExit.locked)

  return (
    <ExperimentSourcesContext.Provider value={dag}>
    <div className={`app ${settings.theme === 'high-contrast' ? 'theme-high-contrast' : settings.theme === 'light' ? 'theme-light' : ''}`}>
      {taskNotices.length > 0 && <div className="task-notices" aria-label={tr("Query cleanup notices")}>
        {taskNotices.map(notice => <div key={notice.id} role="alert" data-error-reason={notice.errorReason}>
          <span>{notice.message}</span><button onClick={() => dismissTaskNotice(notice.id)}>{tr("Dismiss")}</button>
        </div>)}
      </div>}
      {tabsApi.activeWorkspace?.recoveryError && (
        <BootErrorBanner label="Workspace recovery needs attention" severity="warning"
          message={tabsApi.activeWorkspace.recoveryError}
          onRetry={() => { if (tabsApi.activeSessionId) void tabsApi.retryWorkspace?.(tabsApi.activeSessionId) }} />
      )}
      {editLinksError && <BootErrorBanner label="Could not restore edit origins" message={editLinksError} onRetry={retryEditLinks} />}
      {/* Backend unavailable banner */}
      {storage.status?.job && <div role="status" aria-label={tr("Storage cleanup status")} className="workspace-save-status">
        <span>{tr("Storage cleanup")}{" "}{storage.status.job.status}{storage.status.job.errorReason ? ` · ${storage.status.job.errorReason}` : ''}</span>
        <button onClick={() => overlayRef.current?.openSettings()}>{tr("View cleanup details")}</button>
      </div>}
      {(backendDown || backendError) && (
        <div className="backend-banner" role="alert" aria-label={tr("Backend unavailable")}>
          <span className="backend-banner-icon">&#9888;</span>
          <span>{backendError ?? tr("Backend connection lost. Attempting to reconnect...")}</span>
        </div>
      )}

      {/* Boot error banner (PR-2 rule #2 observable surface) */}
      {bootError && (
        <BootErrorBanner
          label={tabsApi.bootErrorLabel}
          message={bootError}
          onRetry={tabsApi.bootErrorRetryable === false ? undefined : retryBoot}
          onDismiss={tabsApi.bootErrorRetryable === false ? tabsApi.dismissBootError : undefined}
          retrying={booting}
        />
      )}

      {/* PR-2 review I-1: Effect 7's backend-session POST used to swallow both
          failure branches. Distinct from the boot banner above: boot is done
          here, and the degradation is that this workspace has no backend
          session, so autosave/lineage sync are no-ops and localStorage is the
          only persistence left. */}
      {sessionSyncError && (
        <BootErrorBanner
          label="Could not save this workspace to the backend"
          message={`${sessionSyncError}. Backend synchronization could not be confirmed. Keep this workspace open and retry saving.`}
          onRetry={retrySessionSync}
          retrying={false}
        />
      )}

      {edit.feedback && (
        <BootErrorBanner
          label={edit.feedback.label}
          message={edit.blocked ?? edit.feedback.message}
          severity={edit.feedback.severity}
          onRetry={edit.feedback.recovery ? () => { void edit.retry() } : undefined}
          retryLabel={edit.feedback.recovery?.stage === 'open' ? tr("Retry opening tab") : tr("Retry")}
          retrying={edit.pending}
          retryDisabled={!!edit.blocked}
          onDismiss={edit.dismiss}
        />
      )}

      {/* Global loading bar */}
      {(parseLoading || execution.loading) && <div className="loading-bar" aria-hidden="true" />}

      {/* Toolbar */}
      <header className="toolbar">
        <div className="toolbar-left">
          <strong>SQL Diagnostic Workbench</strong>
        </div>
        <div className="toolbar-right">
          <button className="toolbar-btn toolbar-btn-ghost" onClick={() => overlayRef.current?.openSettings()} title={tr("Settings")}>
            &#9881;
          </button>
        </div>
      </header>

      {/* F: TabBar — multi-session tabs above main content (spec §3 / §5.2) */}
      <TabBar
        tabs={tabsApi.tabs}
        activeSessionId={tabsApi.activeSessionId}
        onSwitch={tabsApi.switchTab}
        onClose={handleCloseTab}
        onCreate={() => void tabsApi.createTab()}
        onContextMenu={handleTabContextMenu}
      />

      {!compare.state && <div className="query-context" aria-label={tr("Current query context")}>
        <span>{tr("Query connection")}</span>
        <ConnectionManager connections={connections} selectedConnectionId={selectedConnectionId}
          onSelect={handleConnectionSelect} onManage={handleManageConnections} onEdit={handleEditConnection} />
        <span className="query-database">{queryConnection ? `${queryConnection.host}:${queryConnection.port} / ${queryConnection.database}` : selectedConnectionId ? tr("Saved connection unavailable — choose a connection") : tr("Offline · execution requires a connection")}</span>
        <label>{tr("Dialect")}{" "}<select aria-label={tr("Query dialect")} value={effectiveDialect} disabled={!!queryConnection || workspaceExit.locked || booting}
          onChange={event => { if (tabsApi.activeSessionId) tabsApi.updateWorkspace(tabsApi.activeSessionId, { dialect: event.target.value as SqlDialect, isDirty: true }) }}>
          <option value="postgresql">PostgreSQL</option><option value="mysql">MySQL</option>
          <option value="sqlserver">SQL Server</option><option value="oracle">Oracle</option>
        </select></label>
        {queryConnection && <span>{tr("From connection")}</span>}
        {connError && <span role="alert">{connError}</span>}
      </div>}

      {/* Main Content */}
      <div className="main-content">
        {/* Left Panel — Fragment Tree */}
        <FragmentTree
          revealRequest={evidenceReveal}
          tree={displayedTree}
          loading={parseLoading}
          error={parseError}
          warning={decompositionWarning}
          onRetry={handleParse}
          selectedNodeId={selectedNodeId}
          onSelectNode={compare.state ? compare.selectNode : handleNodeSelect}
          onCompareNode={id => compare.state ? compare.selectNode(id) : openCompare(id)}
          optionsByNodeId={optionsCache}
          optionsLoading={optionsLoading}
          decomposing={decomposing}
          onDecompose={handleDecompose}
          onEditNode={handleEditNode}
          onViewLineage={handleViewLineage}
          onBuildUpward={handleBuildUpward}
          addBack={{
            active: addBackActive,
            seedLabel: addBackSeedId
              ? (dag.nodeMap.get(addBackSeedId)?.label ?? addBackSeedId)
              : '',
            breadcrumb: addBackBreadcrumb,
            reachesOrigin: addBackReachesOrigin,
            isOriginRoot: addBackIsOriginRoot,
            degraded: addBackDegraded,
            reason: addBackReason,
            onExit: exitAddBack,
          }}
          addBackSkipped={addBackSkipped}
          treeInstanceKey={treeInstanceKey}
          lineageChain={lineageChain}
          onOpenLineage={() => overlayRef.current?.openLineage()}
          editMarks={editMarks}
          onJumpToEdit={jumpToSession}
          lineageOrigin={lineageOrigin}
          onOpenOrigin={jumpToSession}
          width={leftWidth}
        />

        {buildPanelOpen && panelCandidates && selectedNode && (
          <BuildPanel
            seedLabel={selectedNode.label}
            candidates={panelCandidates.candidates.map((c) => ({
              parentId: c.parentId,
              label: c.node.label,
              revealed: addBackActive ? isEdgeRevealed(selectedNode.id, c.parentId) : false,
            }))}
            skipped={panelCandidates.skipped}
            degraded={panelCandidates.degraded}
            reason={panelCandidates.reason}
            onToggleCandidate={(parentId) => toggleAddBack(selectedNode.id, parentId)}
            onClose={closeBuildPanel}
          />
        )}

        {/* Vertical resize handle between left panel and right area */}
        <div
          className={`vertical-resize-handle ${resizingVertical ? 'dragging' : ''}`}
          onMouseDown={startVerticalResize}
          role="separator"
          aria-orientation="vertical"
          aria-label={tr("Resize panels")}
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'ArrowLeft') setLeftWidth((w) => Math.max(160, w - 10))
            else if (e.key === 'ArrowRight') setLeftWidth((w) => Math.min(600, w + 10))
          }}
        />

        {/* Right content area */}
        <div className="right-content" style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
          {/* Always showing the same layout — SqlViewer + Results — regardless of hasResult.
              SqlViewer reads from currentSql (editable) or selectedNode.sqlFragment (read-only).
              ResultsPanel shows "Execute a fragment" when no result exists. */}
          <main className="right-area" ref={rightAreaRef} style={compare.state ? { display: 'none' } : undefined}>
            {/* SQL Viewer */}
            <section className="sql-viewer">
              <div className="breadcrumb">
                <span className={`breadcrumb-item ${!selectedNode ? '' : 'muted'}`}>
                  {viewerMode === 'browse' ? tr("Read-only fragment") : currentSql ? tr("Current SQL") : tr("New SQL query")}
                </span>
                {viewerMode === 'browse' && selectedNode && (
                  <>
                    <span className="breadcrumb-sep">&gt;</span>
                    <span className="breadcrumb-item">{selectedNode.label}</span>
                  </>
                )}
                {viewerMode === 'browse' && <button className="toolbar-btn" onClick={() => {
                  if (!workspaceExit.locked && tabsApi.activeSessionId) tabsApi.updateWorkspace(tabsApi.activeSessionId, { viewerMode: 'input' })
                }}>{tr("← Edit original SQL")}</button>}
              </div>
              {result?.tree && (parsedSql !== currentSql || parsedDialect !== effectiveDialect) && <div className="query-notice" role="status">
                {tr("Decomposition is out of date")}{parsedDialect !== effectiveDialect ? tr(" (dialect changed)") : tr(" (SQL edited)")}{tr(". Parse current SQL to refresh fragments.")}{" "}</div>}
                <div className="editor-toolbar">
                  {/* Execute Group - pill background, ModeToggle + Parse + Execute/Analyze + Stop.
                      ModeToggle lives here (per spec) so mode and execution are visually clustered. */}
                  <div className="toolbar-group toolbar-group-execute">
                    <ModeToggle value={currentMode} onChange={handleModeChange} />
                    <button
                      className="toolbar-btn toolbar-btn-primary"
                      onClick={handleParse}
                      disabled={!currentSql.trim() || parseLoading}
                      title={tr("Parse current SQL (Ctrl+Shift+Enter)")}
                    >
                      {tr("⚡ Parse")}{" "}<kbd>Ctrl+Shift+Enter</kbd>
                    </button>
                    {currentMode === 'performance' ? (
                      <AnalyzeSplitButton
                        backendUrl={backendUrl}
                        connectionId={selectedConnectionId}
                        onAnalyze={handleAnalyzeWithMode}
                        disabled={!canExecute || !queryConnection || (isBrowsing && (parsedDialect !== effectiveDialect || parsedSql !== currentSql))}
                        title={selectedConnectionId ? tr("Analyze fragment") : tr("Select a connection to analyze")}
                      />
                    ) : (
                      <button
                        className="toolbar-btn toolbar-btn-execute"
                        onClick={handleRunCurrent}
                        disabled={!canExecute || !queryConnection || (isBrowsing && (parsedDialect !== effectiveDialect || parsedSql !== currentSql))}
                        title={selectedConnectionId ? (isBrowsing ? tr("Execute visible fragment (F5)") : tr("Execute current SQL (Ctrl+Enter)")) : tr("Select a connection to execute")}
                      >
                        &#9654; {isBrowsing ? tr("Execute fragment") : tr("Execute SQL")}
                      </button>
                    )}
                    {(isExecuting || execution.errorReason === 'STATUS_UNAVAILABLE' || execution.errorReason === 'CANCEL_UNCONFIRMED') && (
                      <button
                        className="toolbar-btn toolbar-btn-cancel"
                        onClick={() => execution.cancel()}
                        title={tr("Cancel running query")}
                      >
                        {tr("■ Stop")}{" "}</button>
                    )}
                    {execution.errorReason === 'STATUS_UNAVAILABLE' && <button className="toolbar-btn"
                      onClick={execution.retryStatus}>{tr("Retry status")}</button>}
                  </div>

                  {/* Transform Group - Format */}
                  <div className="toolbar-group toolbar-group-transform">
                    {viewerMode === 'input' && currentSql.trim() && (
                      <button
                        className="toolbar-btn toolbar-btn-ghost"
                        onClick={handleFormat}
                        title={tr("Format SQL (Ctrl+Shift+F)")}
                      >
                        {tr("↻ Format")}{" "}</button>
                    )}
                  </div>

                  {/* Session Group - Compare, New */}
                  <div className="toolbar-group toolbar-group-session">
                    {hasResult && (
                      <>
                        <button
                          className="toolbar-btn toolbar-btn-ghost"
                          onClick={() => openCompare()}
                          disabled={execution.loading || execution.submitting || diagnostics.verifyingIds.size > 0 || workspaceExit.locked}
                          title={tr("Compare fragments")}
                        >
                          {tr("⊞ Compare")}{" "}</button>
                      </>
                    )}
                    <button className="toolbar-btn toolbar-btn-ghost" onClick={handleNewSession} title={tr("New tab (Ctrl+N)")}>{tr("+ New")}</button>
                  </div>
                </div>
              {selectedNodeId && <ExperimentContext key={`${tabsApi.activeSessionId}:${selectedConnectionId}:${treeEpoch}:${selectedNodeId}`} nodeId={selectedNodeId} dag={dag} onRetry={decomposeNode} />}
              <SqlViewer
                value={viewerSql}
                onChange={handleSqlChange}
                readOnly={viewerReadOnly || workspaceExit.locked}
                // PR-2 (spec §6.1 item 2): the read-only gate needs a visible
                // explanation. Also covers `+`/openInNewTab, which share the
                // same boot surface.
                bootHint={booting ? tr("Preparing session…") : null}
                onExecute={() => { if (queryConnection && (!isBrowsing || (parsedDialect === effectiveDialect && parsedSql === currentSql))) handleRunCurrent() }}
                dialect={effectiveDialect}
                placeholder={tr("Paste SQL · Ctrl+Enter to execute · Ctrl+Shift+Enter to parse")}
                theme={settings.theme}
                highlights={nodePositions}
                onSelectNodeAtOffset={selectNodeAtOffset}
                selectedNodeId={selectedNodeId}
              />
            </section>

            {/* Results Panel */}
            <HorizontalResizeHandle
              onMouseDown={startHorizontalResize}
              isDragging={resizingHorizontal}
            />
            <div className="query-execution-status" role="status">
              <ExecutionStatusInline loading={isExecuting} status={execution.status} error={null}
                dataResult={execution.dataResult} planResult={execution.planResult} startedAt={execution.startedAt}
                mode={currentMode} queryTimeoutSeconds={settings.queryTimeout} onCancel={execution.cancel} />
            </div>
            <ResultsPanel
              backendUrl={backendUrl}
              result={currentMode === 'performance' ? execution.planResult : execution.dataResult}
              loading={execution.loading}
              error={execution.error}
              provenance={execution.provenance ? { ...execution.provenance, connectionName: queryConnection?.name ?? tr("Connection unavailable"), source: isBrowsing ? tr("Fragment") : tr("Current SQL") } : undefined}
              mode={currentMode}
              diagnostics={diagnostics}
              onVerify={handleVerifyFinding}
              fragmentTreeJson={traceTreeJson}
              connectionId={selectedConnectionId}
              draftScope={JSON.stringify([tabsApi.activeSessionId, selectedConnectionId, treeEpoch])}
              rowTrace={{ ...rowTrace, trace: (connectionId, tree, keys, options) => rowTrace.trace(connectionId, tree, keys,
                { ...options, transformEdges: [...dag.ruleResults.values()].flatMap(r => r.outputs ?? []) }) }}
              canLocateEvidence={(id, sql) => !workspaceExit.locked && !!rowTrace.snapshot &&
                rowTrace.snapshot.scope === JSON.stringify([tabsApi.activeSessionId, selectedConnectionId, treeEpoch]) &&
                rowTrace.snapshot.sqlById[id] === sql && dag.nodeMap.get(id)?.sqlFragment === sql}
              onLocateEvidence={(id, sql) => {
                if (workspaceExit.locked || !rowTrace.snapshot || rowTrace.snapshot.scope !== JSON.stringify([tabsApi.activeSessionId, selectedConnectionId, treeEpoch]) ||
                  rowTrace.snapshot.sqlById[id] !== sql || dag.nodeMap.get(id)?.sqlFragment !== sql) return
                if (addBackActive) exitAddBack()
                handleNodeSelect(id)
                setEvidenceReveal(prev => ({ id, sequence: (prev?.sequence ?? 0) + 1 }))
              }}
            />
          </main>
          {compare.state && <CompareView ref={compareRef} a={compare.state.a} b={compare.state.b} side={compare.state.side}
            sources={compare.sources} backendUrl={backendUrl} connections={connections} connectionId={selectedConnectionId}
            settings={{ maxRows: settings.rowLimit, queryTimeout: settings.queryTimeout }} theme={settings.theme} locked={workspaceExit.locked}
            onChoose={compare.choose} onSide={compare.setSide} onSwap={compare.swap} onClose={compare.close} />}
        </div>
      </div>

      <div className="app-statusbar">          <div className="connection-indicator">
            <span
              className={`status-dot ${status?.status === 'UP' ? 'status-up' : 'status-down'}`}
            />
            <span className="connection-label">
              {status?.status === 'UP' ? tr("Backend connected") : tr("Connecting...")}
            </span>
          </div>
      {tabsApi.activeWorkspace?.hydration === 'ready' && (
        <div role="status" aria-label={tr("Workspace save status")} className="workspace-save-status">
          {tabsApi.activeWorkspace.syncStatus === 'failed' ? tr("Workspace save failed")
            : tabsApi.activeWorkspace.syncStatus === 'pending' || tabsApi.activeWorkspace.isDirty ? tr("Saving workspace…") : tr("Workspace saved")}
        </div>
      )}
</div>
      {/* Bottom Dock — SQL History */}
      <HistoryDock
        entries={history.entries}
        error={history.error}
        loading={history.loading}
        onRefresh={() => history.fetchHistory()}
        onOpenInNewTab={handleOpenInNewTab}
      />

      {/* All dialogs/modals managed by OverlayHost (Task 5 refactor) */}
      {workspaceExit.state && <WorkspaceExitDialog result={workspaceExit.state.result}
        onRetry={workspaceExit.retry} onCancel={workspaceExit.cancel} onDiscard={workspaceExit.discard} />}
      <OverlayHost
        storage={storage}
        ref={overlayRef}
        connections={connections}
        connError={connError}
        onSaveConnection={handleSaveConnection}
        onCredentialsChanged={fetchConnections}
        onDeleteConnection={handleDeleteConnection}
        onTestConnection={testConnection}
        settings={settings}
        onSettingsChange={(s) => setSettings({ ...s })}
        selectedNode={selectedNode}
        dialect={effectiveDialect}
        onSaveFragmentEdit={handleSaveFragmentEdit}
        result={result}
        lineageChain={lineageChain}
        allLineageNodes={allLineageNodes}
        lineagePathNotice={lineagePathNotice}
        selectedNodeId={selectedNodeId}
        backendUrl={backendUrl}
        executionSummaries={executionSummaries}
        onSelectLineageNode={selectNode}
        tabs={tabsApi.tabs}
        onRenameTab={(sessionId, name) => void tabsApi.renameTab(sessionId, name)}
        onCloseTab={handleCloseTab}
        onCloseOtherTabs={(keepId) => {
          for (const t of tabsApi.tabs) {
            if (t.sessionId !== keepId) void tabsApi.closeTab(t.sessionId, { force: true })
          }
        }}
        onCloseAllTabs={() => {
          for (const t of tabsApi.tabs) void tabsApi.closeTab(t.sessionId, { force: true })
        }}
        onConfirmCloseTab={(sessionId) => void tabsApi.closeTab(sessionId, { force: true })}
      />
    </div>
    </ExperimentSourcesContext.Provider>
  )
}

// Horizontal resize handle between SQL viewer and results panel
function HorizontalResizeHandle({ onMouseDown, isDragging }: { onMouseDown: (e: React.MouseEvent) => void; isDragging: boolean }) {
  useLocale()
  return (
    <div
      className={`horizontal-resize-handle ${isDragging ? 'dragging' : ''}`}
      onMouseDown={onMouseDown}
      role="separator"
      aria-orientation="horizontal"
      aria-label={tr("Resize SQL viewer and results")}
      tabIndex={0}
      onKeyDown={(e) => {
        if (!['ArrowUp', 'ArrowDown'].includes(e.key)) return
        e.preventDefault()
        const area = e.currentTarget.parentElement
        const sql = area?.querySelector<HTMLElement>('.sql-viewer')
        const results = area?.querySelector<HTMLElement>('.results-panel')
        if (!sql || !results) return
        const total = sql.offsetHeight + results.offsetHeight
        const height = Math.max(120, Math.min(total - 100, sql.offsetHeight + (e.key === 'ArrowDown' ? 10 : -10)))
        sql.style.flex = results.style.flex = 'none'
        sql.style.height = `${height}px`
        results.style.height = `${total - height}px`
      }}
    />
  )
}
