export interface Session {
  id: string
  name: string
  sqlText: string
  dialect: string
  connectionId: string | null
  fragmentTreeJson: string | null
  workspaceSnapshotJson?: string | null
  createdAt: string
  updatedAt: string
}

export type SqlDialect = 'postgresql' | 'mysql' | 'sqlserver' | 'oracle'

/** Per-tab workspace state — the single source of truth for each tab's working context. */
export interface SessionWorkspaceState {
  // Identity
  sessionId: string
  tabName: string

  // SQL buffer
  sql: string
  isDirty: boolean
  lastSavedAt: number | null

  // Connection & dialect
  connectionId: string | null
  dialect: SqlDialect

  // Fragment tree
  fragmentTreeJson: string | null
  workspaceSnapshotJson?: string | null
  hydration?: 'loading' | 'ready' | 'failed'
  loadEpoch?: number
  recoveryError?: string | null
  recoveryBlocked?: boolean
  syncStatus?: 'pending' | 'saved' | 'failed'
  syncError?: string | null

  // UI state
  selectedNodeId: string | null
  workspaceMode: 'data' | 'performance'
  /**
   * Editor view mode for THIS tab. Per-tab because it is a property of the
   * working context: a fresh tab must open writable (spec 2026-10-01 §5.2),
   * while a tab you already browsed in stays in browse mode. Global state made
   * "select a node, then hit +" a dead end: the new tab's editor was read-only
   * AND empty, with no visible way out.
   */
  viewerMode: 'input' | 'browse'
}

/** Application-level settings, shared across all tabs. Replaces PersistedSession. */
export interface AppSettings {
  theme: 'dark' | 'light' | 'high-contrast'
  dialect: SqlDialect
  rowLimit: number
  queryTimeout: number
  autoCleanupDays: number
}

export interface HistoryEntry {
  resultCompleteness?: import('./execution').ResultCompleteness | null
  id: string
  sessionId: string
  sqlText: string
  executedAt: string
  durationMs: number | null
  rowCount: number | null
}
