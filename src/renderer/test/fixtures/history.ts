import type { HistoryEntry } from '../../types/session'

export const mockHistoryEntry: HistoryEntry = {
  id: 'hist-1',
  sessionId: 'session-1',
  sqlText: "SELECT * FROM orders WHERE status = 'active'",
  executedAt: '2026-07-22T10:00:00Z',
  durationMs: 42,
  rowCount: 15,
}

export const mockHistoryEntries: HistoryEntry[] = [
  mockHistoryEntry,
  {
    id: 'hist-2',
    sessionId: 'session-1',
    sqlText: 'SELECT count(*) FROM customers',
    executedAt: '2026-07-22T09:55:00Z',
    durationMs: 8,
    rowCount: 1,
  },
  {
    id: 'hist-3',
    sessionId: 'session-2',
    sqlText: "UPDATE orders SET status = 'shipped' WHERE id = 100",
    executedAt: '2026-07-22T09:50:00Z',
    durationMs: null,
    rowCount: null,
  },
]
