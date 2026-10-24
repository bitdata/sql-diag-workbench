export interface BackendStatus {
  status: 'UP' | 'DOWN'
  timestamp?: string
  error?: string
  errorReason?: string
  degraded?: boolean
  backendEvent?: BackendStatusEvent
}

export interface BackendStatusEvent {
  status: 'starting' | 'running' | 'stopped' | 'crashed' | 'error'
  error?: string
  errorReason?: string
  degraded?: boolean
  port?: number
}

export interface ElectronAPI {
  onWorkspaceExit?: (callback: (request: { attemptId: string; cancelled?: boolean }) => void) => () => void
  reportWorkspaceExit?: (result: import('./workspaceExit').ExitResult) => void
  decideWorkspaceExit?: (attemptId: string, decision: import('./workspaceExit').ExitDecision) => void
  getBackendUrl: () => Promise<string>
  getAuthToken: () => Promise<string>
  getBackendStatus: () => Promise<BackendStatus>
  onBackendStatusChange: (callback: (event: BackendStatusEvent) => void) => () => void
}

declare global {
  interface Window {
    electronAPI: ElectronAPI
  }
}
