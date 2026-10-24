import type { DiagnosticFinding, TraceStep } from '../../types/diagnostics'

export const mockFinding: DiagnosticFinding = {
  id: 'finding-1',
  ruleId: 'expected-row-missing',
  severity: 'HIGH',
  title: 'Expected row missing at JOIN',
  message: 'Row with order_id=123 disappears after joining with customers table.',
  evidence: [
    'LEFT JOIN produces 0 rows for this key',
    'No matching customer_id=456',
  ],
  confidence: 0.85,
  verificationSql: 'SELECT * FROM customers WHERE id = 456',
  affectedNodeId: 'node-join',
  status: 'SUSPECTED',
  verificationResult: null,
}

export const mockConfirmedFinding: DiagnosticFinding = {
  ...mockFinding,
  id: 'finding-2',
  status: 'CONFIRMED',
  verificationResult: '0 rows returned — customer does not exist',
}

export const mockRefutedFinding: DiagnosticFinding = {
  ...mockFinding,
  id: 'finding-3',
  status: 'REFUTED',
  verificationResult: '1 row returned — customer exists, issue is elsewhere',
}

export const mockFindings: DiagnosticFinding[] = [
  mockFinding,
  mockConfirmedFinding,
]

export const mockTraceSteps: TraceStep[] = [
  {
    stepIndex: 0,
    fragmentId: 'node-root',
    fragmentLabel: 'Full Query',
    sql: 'SELECT * FROM orders JOIN customers ON orders.customer_id = customers.id',
    status: 'HAS_ROWS',
    rowCount: 50,
    elapsedMs: 12,
    error: null,
  },
  {
    stepIndex: 1,
    fragmentId: 'node-join',
    fragmentLabel: 'orders',
    sql: 'SELECT * FROM orders',
    status: 'NO_ROWS',
    rowCount: 0,
    elapsedMs: 8,
    error: null,
  },
]
