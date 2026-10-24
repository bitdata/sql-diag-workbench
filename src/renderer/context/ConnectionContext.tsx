import { createContext, useContext, useCallback, useMemo, type ReactNode } from 'react'
import { useConnections } from '../hooks/useConnections'
import { useLocalStorage } from '../hooks/useLocalStorage'
import type { ConnectionConfig, ConnectionRequest, ConnectionTestResult } from '../types/connection'

interface ConnectionContextValue {
  connections: ConnectionConfig[]
  connLoading: boolean
  connError: string | null
  selectedConnectionId: string | null
  setSelectedConnectionId: (
    id: string | null | ((prev: string | null) => string | null)
  ) => void
  fetchConnections: () => Promise<void>
  createConnection: (req: ConnectionRequest) => Promise<ConnectionConfig | null>
  updateConnection: (id: string, req: ConnectionRequest) => Promise<ConnectionConfig | null>
  deleteConnection: (id: string) => Promise<boolean>
  testConnection: (req: ConnectionRequest) => Promise<ConnectionTestResult>
}

const ConnectionContext = createContext<ConnectionContextValue | null>(null)

const SELECTED_CONN_KEY = 'sql-workbench-selected-connection'

interface ConnectionProviderProps {
  backendUrl: string | null
  children: ReactNode
}

export function ConnectionProvider({ backendUrl, children }: ConnectionProviderProps) {
  const {
    connections,
    loading: connLoading,
    error: connError,
    fetchConnections,
    createConnection,
    updateConnection,
    deleteConnection,
    testConnection,
  } = useConnections(backendUrl)

  const [persistedConn, setPersistedConn] = useLocalStorage<{ selectedConnectionId: string | null }>(
    SELECTED_CONN_KEY,
    { selectedConnectionId: null }
  )

  const setSelectedConnectionId = useCallback(
    (id: string | null | ((prev: string | null) => string | null)) => {
      setPersistedConn((prev) => ({
        ...prev,
        selectedConnectionId: typeof id === 'function' ? id(prev.selectedConnectionId) : id,
      }))
    },
    [setPersistedConn]
  )

  const value = useMemo<ConnectionContextValue>(() => ({
    connections,
    connLoading,
    connError,
    selectedConnectionId: persistedConn.selectedConnectionId,
    setSelectedConnectionId,
    fetchConnections,
    createConnection,
    updateConnection,
    deleteConnection,
    testConnection,
  }), [connections, connLoading, connError, persistedConn.selectedConnectionId,
       setSelectedConnectionId, fetchConnections, createConnection,
       updateConnection, deleteConnection, testConnection])

  return (
    <ConnectionContext.Provider value={value}>
      {children}
    </ConnectionContext.Provider>
  )
}

export function useConnection(): ConnectionContextValue {
  const ctx = useContext(ConnectionContext)
  if (!ctx) throw new Error('useConnection must be used within ConnectionProvider')
  return ctx
}
