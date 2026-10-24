import { beforeEach, expect, it, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import ComplexSourcePanel from '../ComplexSourcePanel'
const mocks = vi.hoisted(() => ({ api: vi.fn(), trace: vi.fn(), cancel: vi.fn(), clear: vi.fn(), state: {} as Record<string, unknown> }))
vi.mock('../../../utils/api', () => ({ apiFetch: mocks.api }))
vi.mock('../../../hooks/useRowTrace', () => ({ useRowTrace: () => ({ status: 'idle', steps: [], trace: mocks.trace, cancel: mocks.cancel, clear: mocks.clear, ...mocks.state }) }))
const props = { backendUrl: 'http://backend', connectionId: 'c1', fragmentTreeJson: JSON.stringify({ sqlFragment: 'SELECT invoice FROM invoices' }), keys: [{ column: 'invoice', type: 'TEXT' as const, value: 'INV-3' }] }
const source = (id: string) => ({ occurrence: id, schema: 'public', relation: 'items', path: ['query', 'cte:invoices'], sourceSql: 'SELECT parent AS source_key_0 FROM public.items',
  keys: [{ column: 'source_key_0', type: 'TEXT', value: 'INV-3' }], mappings: [{ keyIndex: 0, outputColumn: 'invoice', sourceExpression: 'parent' }] })
const plan = () => ({ status: 'COMPLETED', verified: true, connectionId: 'c1', originalSql: 'SELECT invoice FROM invoices', metadataSql: 'SELECT invoice FROM invoices LIMIT 0', sources: [source('source-1'), source('source-2')], issues: [] as Record<string, unknown>[], observedAt: '2026-10-16T00:00:00Z', errorReason: undefined as string | undefined })
beforeEach(() => { vi.clearAllMocks(); mocks.state = {}; mocks.api.mockResolvedValue({ ok: true, json: async () => plan() }) })
it('observes a chosen occurrence independently without executing all source queries', async () => {
  render(<ComplexSourcePanel {...props} />); fireEvent.click(screen.getByText('Resolve query sources'))
  await screen.findByText('Observe source-2'); expect(mocks.trace).not.toHaveBeenCalled()
  fireEvent.click(screen.getByText('Observe source-2')); expect(mocks.trace).toHaveBeenCalledTimes(1)
  expect(JSON.parse(mocks.trace.mock.calls[0][1]).sqlFragment).toBe(source('source-2').sourceSql)
  expect(mocks.trace.mock.calls[0][2]).toEqual(source('source-2').keys)
})
it('retains explicit incomplete coverage alongside a valid source observation', async () => {
  const p = plan(); p.status = 'PARTIAL'; p.verified = false; p.errorReason = 'SOURCE_COVERAGE_INCOMPLETE'; p.issues = [{ occurrence: 'source-3', schema: 'public', relation: 'payments', errorReason: 'SOURCE_RELATION_WITHOUT_KEY' }]
  mocks.api.mockResolvedValue({ ok: true, json: async () => p }); mocks.state = { status: 'COMPLETED', quantity: { verdict: 'OBSERVATION_ONLY', rootCount: '0' } }
  render(<ComplexSourcePanel {...props} />); fireEvent.click(screen.getByText('Resolve query sources')); await screen.findByText('Observe source-1')
  fireEvent.click(screen.getByText('Observe source-1'))
  expect(screen.getByLabelText('Complex source coverage')).toHaveTextContent('Partial coverage · UNKNOWN')
  expect(screen.getByLabelText('Complex source coverage')).toHaveTextContent('public.payments')
  expect(screen.getByLabelText('Observation source-1')).toHaveTextContent('No visible match in this physical source · Count: 0')
  expect(screen.getByLabelText('Observation source-2')).not.toHaveTextContent('Count:')
})
it.each(['value', 'index', 'identity', 'occurrence'])('rejects invalid %s before exposing any runnable source', async kind => {
  const p = plan()
  if (kind === 'value') p.sources[0].keys[0].value = 'other'
  if (kind === 'index') p.sources[0].mappings[0].keyIndex = 2
  if (kind === 'identity') p.connectionId = 'other'
  if (kind === 'occurrence') p.sources[1].occurrence = p.sources[0].occurrence
  mocks.api.mockResolvedValue({ ok: true, json: async () => p })
  render(<ComplexSourcePanel {...props} />); fireEvent.click(screen.getByText('Resolve query sources'))
  await waitFor(() => expect(screen.getByLabelText('Complex source coverage')).toHaveTextContent('SOURCE_PLAN_INVALID'))
  expect(screen.queryByText('Observe source-1')).toBeNull(); expect(mocks.trace).not.toHaveBeenCalled()
})
it('ignores a cancelled late resolution without accepting source counts', async () => {
  let resolve!: (value: unknown) => void; mocks.api.mockImplementation(() => new Promise(r => { resolve = r }))
  render(<ComplexSourcePanel {...props} />); fireEvent.click(screen.getByText('Resolve query sources')); fireEvent.click(screen.getByText('Cancel source resolution'))
  await act(async () => resolve({ ok: true, json: async () => plan() }))
  expect(screen.queryByText('Observe source-1')).toBeNull(); expect(screen.getByText(/server request may still/)).toBeInTheDocument()
})
it('removes all source observations when submitted investigation keys change', async () => {
  const view = render(<ComplexSourcePanel {...props} />); fireEvent.click(screen.getByText('Resolve query sources')); await screen.findByText('Observe source-1')
  view.rerender(<ComplexSourcePanel {...props} keys={[{ column: 'invoice', type: 'TEXT', value: 'INV-99' }]} />)
  expect(screen.queryByText('Observe source-1')).toBeNull(); expect(screen.getByLabelText('Complex source coverage')).not.toHaveTextContent('mapped')
})
