import type { ExecutionResult, PlanNode } from '../../types/execution'

export const mockSuccessResult: ExecutionResult = {
  executionId: 'exec-1',
  status: 'COMPLETED',
  columns: [
    { name: 'id', type: 'integer' },
    { name: 'name', type: 'varchar' },
  ],
  rows: [
    [1, 'Alice'],
    [2, 'Bob'],
    [3, 'Charlie'],
  ],
  rowCount: 3,
  timing: { durationMs: 42 },
  plan: null,
  planError: null,
  error: null,
}

export const mockPlanNode: PlanNode = {
  operation: 'Seq Scan on orders',
  startupCost: 0,
  totalCost: 12.5,
  planRows: 100,
  actualRows: 95,
  actualDuration: 3.2,
  children: [],
}

export const mockPlanResult: ExecutionResult = {
  executionId: 'exec-2',
  status: 'COMPLETED',
  columns: null,
  rows: null,
  rowCount: 0,
  timing: { durationMs: 150 },
  plan: mockPlanNode,
  planError: null,
  error: null,
}

export const mockPlanErrorResult: ExecutionResult = {
  executionId: 'exec-3',
  status: 'COMPLETED',
  columns: null,
  rows: null,
  rowCount: 0,
  timing: null,
  plan: null,
  planError: 'EXPLAIN ANALYZE requires an active connection',
  error: null,
}

export const mockErrorResult: ExecutionResult = {
  executionId: 'exec-4',
  status: 'FAILED',
  columns: null,
  rows: null,
  rowCount: 0,
  timing: null,
  plan: null,
  planError: null,
  error: 'relation "orders" does not exist',
}
