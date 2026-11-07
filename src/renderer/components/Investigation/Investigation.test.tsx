import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { apiFetch } from '../../utils/api'
import { setLocale } from '../../i18n'
import SaveInvestigationDialog from './SaveInvestigationDialog'
import InvestigationHistory from './InvestigationHistory'
import InvestigationActions from './InvestigationActions'
import raw from '../../utils/__tests__/fixtures/investigation-snapshot.json'

vi.mock('../../utils/api', () => ({ apiFetch: vi.fn() }))
vi.mock('../Results/PlanView', () => ({ default: () => <div>archived plan</div> }))
const api = vi.mocked(apiFetch)
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })
const draft = { observationId: 'receipt', sessionId: null, workspace: null, evaluation: null, findings: null }
const summary = { id: raw.content.id, createdAt: raw.content.createdAt, title: 'saved', sessionName: null, dialect: 'postgresql',
  serverProduct: null, serverVersion: null, partial: false, degraded: false, pinned: false, availability: 'AVAILABLE', errorReason: null }
beforeEach(() => { setLocale('en'); api.mockReset() })
afterEach(cleanup)
async function fillSave() {
  fireEvent.change(screen.getByLabelText('Investigation title'), { target: { value: 'investigate' } })
  fireEvent.change(screen.getByLabelText('Observation notes'), { target: { value: 'preserve these notes' } })
  fireEvent.click(screen.getByRole('checkbox'))
}
describe('investigation save operation identity', () => {
  it('retains notes and request ID after an unknown outcome; guards duplicate submissions', async () => {
    let resolve!: (value: Response) => void
    api.mockImplementationOnce(() => new Promise(r => { resolve = r }))
      .mockResolvedValueOnce(response({ status: 'SAVED', snapshotId: 'saved' }))
    const saved = vi.fn()
    render(<SaveInvestigationDialog backendUrl="http://backend" draft={draft} isCurrent={() => true} onClose={vi.fn()} onSaved={saved} />)
    await fillSave()
    const form = screen.getByRole('button', { name: 'Save investigation' }).closest('form')!
    fireEvent.submit(form); fireEvent.submit(form)
    expect(api).toHaveBeenCalledTimes(1)
    await act(async () => resolve(response({ status: 'unexpected' })))
    expect(await screen.findByRole('alert')).toHaveTextContent('SNAPSHOT_INVALID_RESPONSE')
    expect(screen.getByLabelText('Observation notes')).toHaveValue('preserve these notes')
    fireEvent.submit(form)
    await waitFor(() => expect(saved).toHaveBeenCalledWith('saved'))
    const bodies = api.mock.calls.map(call => JSON.parse(String(call[1]?.body)))
    expect(bodies[0].requestId).toBe(bodies[1].requestId)
  })
  it('ignores a successful response after its owner becomes stale', async () => {
    let resolve!: (value: Response) => void, current = true
    api.mockImplementationOnce(() => new Promise(r => { resolve = r }))
    const saved = vi.fn()
    render(<SaveInvestigationDialog backendUrl="http://backend" draft={draft} isCurrent={() => current} onClose={vi.fn()} onSaved={saved} />)
    await fillSave(); fireEvent.click(screen.getByRole('button', { name: 'Save investigation' }))
    current = false
    await act(async () => resolve(response({ status: 'SAVED', snapshotId: 'old' })))
    expect(saved).not.toHaveBeenCalled()
  })
})
describe('history operation retries and lifetime', () => {
  it('returns focus to its opener after closing a history Portal', async () => {
    api.mockResolvedValueOnce(response({ items: [], nextCursor: null }))
    render(<InvestigationHistory backendUrl="http://backend" />)
    const opener = screen.getByRole('button', { name: 'Investigation history' })
    fireEvent.click(opener)
    await screen.findByText('No investigations saved.')
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(opener).toHaveFocus())
  })
  it('retries the failed detail rather than reloading the list', async () => {
    api.mockResolvedValueOnce(response({ items: [summary], nextCursor: null }))
      .mockResolvedValueOnce(response({ code: 'TEMPORARY_FAILURE' }, 503))
      .mockResolvedValueOnce(response(raw))
    render(<InvestigationHistory backendUrl="http://backend" />)
    fireEvent.click(screen.getByRole('button', { name: 'Investigation history' }))
    fireEvent.click(await screen.findByRole('button', { name: 'saved' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }))
    await screen.findByLabelText('Read-only investigation')
    expect(api.mock.calls.map(call => call[0])).toEqual(['http://backend/api/investigation-snapshots',
      `http://backend/api/investigation-snapshots/${summary.id}`, `http://backend/api/investigation-snapshots/${summary.id}`])
  })
  it('preserves previous page and cursor when retrying pagination', async () => {
    api.mockResolvedValueOnce(response({ items: [summary], nextCursor: 'cursor/2' }))
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(response({ items: [{ ...summary, id: 'second', title: 'second' }], nextCursor: null }))
    render(<InvestigationHistory backendUrl="http://backend" />)
    fireEvent.click(screen.getByRole('button', { name: 'Investigation history' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }))
    await screen.findByRole('button', { name: 'second' })
    expect(screen.getByRole('button', { name: 'saved' })).toBeVisible()
    expect(api.mock.calls[1][0]).toBe('http://backend/api/investigation-snapshots?cursor=cursor%2F2')
    expect(api.mock.calls[2][0]).toBe(api.mock.calls[1][0])
  })
  it('displays an unavailable row and still permits explicit deletion', async () => {
    api.mockResolvedValueOnce(response({ items: [{ ...summary, availability: 'UNAVAILABLE', errorReason: 'SNAPSHOT_CORRUPT' }], nextCursor: null }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(response({ items: [], nextCursor: null }))
    render(<InvestigationHistory backendUrl="http://backend" />)
    fireEvent.click(screen.getByRole('button', { name: 'Investigation history' }))
    expect(await screen.findByRole('button', { name: 'Investigation unavailable' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Delete snapshot' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm deletion' }))
    await screen.findByText('No investigations saved.')
    expect(api.mock.calls[1][1]?.method).toBe('DELETE')
  })
  it('ignores a list response from a replaced backend', async () => {
    let resolve!: (value: Response) => void
    api.mockImplementationOnce(() => new Promise(r => { resolve = r }))
      .mockResolvedValueOnce(response({ items: [], nextCursor: null }))
    const view = render(<InvestigationHistory backendUrl="http://old" />)
    fireEvent.click(screen.getByRole('button', { name: 'Investigation history' }))
    view.rerender(<InvestigationHistory backendUrl="http://new" />)
    await screen.findByText('No investigations saved.')
    await act(async () => resolve(response({ items: [summary], nextCursor: null })))
    expect(screen.queryByRole('button', { name: 'saved' })).not.toBeInTheDocument()
  })
})

describe('current observation availability', () => {
  const p = raw.content.observation
  const props = () => ({ backendUrl: 'http://backend', result: { status: 'COMPLETED', executionId: p.executionId,
    plan: p.plan, planCapture: p.planCapture, observationReceipt: { status: 'SAVED', receiptId: p.id } } as any,
    provenance: { sql: p.submittedSql, connectionId: p.connectionId, dialect: p.dialect },
    sessionId: p.sessionId, scope: 'ordinary-or-compare-side', fragmentTreeJson: JSON.stringify(raw.content.workspace.value), loading: false })
  it('blocks saving until receipt metadata is verified, exposes expiry and retries metadata failure', async () => {
    api.mockRejectedValueOnce(new Error('metadata unavailable')).mockResolvedValueOnce(response(p))
    render(<InvestigationActions {...props()} />)
    expect(screen.getByRole('button', { name: 'Save investigation' })).toBeDisabled()
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save investigation' })).toBeEnabled())
    expect(screen.getByText(new RegExp(p.expiresAt.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Save investigation' }))
    expect(await screen.findByRole('dialog', { name: 'Save investigation' })).toBeVisible()
  })
  it('cannot enable a replacement owner using a delayed previous receipt', async () => {
    let resolve!: (value: Response) => void
    api.mockImplementationOnce(() => new Promise(r => { resolve = r })).mockRejectedValueOnce(new Error('new unavailable'))
    const view = render(<InvestigationActions {...props()} />)
    view.rerender(<InvestigationActions {...props()} scope="new-compare-side" />)
    await screen.findByRole('alert')
    await act(async () => resolve(response(p)))
    expect(screen.getByRole('button', { name: 'Save investigation' })).toBeDisabled()
  })
  it('rejects metadata for a different execution and never opens the save dialog', async () => {
    api.mockResolvedValueOnce(response({ ...p, executionId: 'different' }))
    render(<InvestigationActions {...props()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('SNAPSHOT_SCOPE_MISMATCH')
    expect(screen.getByRole('button', { name: 'Save investigation' })).toBeDisabled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
