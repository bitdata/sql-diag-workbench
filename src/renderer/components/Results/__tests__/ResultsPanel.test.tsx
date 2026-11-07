import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, waitFor } from '@testing-library/react'
import { sqlSha256 } from '../../../utils/planSqlMapping'
import { renderWithProviders } from '../../../test/renderWithProviders'
import {
  mockPlanResult,
  mockPlanErrorResult,
  mockFindings,
} from '../../../test/fixtures'
import type { DiagnosticsHook } from '../../../hooks/useDiagnostics'
import ResultsPanel from '../ResultsPanel'

function buildDiagnosticsHook(overrides: Partial<DiagnosticsHook> = {}): DiagnosticsHook {
  return {
    lifecycle: { status: 'ready', ownerKey: 'test', generation: 1, errorReason: null },
    findings: [],
    loading: false,
    // Real hook contract (useDiagnostics.ts): the no-error state is
    // error === null — NOT ''. '' here used to hide the `??`-fallback bug.
    error: null,
    verifyingIds: new Set(),
    verifyErrors: {},
    analyze: vi.fn(),
    analyzePlan: vi.fn(),
    clear: vi.fn(),
    verify: vi.fn(),
    clearVerifyError: vi.fn(),
    ...overrides,
  }
}

describe('ResultsPanel', () => {
  const originalFetch = globalThis.fetch
  beforeEach(() => {
    globalThis.fetch = vi.fn(() => Promise.resolve(new Response(JSON.stringify({
      version: 1, executionId: 'exec-2', captureKey: 'capture', dialect: 'postgresql',
      submittedSqlSha256: 'hash', edges: [], degraded: false, errorReason: null, issues: [],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))) as typeof fetch
  })
  afterEach(() => { globalThis.fetch = originalFetch })

  describe('Data mode', () => {
    it('shows Results and Row Trace tabs', () => {
      const { getByRole } = renderWithProviders(
        <ResultsPanel result={null} loading={false} error={null} mode="data" />
      )
      expect(getByRole('tab', { name: 'Results' })).toBeInTheDocument()
      expect(getByRole('tab', { name: 'Row Trace' })).toBeInTheDocument()
    })

    it('does not show Plan or Diagnostics tabs', () => {
      const { queryByRole } = renderWithProviders(
        <ResultsPanel result={null} loading={false} error={null} mode="data" />
      )
      expect(queryByRole('tab', { name: 'Plan' })).not.toBeInTheDocument()
      expect(queryByRole('tab', { name: 'Diagnostics' })).not.toBeInTheDocument()
    })

    it('shows "Row Trace not available" when rowTrace prop is omitted', async () => {
      const { getByText, getByRole, user } = renderWithProviders(
        <ResultsPanel result={null} loading={false} error={null} mode="data" />
      )
      // Switch to Row Trace tab
      await user.click(getByRole('tab', { name: 'Row Trace' }))
      expect(getByText('Row Trace not available')).toBeInTheDocument()
    })
  })

  describe('Performance mode', () => {
    it('shows Plan and Diagnostics tabs', () => {
      const { getByRole } = renderWithProviders(
        <ResultsPanel result={null} loading={false} error={null} mode="performance" />
      )
      expect(getByRole('tab', { name: 'Plan' })).toBeInTheDocument()
      expect(getByRole('tab', { name: 'Diagnostics' })).toBeInTheDocument()
    })

    it('does not show Results or Row Trace tabs', () => {
      const { queryByRole } = renderWithProviders(
        <ResultsPanel result={null} loading={false} error={null} mode="performance" />
      )
      expect(queryByRole('tab', { name: 'Results' })).not.toBeInTheDocument()
      expect(queryByRole('tab', { name: 'Row Trace' })).not.toBeInTheDocument()
    })

    it('shows planError when plan execution failed', () => {
      const { getByText, getByRole } = renderWithProviders(
        <ResultsPanel
          result={mockPlanErrorResult}
          loading={false}
          error={null}
          mode="performance"
        />
      )
      // Plan tab is default in performance mode
      expect(getByRole('tab', { name: 'Plan' })).toHaveAttribute('aria-selected', 'true')
      // PlanView should show the planError text
      expect(getByText(/EXPLAIN ANALYZE requires an active connection/)).toBeInTheDocument()
    })
  })

  describe('Loading and error states', () => {
    it('defaults to data mode when mode prop is omitted', () => {
      const { getByRole } = renderWithProviders(
        <ResultsPanel result={null} loading={false} error={null} />
      )
      expect(getByRole('tab', { name: 'Results' })).toBeInTheDocument()
    })
  })

  describe('Diagnostics integration', () => {
    it('passes findings to DiagnosticsPanel in performance mode', async () => {
      const diagnostics = buildDiagnosticsHook({ findings: mockFindings })
      const { getAllByText, getByRole, user } = renderWithProviders(
        <ResultsPanel
          result={mockPlanResult}
          loading={false}
          error={null}
          mode="performance"
          diagnostics={diagnostics}
        />
      )
      // Switch to diagnostics tab
      await user.click(getByRole('tab', { name: 'Diagnostics' }))
      // Both mock findings share the same title, so use getAllByText
      const titles = getAllByText(mockFindings[0].title)
      expect(titles).toHaveLength(mockFindings.length)
    })

    it('renders finding cards (no bogus failure) when the hook is present with error: null', async () => {
      // 2026-09-09 credential-tier E2E landmine C regression: useDiagnostics keeps
      // error: null in the no-error state; ResultsPanel's `??` fallback turned that
      // into a fake "Analysis failed" screen for EVERY real user, hiding the cards.
      const diagnostics = buildDiagnosticsHook({ findings: mockFindings, error: null })
      const { getAllByText, getByRole, queryByText, user } = renderWithProviders(
        <ResultsPanel
          result={mockPlanResult}
          loading={false}
          error={null}
          mode="performance"
          diagnostics={diagnostics}
        />
      )
      await user.click(getByRole('tab', { name: 'Diagnostics' }))
      // Emit-type assertions: cards render AND no failure banner (task #11
      // "empty assertion" lesson — both directions pinned).
      expect(getAllByText(mockFindings[0].title)).toHaveLength(mockFindings.length)
      expect(queryByText(/Analysis failed/)).not.toBeInTheDocument()
    })

    it('shows the real error screen when the hook is present with an error message', async () => {
      // Third leg of the tri-state (task-C+D review Minor-1): hook present AND
      // error non-null is a GENUINE failure — the message must surface verbatim
      // instead of the cards. Pins that the landmine-C fix did not swing the
      // pendulum the other way (null suppressed, string also suppressed).
      const diagnostics = buildDiagnosticsHook({
        findings: mockFindings,
        error: 'Diagnostics backend exploded',
      })
      const { getByText, getByRole, queryAllByText, user } = renderWithProviders(
        <ResultsPanel
          result={mockPlanResult}
          loading={false}
          error={null}
          mode="performance"
          diagnostics={diagnostics}
        />
      )
      await user.click(getByRole('tab', { name: 'Diagnostics' }))
      expect(getByText(/Diagnostics backend exploded/)).toBeInTheDocument()
      expect(queryAllByText(mockFindings[0].title)).toHaveLength(0)
    })

    it('shows the hook-not-provided fallback only when the diagnostics prop is absent', async () => {
      // The fallback string is for the UNWIRED case (undefined hook), not the
      // healthy no-error state — both states must stay distinguishable.
      const { getByText, getByRole, user } = renderWithProviders(
        <ResultsPanel result={mockPlanResult} loading={false} error={null} mode="performance" />
      )
      await user.click(getByRole('tab', { name: 'Diagnostics' }))
      // Regex: the panel renders "Analysis failed: " + the fallback as sibling
      // text nodes, so exact-string getByText would not match the span.
      expect(getByText(/Diagnostics unavailable \(hook not provided\)/)).toBeInTheDocument()
    })
  })

  it('does not re-request mapping when findings are recreated with the same content', async () => {
    const result = { ...mockPlanResult, executionId: 'exec-2' }
    const provenance = { sql: 'SELECT * FROM orders', connectionId: 'conn-1', dialect: 'postgresql', connectionName: 'Test', source: 'query' }
    const first = buildDiagnosticsHook({ findings: [...mockFindings] })
    const mappingCalls = () => vi.mocked(globalThis.fetch).mock.calls.filter(([input]) => String(input).endsWith('/api/diagnostics/plan-mapping'))
    const { rerender } = render(
      <ResultsPanel result={result} loading={false} error={null} mode="performance"
        backendUrl="http://test" provenance={provenance} diagnostics={first} />
    )
    await waitFor(() => expect(mappingCalls()).toHaveLength(1))

    rerender(<ResultsPanel result={result} loading={false} error={null} mode="performance"
      backendUrl="http://test" provenance={provenance}
      diagnostics={buildDiagnosticsHook({ findings: [...mockFindings] })} />)
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 0)) })
    expect(mappingCalls()).toHaveLength(1)
  })

  it('publishes request-time evidence, refreshes on finding content/tree revision, and preserves plan tools', async () => {
    const sql = 'SELECT * FROM orders', hash = await sqlSha256(sql)
    const metric = { state: 'AVAILABLE' as const, value: '1', unit: 'ROWS' as const, basis: 'PER_LOOP_AVERAGE' as const, sourceField: 'rows', errorReason: null }
    const result = { ...mockPlanResult, plan: { ...mockPlanResult.plan!, metrics: { version: 1 as const, origin: 'DATABASE_NODE' as const, sourcePath: '$', estimatedRows: metric, actualRows: metric, actualDurationMs: metric, loops: metric, startupCost: metric, totalCost: metric } } }
    const provenance = { sql, connectionId: 'c', dialect: 'postgresql', connectionName: 'Test', source: 'query' }
    const evaluation = { version: 1 as const, executionId: result.executionId, sourceState: 'VERIFIED' as const, rules: [], degraded: false, errorReason: null }
    const finding = { ...mockFindings[0], planNodePath: '$', title: 'Initial evidence' }
    const requests: unknown[] = []
    globalThis.fetch = vi.fn(async (_input, init) => {
      const body = JSON.parse(init!.body as string); requests.push(body)
      return new Response(JSON.stringify({ version: 1, executionId: result.executionId, dialect: 'postgresql', submittedSqlSha256: hash,
        captureKey: `${result.executionId}:postgresql:${hash}`, degraded: false, errorReason: null, issues: [],
        edges: [{ planNodePath: '$', targetKind: 'DIAGNOSTIC_EVIDENCE', targetId: finding.id, confidence: 'INFERRED', relation: 'PLAN_TO_EVIDENCE', span: null, reason: 'candidate' }] }))
    }) as typeof fetch
    const panel = (title: string, draftScope: string, executionId = result.executionId) => <ResultsPanel result={{ ...result, executionId }} provenance={provenance} loading={false} error={null} mode="performance"
      backendUrl="http://test" sessionId="s" draftScope={draftScope} diagnostics={buildDiagnosticsHook({ evaluation, findings: [{ ...finding, title }] })} />
    const { getByRole, getByLabelText, queryByLabelText, container, rerender } = render(panel('Initial evidence', 'revision-1'))
    fireEvent.click(container.querySelector('.plan-row')!)
    await waitFor(() => expect(getByRole('button', { name: 'View evidence' })).toBeInTheDocument())
    fireEvent.change(getByLabelText('Plan order'), { target: { value: 'actualRows' } })
    fireEvent.change(getByLabelText('Filter nodes'), { target: { value: result.plan.operation } })
    fireEvent.click(getByRole('button', { name: 'View evidence' }))
    expect(getByLabelText('Mapping target preview')).toHaveTextContent('Initial evidence')
    expect(getByLabelText('Plan order')).toHaveValue('actualRows')
    expect(getByLabelText('Filter nodes')).toHaveValue(result.plan.operation)
    fireEvent.click(getByRole('button', { name: 'Close preview' }))
    rerender(panel('Updated evidence', 'revision-1'))
    await waitFor(() => expect(requests).toHaveLength(2))
    await waitFor(() => expect(getByRole('button', { name: 'View evidence' })).toBeInTheDocument())
    fireEvent.click(getByRole('button', { name: 'View evidence' }))
    expect(getByLabelText('Mapping target preview')).toHaveTextContent('Updated evidence')
    rerender(panel('Updated evidence', 'revision-2'))
    expect(queryByLabelText('Mapping target preview')).toBeNull()
    await waitFor(() => expect(requests).toHaveLength(3))
    expect(container.querySelector('.plan-node-details')).toBeInTheDocument()
    expect(getByLabelText('Plan order')).toHaveValue('actualRows')
    expect(getByLabelText('Filter nodes')).toHaveValue(result.plan.operation)
    await waitFor(() => expect(getByRole('button', { name: 'View evidence' })).toBeInTheDocument())
    fireEvent.click(getByRole('button', { name: 'View evidence' }))
    rerender(panel('Updated evidence', 'revision-2', 'next-execution'))
    expect(queryByLabelText('Mapping target preview')).toBeNull()
    await waitFor(() => expect(getByRole('tab', { name: 'Plan' })).toHaveFocus())
  })

  it.each(['success', 'failure'] as const)('ignores an obsolete mapping request that finishes with %s after the current scope', async outcome => {
    const sql = 'SELECT id FROM orders'
    const hash = await sqlSha256(sql)
    const requests: { resolve: (response: Response) => void; reject: (error: Error) => void }[] = []
    globalThis.fetch = vi.fn(() => new Promise<Response>((resolve, reject) => requests.push({ resolve, reject }))) as typeof fetch
    const metric = { state: 'AVAILABLE' as const, value: '1', unit: 'ROWS' as const, basis: 'PER_LOOP_AVERAGE' as const, sourceField: 'rows', errorReason: null }
    const result = { ...mockPlanResult, executionId: 'exec-race', plan: { ...mockPlanResult.plan!,
      metrics: { version: 1 as const, origin: 'DATABASE_NODE' as const, sourcePath: 'tree[0]',
        estimatedRows: metric, actualRows: metric, actualDurationMs: metric, loops: metric, startupCost: metric, totalCost: metric } } }
    const provenance = { sql, connectionId: 'conn-1', dialect: 'postgresql', connectionName: 'Test', source: 'query' }
    const panel = (sessionId: string) => <ResultsPanel result={result} loading={false} error={null} mode="performance"
      backendUrl="http://test" provenance={provenance} sessionId={sessionId} />
    const response = (label: string) => new Response(JSON.stringify({
      version: 1, executionId: result.executionId, dialect: provenance.dialect, submittedSqlSha256: hash,
      captureKey: `${result.executionId}:postgresql:${hash}`, degraded: false, errorReason: null, issues: [],
      edges: [{ planNodePath: 'tree[0]', targetKind: 'FRAGMENT', targetId: label, span: null,
        confidence: 'INFERRED', relation: 'PLAN_TO_FRAGMENT', reason: label }],
    }), { status: 200 })
    const { container, rerender } = render(panel('session-old'))
    await waitFor(() => expect(requests).toHaveLength(1))
    fireEvent.click(container.querySelector('.plan-row')!)
    rerender(panel('session-current'))
    await waitFor(() => expect(requests).toHaveLength(2))
    await act(async () => { requests[1].resolve(response('current-scope-target')) })
    await waitFor(() => expect(container.querySelector('.plan-mapping')).toHaveTextContent('current-scope-target'))
    await act(async () => {
      if (outcome === 'success') requests[0].resolve(response('obsolete-scope-target'))
      else requests[0].reject(new Error('obsolete-scope-failure'))
    })
    expect(container.querySelector('.plan-mapping')).toHaveTextContent('current-scope-target')
    expect(container.querySelector('.plan-mapping')).not.toHaveTextContent('obsolete-scope')
  })

  it('does not submit an obsolete request when the scope changes while hashing', async () => {
    const digest = crypto.subtle.digest.bind(crypto.subtle)
    let finishFirst!: (value: ArrayBuffer) => void
    const spy = vi.spyOn(crypto.subtle, 'digest').mockImplementationOnce(() => new Promise<ArrayBuffer>(resolve => { finishFirst = resolve }))
    const result = { ...mockPlanResult, executionId: 'hash-race' }
    const provenance = { sql: 'SELECT 1', connectionId: 'c', dialect: 'postgresql', connectionName: 'Test', source: 'query' }
    const panel = (draftScope: string) => <ResultsPanel result={result} loading={false} error={null} mode="performance" backendUrl="http://test" provenance={provenance} draftScope={draftScope} />
    try {
      const { rerender } = render(panel('old-tree'))
      await waitFor(() => expect(finishFirst).toBeTypeOf('function'))
      expect(globalThis.fetch).not.toHaveBeenCalled()
      rerender(panel('current-tree'))
      await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledTimes(1))
      await act(async () => { finishFirst(await digest('SHA-256', new TextEncoder().encode('SELECT 1'))) })
      expect(globalThis.fetch).toHaveBeenCalledTimes(1)
    } finally { spy.mockRestore() }
  })
})

it('only navigates findings from the displayed execution and drops selection on a new capture', async () => {
  const m = { state: 'AVAILABLE' as const, value: '1', unit: 'ROWS' as const, basis: 'PER_LOOP_AVERAGE' as const, sourceField: 'rows', errorReason: null }
  const result = { ...mockPlanResult, executionId: 'capture-one', plan: { ...mockPlanResult.plan!, metrics: { version: 1 as const, origin: 'DATABASE_NODE' as const, sourcePath: 'tree[0]', estimatedRows: m, actualRows: m, actualDurationMs: m, loops: m, startupCost: m, totalCost: m } } }
  const findings = [{ ...mockFindings[0], planNodePath: 'tree[0]' }]
  const evaluation = { version: 1 as const, executionId: 'capture-one', sourceState: 'VERIFIED' as const, rules: [], degraded: false, errorReason: null }
  const diagnostics = buildDiagnosticsHook({ findings, evaluation })
  const { getByRole, queryByRole, container, rerender, user } = renderWithProviders(<ResultsPanel result={result} loading={false} error={null} mode="performance" diagnostics={diagnostics} />)
  await user.click(getByRole('tab', { name: 'Diagnostics' }))
  await user.click(getByRole('button', { name: 'View plan evidence' }))
  expect(getByRole('tab', { name: 'Plan' })).toHaveAttribute('data-state', 'active')
  expect(container.querySelector('.plan-node-details')).toHaveTextContent('tree[0]')
  expect(container.querySelector('.plan-node-details')).toHaveFocus()
  rerender(<ResultsPanel result={{ ...result, executionId: 'capture-two' }} loading={false} error={null} mode="performance" diagnostics={diagnostics} />)
  expect(container.querySelector('.plan-node-details')).toBeNull()
  await user.click(getByRole('tab', { name: 'Diagnostics' }))
  expect(queryByRole('button', { name: 'View plan evidence' })).toBeNull()
  expect(container.querySelector('.diagnostic-card')).toBeNull()
  expect(container).toHaveTextContent('No diagnostics for the current plan yet.')
})
