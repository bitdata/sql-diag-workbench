import { beforeEach, expect, it, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import PhysicalSourcePanel from '../PhysicalSourcePanel'
const mocks = vi.hoisted(() => ({ api: vi.fn(), trace: vi.fn(), cancel: vi.fn(), clear: vi.fn(), state: {} as Record<string, unknown> }))
vi.mock('../../../utils/api', () => ({ apiFetch: mocks.api }))
vi.mock('../../../hooks/useRowTrace', () => ({ useRowTrace: () => ({ status: 'idle', steps: [], trace: mocks.trace, cancel: mocks.cancel, clear: mocks.clear, ...mocks.state }) }))
const props = { backendUrl: 'http://backend', connectionId: 'c1', fragmentTreeJson: JSON.stringify({ id: 'root', sqlFragment: 'SELECT parent AS invoice FROM sales_invoice_item' }),
  keys: [{ column: 'invoice', type: 'TEXT' as const, value: 'INV-99' }] }
const plan = () => ({ status: 'COMPLETED', verified: true, connectionId: 'c1', originalSql: 'SELECT parent AS invoice FROM sales_invoice_item', metadataSql: 'SELECT parent AS invoice FROM sales_invoice_item LIMIT 0',
  schema: 'public', relation: 'sales_invoice_item', sourceSql: 'SELECT * FROM "public"."sales_invoice_item"', keys: [{ column: 'parent', type: 'TEXT', value: 'INV-99' }], observedAt: '2026-10-16T00:00:00Z' })
beforeEach(() => { vi.clearAllMocks(); mocks.state = {}; mocks.api.mockResolvedValue({ ok: true, json: async () => plan() }) })
it('binds the original query to verified mapped keys, preserving typed values', async () => {
  render(<PhysicalSourcePanel {...props} />); fireEvent.click(screen.getByText('Check query source'))
  await waitFor(() => expect(mocks.trace).toHaveBeenCalled())
  expect(JSON.parse(mocks.api.mock.calls[0][1].body)).toEqual({ connectionId: 'c1', sql: plan().originalSql, keys: props.keys })
  const [id, tree, keys, options] = mocks.trace.mock.calls[0]
  expect(id).toBe('c1'); expect(JSON.parse(tree).sqlFragment).toBe(plan().sourceSql); expect(keys).toEqual(plan().keys)
  expect(options).toEqual({ mode: 'CARDINALITY', queryTimeoutSeconds: 5 })
})
it.each(['connection', 'sql', 'value', 'type'])('rejects a mismatched %s instead of accepting its source observation', async kind => {
  const data = plan()
  if (kind === 'connection') data.connectionId = 'other'
  if (kind === 'sql') data.originalSql = 'SELECT other'
  if (kind === 'value') data.keys[0].value = 'different'
  if (kind === 'type') data.keys[0].type = 'DECIMAL'
  mocks.api.mockResolvedValue({ ok: true, json: async () => data })
  render(<PhysicalSourcePanel {...props} />); fireEvent.click(screen.getByText('Check query source'))
  await waitFor(() => expect(screen.getByLabelText('Physical source observation')).toHaveTextContent('SOURCE_PLAN_INVALID'))
  expect(mocks.trace).not.toHaveBeenCalled()
})
it('cannot turn unresolved lineage into a zero count', async () => {
  mocks.api.mockResolvedValue({ ok: true, json: async () => ({ status: 'UNKNOWN', verified: false, errorReason: 'SOURCE_ORIGIN_MISMATCH' }) })
  mocks.state = { status: 'COMPLETED', quantity: { verdict: 'OBSERVATION_ONLY', rootCount: '0' } }
  render(<PhysicalSourcePanel {...props} />); fireEvent.click(screen.getByText('Check query source'))
  await waitFor(() => expect(screen.getByLabelText('Physical source observation')).toHaveTextContent('SOURCE_ORIGIN_MISMATCH'))
  expect(screen.queryByText(/No visible match in verified/)).toBeNull(); expect(mocks.trace).not.toHaveBeenCalled()
})
it('ignores a late preparation response after cancellation and discloses server uncertainty', async () => {
  let resolve!: (value: unknown) => void
  mocks.api.mockImplementation(() => new Promise(r => { resolve = r }))
  render(<PhysicalSourcePanel {...props} />); fireEvent.click(screen.getByText('Check query source'))
  fireEvent.click(screen.getByText('Cancel physical source check'))
  await act(async () => resolve({ ok: true, json: async () => plan() }))
  expect(mocks.trace).not.toHaveBeenCalled(); expect(screen.getByText(/server request may still/)).toBeInTheDocument()
})
it('invalidates a late preparation when the query scope changes', async () => {
  let resolve!: (value: unknown) => void
  mocks.api.mockImplementation(() => new Promise(r => { resolve = r }))
  const view = render(<PhysicalSourcePanel {...props} />); fireEvent.click(screen.getByText('Check query source'))
  view.rerender(<PhysicalSourcePanel {...props} fragmentTreeJson={JSON.stringify({ sqlFragment: 'SELECT 1' })} />)
  await act(async () => resolve({ ok: true, json: async () => plan() }))
  expect(mocks.trace).not.toHaveBeenCalled()
})
