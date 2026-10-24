import type { ConnectionConfig } from '../../types/connection'

export const mockConnection: ConnectionConfig = {
  id: 'conn-1',
  name: 'Test PG',
  host: 'localhost',
  port: 5432,
  database: 'testdb',
  username: 'tester',
  dialect: 'postgresql',
  keychainRef: 'ref-1',
  readonly: false,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

export const mockConnections: ConnectionConfig[] = [mockConnection]
