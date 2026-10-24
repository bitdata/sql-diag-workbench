import { describe, it, expect, vi } from 'vitest'
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
    findings: [],
    loading: false,
    // Real hook contract (useDiagnostics.ts): the no-error state is
    // error === null — NOT ''. '' here used to hide the `??`-fallback bug.
    error: null,
    verifyingIds: new Set(),
    verifyErrors: {},
    analyze: vi.fn(),
    clear: vi.fn(),
    verify: vi.fn(),
    clearVerifyError: vi.fn(),
    ...overrides,
  }
}

describe('ResultsPanel', () => {
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
})
