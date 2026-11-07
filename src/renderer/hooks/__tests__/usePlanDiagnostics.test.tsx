import { StrictMode } from 'react'
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { usePlanDiagnostics, type DiagnosticSubject } from '../usePlanDiagnostics'
import { apiFetch } from '../../utils/api'
import { mockFinding, mockPlanResult, mockRootNode } from '../../test/fixtures'
import { setWorkspaceExitLocked } from '../../utils/workspaceExitGate'
import { useTaskNotices } from '../../utils/taskNotices'

vi.mock('../../utils/api', () => ({ apiFetch: vi.fn() }))
const response = (body: unknown) => ({ ok: true, json: async () => body }) as Response
const receipt = (executionId = 'e1', extra = {}) => ({ findings: [mockFinding], reportPersistence: { status: 'saved', errorReason: null },
  evaluation: { version: 1, executionId, sourceState: 'VERIFIED', rules: [], degraded: false, errorReason: null }, ...extra })
const subject = (extra: Partial<DiagnosticSubject> = {}): DiagnosticSubject => ({ kind: 'input', sessionId: 's', connectionId: 'c',
  dialect: 'postgresql', revision: 1, sql: mockRootNode.sqlFragment, result: { ...mockPlanResult, executionId: 'e1' }, ...extra })
function deferred() {
  let resolve!: (r: Response) => void
  return { promise: new Promise<Response>(r => { resolve = r }), resolve: (r: Response) => resolve(r) }
}
const mount = (s: DiagnosticSubject | null = subject()) => renderHook(
  ({ s, enabled, blocked, url }) => usePlanDiagnostics(url, s, enabled, blocked),
  { initialProps: { s, enabled: true, blocked: false, url: 'http://backend' } })

describe('plan diagnostics lifecycle', () => {
  beforeEach(() => { vi.mocked(apiFetch).mockReset(); setWorkspaceExitLocked(false) })

  it('starts idle without a plan; requests once and retains receipt over unrelated renders and mode round-trips', async () => {
    vi.mocked(apiFetch).mockResolvedValue(response(receipt()))
    const { result, rerender } = mount(null)
    expect(result.current.lifecycle.status).toBe('idle')
    expect(apiFetch).not.toHaveBeenCalled()
    rerender({ s: subject(), enabled: true, blocked: false, url: 'http://backend' })
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    const generation = result.current.lifecycle.generation
    for (const enabled of [false, true, true]) rerender({ s: subject(), enabled, blocked: false, url: 'http://backend' })
    expect(apiFetch).toHaveBeenCalledTimes(1)
    expect(result.current.lifecycle.generation).toBe(generation)
    expect(result.current.findings).toEqual([mockFinding])
  })

  it('discards on null/owner changes, then diagnoses a cached execution on re-entry', async () => {
    vi.mocked(apiFetch).mockResolvedValue(response(receipt()))
    const { result, rerender } = mount()
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    rerender({ s: null, enabled: true, blocked: false, url: 'http://backend' })
    expect(result.current.findings).toEqual([])
    expect(result.current.lifecycle.status).toBe('idle')
    rerender({ s: subject(), enabled: true, blocked: false, url: 'http://backend' })
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    expect(apiFetch).toHaveBeenCalledTimes(2)
    expect(vi.mocked(apiFetch).mock.calls.every(([url]) => String(url).endsWith('/diagnostics/plan'))).toBe(true)
  })

  it.each(['sessionId', 'connectionId', 'dialect', 'revision'] as const)('isolates a changed %s even with the same plan reference', async field => {
    vi.mocked(apiFetch).mockResolvedValue(response(receipt()))
    const { result, rerender } = mount()
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    const oldVerify = result.current.verify
    rerender({ s: subject({ [field]: field === 'revision' ? 2 : 'changed' }), enabled: true, blocked: false, url: 'http://backend' })
    expect(result.current.findings).toEqual([])
    expect(await oldVerify(mockFinding.id, mockFinding.verificationSql!, 'c')).toEqual({ status: 'skipped', errorReason: 'DIAGNOSTICS_ACTION_STALE' })
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    expect(apiFetch).toHaveBeenCalledTimes(2)
  })

  it('isolates a changed backend and a changed execution', async () => {
    vi.mocked(apiFetch).mockImplementation(async (_url, init) => response(receipt(JSON.parse(init!.body as string).executionId)))
    const { result, rerender } = mount()
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    rerender({ s: subject(), enabled: true, blocked: false, url: 'http://other' })
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    rerender({ s: subject({ result: { ...mockPlanResult, executionId: 'e2' } }), enabled: true, blocked: false, url: 'http://other' })
    expect(result.current.findings).toEqual([])
    await waitFor(() => expect(result.current.evaluation?.executionId).toBe('e2'))
    expect(apiFetch).toHaveBeenCalledTimes(3)
  })

  it('rejects old A replies after A -> no plan -> A, including after the new response', async () => {
    const old = deferred(), fresh = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    const { result, rerender } = mount()
    rerender({ s: null, enabled: true, blocked: false, url: 'http://backend' })
    rerender({ s: subject(), enabled: true, blocked: false, url: 'http://backend' })
    await act(async () => { fresh.resolve(response(receipt('e1', { findings: [{ ...mockFinding, title: 'fresh' }] }))) })
    await waitFor(() => expect(result.current.findings[0]?.title).toBe('fresh'))
    await act(async () => { old.resolve(response(receipt('e1', { findings: [{ ...mockFinding, title: 'old' }] }))) })
    expect(result.current.findings[0]?.title).toBe('fresh')
  })

  it('does not auto-loop on failure; explicitly retries once, preserving report-save failures separately', async () => {
    vi.mocked(apiFetch).mockRejectedValueOnce(new Error('network down')).mockResolvedValue(response(receipt('e1', { reportPersistence: { status: 'failed', errorReason: 'REPORT_SAVE_FAILED' } })))
    const { result, rerender } = mount()
    await waitFor(() => expect(result.current.lifecycle.status).toBe('error'))
    rerender({ s: subject(), enabled: true, blocked: false, url: 'http://backend' })
    expect(apiFetch).toHaveBeenCalledTimes(1)
    act(() => { result.current.retry!(); result.current.retry!() })
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    expect(apiFetch).toHaveBeenCalledTimes(2)
    expect(result.current.reportPersistence?.status).toBe('failed')
  })

  it('distinguishes an empty completed evaluation, partial coverage, and an expired source with limited findings', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce(response(receipt('e1', { findings: [] })))
      .mockResolvedValueOnce(response(receipt('e1', { evaluation: { ...receipt().evaluation, degraded: true, errorReason: 'PARTIAL_EVALUATION', rules: [{ ruleId: 'r', status: 'SKIPPED', errorReason: 'unsupported' }] } })))
      .mockResolvedValueOnce(response(receipt('e1', { evaluation: { ...receipt().evaluation, sourceState: 'EXPIRED', degraded: true, errorReason: 'PLAN_SOURCE_EXPIRED' } })))
    const { result } = mount()
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    expect(result.current.findings).toEqual([])
    act(() => result.current.clear())
    await waitFor(() => expect(result.current.findings).toHaveLength(1))
    expect(result.current.lifecycle.status).toBe('ready')
    expect(result.current.evaluation?.degraded).toBe(true)
    act(() => result.current.clear())
    await waitFor(() => expect(result.current.lifecycle.status).toBe('unavailable'))
    expect(result.current.lifecycle.errorReason).toBe('PLAN_SOURCE_EXPIRED')
    expect(result.current.findings).toEqual([mockFinding])
    expect((await result.current.verify(mockFinding.id, mockFinding.verificationSql!, 'c'))?.status).toBe('skipped')
    expect(apiFetch).toHaveBeenCalledTimes(3)
  })

  it.each([{}, { findings: [] }, receipt('wrong'), receipt('e1', { findings: [mockFinding, mockFinding] })])('rejects missing/mismatched/malformed response %j', async body => {
    vi.mocked(apiFetch).mockResolvedValue(response(body))
    const { result } = mount()
    await waitFor(() => expect(result.current.lifecycle.status).toBe('error'))
    expect(result.current.findings).toEqual([])
    expect(result.current.lifecycle.errorReason).toMatch(/^DIAGNOSTICS_/)
  })

  it('keeps loading when hidden in Data; only initiates idle analysis after returning to Performance', async () => {
    const pending = deferred()
    vi.mocked(apiFetch).mockReturnValue(pending.promise)
    const { result, rerender } = renderHook(({ enabled }) => usePlanDiagnostics('http://backend', subject(), enabled), { initialProps: { enabled: false } })
    expect(apiFetch).not.toHaveBeenCalled()
    rerender({ enabled: true }); rerender({ enabled: false })
    await act(async () => { pending.resolve(response(receipt())) })
    expect(result.current.lifecycle.status).toBe('ready')
    rerender({ enabled: true })
    expect(apiFetch).toHaveBeenCalledTimes(1)
  })

  it('invalidates while locked and restores without rerunning the plan; stale or invalid verification does not query', async () => {
    vi.mocked(apiFetch).mockResolvedValue(response(receipt()))
    const { result, rerender } = mount()
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    for (const [id, sql, connection] of [['missing', 'SELECT 1', 'c'], [mockFinding.id, 'different', 'c'], [mockFinding.id, mockFinding.verificationSql!, 'other']])
      expect((await result.current.verify(id, sql, connection))?.status).toBe('skipped')
    const old = result.current.verify
    rerender({ s: subject(), enabled: true, blocked: true, url: 'http://backend' })
    expect(result.current.findings).toEqual([])
    expect((await old(mockFinding.id, mockFinding.verificationSql!, 'c'))?.status).toBe('skipped')
    rerender({ s: subject(), enabled: true, blocked: false, url: 'http://backend' })
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    expect(apiFetch).toHaveBeenCalledTimes(2)
  })

  it('deduplicates verification and detaches a late result without changing a reused finding ID', async () => {
    const verify = deferred()
    vi.mocked(apiFetch).mockImplementation(async url => String(url).endsWith('/verify') ? verify.promise : response(receipt()))
    const { result, rerender } = mount()
    const notices = renderHook(() => useTaskNotices())
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    act(() => { void result.current.verify(mockFinding.id, mockFinding.verificationSql!, 'c'); void result.current.verify(mockFinding.id, mockFinding.verificationSql!, 'c') })
    expect(apiFetch).toHaveBeenCalledTimes(2)
    rerender({ s: null, enabled: true, blocked: false, url: 'http://backend' })
    expect(notices.result.current.some(n => n.errorReason === 'VERIFICATION_PENDING')).toBe(true)
    rerender({ s: subject(), enabled: true, blocked: false, url: 'http://backend' })
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    await act(async () => { verify.resolve(response({ status: 'CONFIRMED', resultSummary: 'late' })) })
    expect(result.current.findings[0].status).toBe('SUSPECTED')
    expect(notices.result.current.some(n => n.errorReason === 'VERIFICATION_PENDING')).toBe(false)
  })

  it('sends fragment identity and survives StrictMode replay', async () => {
    vi.mocked(apiFetch).mockResolvedValue(response(receipt()))
    const { result } = renderHook(() => usePlanDiagnostics('http://backend', subject({ kind: 'fragment', fragment: mockRootNode }), true), { wrapper: StrictMode })
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    const calls = vi.mocked(apiFetch).mock.calls
    const body = JSON.parse(calls[calls.length - 1][1]!.body as string)
    expect(body.fragmentId).toBe(mockRootNode.id)
    expect(body.executionId).toBe('e1')
    expect(String(calls[calls.length - 1][0])).toMatch(/diagnostics\/analyze$/)
  })

  it.each(['http', 'json', 'network'])('exposes %s failures without classifying them as empty findings', async failure => {
    if (failure === 'http') vi.mocked(apiFetch).mockResolvedValue({ ok: false, status: 502 } as Response)
    else if (failure === 'json') vi.mocked(apiFetch).mockResolvedValue({ ok: true, json: async () => { throw new SyntaxError('invalid json') } } as unknown as Response)
    else vi.mocked(apiFetch).mockRejectedValue(new Error('offline'))
    const { result } = mount()
    await waitFor(() => expect(result.current.lifecycle.status).toBe('error'))
    expect(result.current.lifecycle.errorReason).toMatch(/^DIAGNOSTICS_/)
    expect(result.current.evaluation).toBeUndefined()
    expect(apiFetch).toHaveBeenCalledTimes(1)
  })

  it('guards degraded, already confirmed, synchronously locked and unmounted verification callbacks', async () => {
    vi.mocked(apiFetch).mockResolvedValue(response(receipt('e1', { findings: [
      { ...mockFinding, id: 'degraded', degraded: true }, { ...mockFinding, id: 'confirmed', status: 'CONFIRMED' }, mockFinding,
    ] })))
    const { result, unmount } = mount()
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    for (const id of ['degraded', 'confirmed']) expect((await result.current.verify(id, mockFinding.verificationSql!, 'c'))?.status).toBe('skipped')
    setWorkspaceExitLocked(true)
    expect((await result.current.verify(mockFinding.id, mockFinding.verificationSql!, 'c'))?.status).toBe('skipped')
    setWorkspaceExitLocked(false)
    const verify = result.current.verify
    unmount()
    expect((await verify(mockFinding.id, mockFinding.verificationSql!, 'c'))?.status).toBe('skipped')
    expect(apiFetch).toHaveBeenCalledTimes(1)
  })

  it('does not let a ready callback verify during a synchronous clear before React commits', async () => {
    vi.mocked(apiFetch).mockResolvedValue(response(receipt()))
    const { result } = mount()
    await waitFor(() => expect(result.current.lifecycle.status).toBe('ready'))
    let dispatch: unknown
    await act(async () => {
      const previous = result.current
      previous.clear()
      dispatch = await previous.verify(mockFinding.id, mockFinding.verificationSql!, 'c')
    })
    expect(dispatch).toEqual({ status: 'skipped', errorReason: 'DIAGNOSTICS_ACTION_STALE' })
    expect(vi.mocked(apiFetch).mock.calls.every(([url]) => !String(url).endsWith('/verify'))).toBe(true)
  })
})
