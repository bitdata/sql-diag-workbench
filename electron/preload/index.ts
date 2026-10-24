import { contextBridge, ipcRenderer } from 'electron'
import type { ExitResult, ExitDecision } from '../workspace-exit'

export interface BackendStatusEvent {
  status: 'starting' | 'running' | 'stopped' | 'crashed' | 'error'
  error?: string
  errorReason?: string
  degraded?: boolean
  port?: number
}

interface BackendUrlInfo {
  url: string
  token: string
}

contextBridge.exposeInMainWorld('electronAPI', {
  onWorkspaceExit: (callback: (request: { attemptId: string; cancelled?: boolean }) => void) => {
    const handler = (_event: Electron.IpcRendererEvent, request: { attemptId: string; cancelled?: boolean }) => callback(request)
    ipcRenderer.on('workspace-exit-request', handler)
    return () => ipcRenderer.removeListener('workspace-exit-request', handler)
  },
  reportWorkspaceExit: (result: ExitResult) => ipcRenderer.send('workspace-exit-result', result),
  decideWorkspaceExit: (attemptId: string, decision: ExitDecision) => ipcRenderer.send('workspace-exit-decision', { attemptId, decision }),
  getBackendUrl: (): Promise<string> =>
    ipcRenderer.invoke('get-backend-url').then((info: BackendUrlInfo) => info.url),
  getAuthToken: (): Promise<string> =>
    ipcRenderer.invoke('get-backend-url').then((info: BackendUrlInfo) => info.token),
  getBackendStatus: (): Promise<{ status: string; error?: string; errorReason?: string; degraded?: boolean; backendEvent?: BackendStatusEvent }> =>
    ipcRenderer.invoke('get-backend-status'),
  onBackendStatusChange: (callback: (event: BackendStatusEvent) => void) => {
    const handler = (_event: Electron.IpcRendererEvent, data: BackendStatusEvent) =>
      callback(data)
    ipcRenderer.on('backend-status-change', handler)
    return () => ipcRenderer.removeListener('backend-status-change', handler)
  }
})
