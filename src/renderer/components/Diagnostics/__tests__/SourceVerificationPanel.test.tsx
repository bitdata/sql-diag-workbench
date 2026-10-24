import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import SourceVerificationPanel from '../SourceVerificationPanel'
const mocks = vi.hoisted(() => ({ api: vi.fn(), trace: vi.fn(), cancel: vi.fn(), clear: vi.fn(), state: {} as Record<string, unknown> }))
vi.mock('../../../utils/api', () => ({ apiFetch: mocks.api }))
vi.mock('../../../hooks/useRowTrace', () => ({ useRowTrace: () => ({ status: 'idle', steps: [], trace: mocks.trace, cancel: mocks.cancel, clear: mocks.clear, ...mocks.state }) }))
const props = { backendUrl: 'http://backend', connectionId: 'c1', keys: [{ column: 'out.parent', type: 'TEXT' as const, value: 'INV-99' }] }
beforeEach(() => { vi.clearAllMocks(); mocks.state = {}; mocks.api.mockResolvedValue({ ok: true, json: async () => ({ id: 'c1', dialect: 'mysql' }) }) })
const fill = () => fireEvent.change(screen.getByLabelText('Source relation'), { target: { value: 'sales_invoice_item' } })
describe('source observation boundaries', () => {
  it('uses actual connection dialect and source mappings, without changing the target parameters', async () => {
    render(<SourceVerificationPanel {...props} />); fill()
    fireEvent.change(screen.getByLabelText('Source column 1'), { target: { value: 'invoice_id' } })
    fireEvent.click(screen.getByText('Check source relation'))
    await waitFor(() => expect(mocks.trace).toHaveBeenCalled())
    const [id, tree, keys, options] = mocks.trace.mock.calls[0]
    expect(id).toBe('c1'); expect(JSON.parse(tree).sqlFragment).toBe('SELECT * FROM `sales_invoice_item`')
    expect(keys).toEqual([{ column: 'invoice_id', type: 'TEXT', value: 'INV-99' }]); expect(options).toEqual({ mode: 'CARDINALITY', queryTimeoutSeconds: 5 })
  })
  it('cannot turn an error carrying a stale zero into an absence conclusion', () => {
    mocks.state = { status: 'COMPLETED', error: 'contract invalid', errorReason: 'TRACE_RESPONSE_INVALID', quantity: { rootCount: '0', verdict: 'OBSERVATION_ONLY' } }
    render(<SourceVerificationPanel {...props} />)
    expect(screen.getByRole('status')).toHaveTextContent('UNKNOWN')
    expect(screen.queryByText(/No visible match/)).toBeNull()
  })
  it('shows zero only for a successful scoped observation and retains visibility limits', () => {
    mocks.state = { status: 'COMPLETED', quantity: { rootCount: '0', verdict: 'OBSERVATION_ONLY' } }
    render(<SourceVerificationPanel {...props} />)
    expect(screen.getByRole('status')).toHaveTextContent('No visible match in declared relation')
    expect(screen.getByText(/Views, row-level security/)).toBeInTheDocument()
  })
  it('does not submit a late preparation response after the declared relation changes', async () => {
    let resolve!: (value: unknown) => void
    mocks.api.mockImplementation(() => new Promise(r => { resolve = r }))
    render(<SourceVerificationPanel {...props} />); fill(); fireEvent.click(screen.getByText('Check source relation'))
    fireEvent.change(screen.getByLabelText('Source relation'), { target: { value: 'different_source' } })
    await act(async () => resolve({ ok: true, json: async () => ({ id: 'c1', dialect: 'mysql' }) }))
    expect(mocks.trace).not.toHaveBeenCalled()
    expect(mocks.clear).toHaveBeenCalled()
  })
  it('does not submit after unmount during connection lookup', async () => {
    let resolve!: (value: unknown) => void
    mocks.api.mockImplementation(() => new Promise(r => { resolve = r }))
    const view = render(<SourceVerificationPanel {...props} />); fill(); fireEvent.click(screen.getByText('Check source relation')); view.unmount()
    await act(async () => resolve({ ok: true, json: async () => ({ id: 'c1', dialect: 'mysql' }) }))
    expect(mocks.trace).not.toHaveBeenCalled()
  })
  it('rejects a different connection identity instead of using its dialect', async () => {
    mocks.api.mockResolvedValue({ ok: true, json: async () => ({ id: 'other', dialect: 'mysql' }) })
    render(<SourceVerificationPanel {...props} />); fill(); fireEvent.click(screen.getByText('Check source relation'))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('SOURCE_CONNECTION_MISMATCH'))
    expect(mocks.trace).not.toHaveBeenCalled()
  })
})
