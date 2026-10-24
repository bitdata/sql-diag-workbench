import { tr, useLocale } from '../../i18n'
import { useState, useCallback, useImperativeHandle, forwardRef } from 'react'
import ConnectionDialog from '../Connection/ConnectionDialog'
import SettingsPanel from '../Settings/SettingsPanel'
import FragmentEditor from '../Editor/FragmentEditor'
import LineagePanel from '../Lineage/LineagePanel'
import TabRenameDialog from '../Tabs/TabRenameDialog'
import CloseTabConfirmDialog from '../Tabs/CloseTabConfirmDialog'
import TabContextMenu from '../Tabs/TabContextMenu'
import type { ConnectionConfig, ConnectionRequest, ConnectionTestResult } from '../../types/connection'
import type { FragmentNode } from '../../types/fragment'
import type { Tab } from '../../types/tab'
import type { SqlDialect } from '../../types/session'
import type { StorageHook } from '../../hooks/useStorage'

interface SettingsState {
  dialect: SqlDialect
  rowLimit: number
  queryTimeout: number
  autoCleanupDays: number
  theme: 'dark' | 'light' | 'high-contrast'
}

export interface OverlayHandle {
  getExitBlockers?: () => string[]
  openConnectionDialog: (editingId?: string) => void
  openSettings: () => void
  openFragmentEditor: (node: FragmentNode) => void
  openLineage: () => void
  openTabRename: (tab: Tab) => void
  openTabContextMenu: (tab: Tab, x: number, y: number) => void
  openCloseConfirm: (tab: Tab) => void
}

interface OverlayHostProps {
  storage?: StorageHook
  // Connection
  connections: ConnectionConfig[]
  connError: string | null
  onCredentialsChanged?: () => Promise<void>
  onSaveConnection: (id: string | undefined, req: ConnectionRequest) => Promise<boolean>
  onDeleteConnection: (id: string) => Promise<boolean>
  onTestConnection: (req: ConnectionRequest) => Promise<ConnectionTestResult>
  // Settings
  settings: SettingsState
  onSettingsChange: (s: SettingsState) => void
  // Compare
  selectedNode: FragmentNode | null
  // Fragment editor
  dialect: SqlDialect
  onSaveFragmentEdit: (id: string | null, originalSql: string, newSql: string, description: string) => Promise<boolean>
  // Lineage
  result: { tree: FragmentNode | null } | null
  lineageChain: FragmentNode[]
  allLineageNodes: FragmentNode[]
  lineagePathNotice?: string | null
  selectedNodeId: string | null
  backendUrl: string | null
  executionSummaries: Record<string, import('../../hooks/useExecution').ExecutionSummaryLike>
  onSelectLineageNode: (id: string) => void
  // Tab operations
  tabs: Tab[]
  onRenameTab: (sessionId: string, name: string) => void
  onCloseTab: (sessionId: string) => void
  onCloseOtherTabs: (keepSessionId: string) => void
  onCloseAllTabs: () => void
  onConfirmCloseTab: (sessionId: string) => void
}

const OverlayHost = forwardRef<OverlayHandle, OverlayHostProps>(function OverlayHost(props, ref) {
  useLocale()
  const {
    connections, connError, onSaveConnection, onDeleteConnection, onTestConnection,
    settings, onSettingsChange,
    selectedNode,
    dialect, onSaveFragmentEdit,
    result, lineageChain, allLineageNodes, selectedNodeId, backendUrl, executionSummaries, onSelectLineageNode,
    tabs, onRenameTab, onCloseTab, onCloseOtherTabs, onCloseAllTabs, onConfirmCloseTab,
  } = props

  // ── Internal overlay state (moved from App.tsx) ──
  const [showConnectionDialog, setShowConnectionDialog] = useState(false)
  const [editingConnection, setEditingConnection] = useState<string | null>(null)
  const [showSettings, setShowSettings] = useState(false)
  const [showFragmentEditor, setShowFragmentEditor] = useState(false)
  const [editingFragmentId, setEditingFragmentId] = useState<string | null>(null)
  const [editingFragmentSql, setEditingFragmentSql] = useState('')
  const [editingFragmentLabel, setEditingFragmentLabel] = useState('')
  const [showLineagePanel, setShowLineagePanel] = useState(false)
  const [renameDialogTab, setRenameDialogTab] = useState<Tab | null>(null)
  const [closeConfirmTab, setCloseConfirmTab] = useState<Tab | null>(null)
  const [tabContextMenu, setTabContextMenu] = useState<{ tab: Tab; x: number; y: number } | null>(null)

  // ── Imperative handle for parent to open overlays ──
  useImperativeHandle(ref, () => ({
    getExitBlockers: () => [
      ...(showFragmentEditor ? [tr("The fragment editor has content that has not been submitted.")] : []),
      ...(showConnectionDialog ? [tr("The connection dialog has content that has not been submitted.")] : []),
      ...(renameDialogTab ? [tr("The tab rename dialog has content that has not been submitted.")] : []),
      ...(closeConfirmTab ? ['A tab close decision has not been completed.'] : []),
    ],
    openConnectionDialog: (editingId?: string) => {
      setEditingConnection(editingId ?? null)
      setShowConnectionDialog(true)
    },
    openSettings: () => setShowSettings(true),
    openFragmentEditor: (node: FragmentNode) => {
      setEditingFragmentId(node.id)
      setEditingFragmentSql(node.sqlFragment)
      setEditingFragmentLabel(node.label)
      setShowFragmentEditor(true)
    },
    openLineage: () => setShowLineagePanel(true),
    openTabRename: (tab: Tab) => setRenameDialogTab(tab),
    openTabContextMenu: (tab: Tab, x: number, y: number) => setTabContextMenu({ tab, x, y }),
    openCloseConfirm: (tab: Tab) => setCloseConfirmTab(tab),
  }), [showFragmentEditor, showConnectionDialog, renameDialogTab, closeConfirmTab])

  // ── Helper: close fragment editor and reset all internal state ──
  const closeFragmentEditor = useCallback(() => {
    setShowFragmentEditor(false)
    setEditingFragmentId(null)
    setEditingFragmentSql('')
    setEditingFragmentLabel('')
  }, [])

  // ── Connection: auto-close on successful save/delete ──
  const handleConnectionSave = useCallback(async (id: string | undefined, req: ConnectionRequest) => {
    const ok = await onSaveConnection(id, req)
    if (ok) {
      setShowConnectionDialog(false)
      setEditingConnection(null)
    }
    return ok
  }, [onSaveConnection])

  const handleConnectionDelete = useCallback(async (id: string) => {
    const ok = await onDeleteConnection(id)
    if (!ok) return false
    setShowConnectionDialog(false)
    setEditingConnection(null)
    return true
  }, [onDeleteConnection])

  return (
    <>
      {/* Tab context menu */}
      {tabContextMenu && (
        <TabContextMenu
          x={tabContextMenu.x}
          y={tabContextMenu.y}
          onRename={() => {
            setRenameDialogTab(tabContextMenu.tab)
            setTabContextMenu(null)
          }}
          onClose={() => {
            onCloseTab(tabContextMenu.tab.sessionId)
            setTabContextMenu(null)
          }}
          onCloseOthers={() => {
            onCloseOtherTabs(tabContextMenu.tab.sessionId)
            setTabContextMenu(null)
          }}
          onCloseAll={() => {
            onCloseAllTabs()
            setTabContextMenu(null)
          }}
          onDismiss={() => setTabContextMenu(null)}
        />
      )}

      {/* Tab rename dialog */}
      {renameDialogTab && (
        <TabRenameDialog
          currentName={renameDialogTab.name}
          onConfirm={(name) => {
            onRenameTab(renameDialogTab.sessionId, name)
            setRenameDialogTab(null)
          }}
          onCancel={() => setRenameDialogTab(null)}
        />
      )}

      {/* Close tab confirmation */}
      {closeConfirmTab && (
        <CloseTabConfirmDialog
          tabName={closeConfirmTab.name}
          onCancel={() => setCloseConfirmTab(null)}
          onDiscard={() => {
            onConfirmCloseTab(closeConfirmTab.sessionId)
            setCloseConfirmTab(null)
          }}
          onSave={() => {
            onConfirmCloseTab(closeConfirmTab.sessionId)
            setCloseConfirmTab(null)
          }}
        />
      )}

      {/* Connection dialog */}
      {showConnectionDialog && (
        <ConnectionDialog
          backendUrl={backendUrl}
          onCredentialsChanged={props.onCredentialsChanged}
          editingConnection={editingConnection ? connections.find(c => c.id === editingConnection) : undefined}
          saveError={connError}
          onSave={handleConnectionSave}
          onDelete={handleConnectionDelete}
          onCancel={() => { setShowConnectionDialog(false); setEditingConnection(null) }}
          onTest={onTestConnection}
        />
      )}

      {/* Settings panel */}
      {showSettings && (
        <SettingsPanel
          storage={props.storage}
          settings={settings}
          onChange={(s) => onSettingsChange({
            ...s,
          })}
          onClose={() => setShowSettings(false)}
        />
      )}

      {/* Fragment editor */}
      {showFragmentEditor && (
        <FragmentEditor
          sql={editingFragmentSql}
          label={editingFragmentLabel}
          dialect={dialect}
          onSave={async (newSql, description) => {
            const ok = await onSaveFragmentEdit(editingFragmentId, editingFragmentSql, newSql, description)
            if (ok) closeFragmentEditor()
          }}
          onCancel={closeFragmentEditor}
        />
      )}

      {/* Lineage panel */}
      {showLineagePanel && result?.tree && (
        <LineagePanel
          chain={lineageChain}
          allNodes={allLineageNodes}
          pathNotice={props.lineagePathNotice}
          selectedNodeId={selectedNodeId}
          backendUrl={backendUrl}
          dialect={dialect}
          executionSummaries={executionSummaries}
          onClose={() => setShowLineagePanel(false)}
          onSelectNode={(id) => {
            onSelectLineageNode(id)
            setShowLineagePanel(false)
          }}
        />
      )}
    </>
  )
})

export default OverlayHost
