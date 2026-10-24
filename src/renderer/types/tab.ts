import type { SessionWorkspaceState } from './session'

/**
 * A tab in the multi-tab SQL history bar.
 *
 * Identified by `sessionId` (1:1 with backend Session). The frontend holds
 * a derived `isDirty` flag — true when the local SQL buffer differs from
 * what was last successfully PUT to the backend. `lastSavedAt` is the ms
 * timestamp of the most recent successful save (used by the dirty "•"
 * indicator and future diagnostics).
 *
 * `workspace` is the single source of truth for each tab's working context
 * (SQL buffer, connection, dialect, fragment tree, UI state).
 */
export interface Tab {
  sessionId: string
  name: string
  isDirty: boolean
  lastSavedAt: number
  workspace: SessionWorkspaceState
}
