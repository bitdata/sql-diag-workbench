import type { Tab } from '../../types/tab'
import type { SessionWorkspaceState } from '../../types/session'

function buildWorkspace(
  sessionId: string,
  name: string,
  sql = '',
): SessionWorkspaceState {
  return {
    sessionId,
    tabName: name,
    sql,
    isDirty: false,
    lastSavedAt: Date.now(),
    connectionId: 'conn-1',
    dialect: 'postgresql',
    fragmentTreeJson: null,
    selectedNodeId: null,
    workspaceMode: 'data',
    viewerMode: 'input',
  }
}

export const mockTab1: Tab = {
  sessionId: 'session-1',
  name: 'Query 1',
  isDirty: false,
  lastSavedAt: Date.now(),
  workspace: buildWorkspace('session-1', 'Query 1', 'SELECT 1'),
}

export const mockTab2: Tab = {
  sessionId: 'session-2',
  name: 'Query 2',
  isDirty: true,
  lastSavedAt: Date.now(),
  workspace: buildWorkspace('session-2', 'Query 2', 'SELECT 2'),
}

export const mockTab3: Tab = {
  sessionId: 'session-3',
  name: 'Long Query Name That Should Be Truncated',
  isDirty: false,
  lastSavedAt: Date.now(),
  workspace: buildWorkspace(
    'session-3',
    'Long Query Name That Should Be Truncated',
  ),
}

export const mockTabs: Tab[] = [mockTab1, mockTab2, mockTab3]
