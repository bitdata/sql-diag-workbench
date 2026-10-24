import { describe, it, expect, vi } from 'vitest'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { mockTraceSteps, mockFinding } from '../../../test/fixtures'
import type { UseRowTraceReturn } from '../../../hooks/useRowTrace'
import RowTracePanel from '../RowTracePanel'

function buildMockRowTrace(overrides: Partial<UseRowTraceReturn> = {}): UseRowTraceReturn {
  return {
    traceId: null,
    status: 'idle',
    steps: [],
    eliminationStep: null,
    findings: [],
    totalElapsedMs: 0,
    error: null,
    eliminationOutcome: null,
    errorReason: null,
    trace: vi.fn(),
    cancel: vi.fn(),
    clear: vi.fn(),
    ...overrides,
  }
}

const defaultProps = {
  fragmentTreeJson: '{"type":"ORIGINAL"}',
  connectionId: 'conn-1',
}

describe('RowTracePanel', () => {
  describe('Empty states', () => {
    it('shows "Parse SQL first" when fragmentTreeJson is empty', () => {
      const rowTrace = buildMockRowTrace()
      const { getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} fragmentTreeJson="" rowTrace={rowTrace} />
      )
      expect(getByText(/Parse SQL first/)).toBeInTheDocument()
    })

    it('shows "Connect to a database first" when connectionId is null', () => {
      const rowTrace = buildMockRowTrace()
      const { getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} connectionId={null} rowTrace={rowTrace} />
      )
      expect(getByText(/Connect to a database first/)).toBeInTheDocument()
    })
  })

  describe('Idle state — input form', () => {
    it('renders column and value inputs with Trace button', () => {
      const rowTrace = buildMockRowTrace()
      const { getByPlaceholderText, getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByPlaceholderText(/Column/)).toBeInTheDocument()
      expect(getByPlaceholderText(/Value/)).toBeInTheDocument()
      expect(getByText('Trace')).toBeInTheDocument()
    })

    it('disables Trace button when inputs are empty', () => {
      const rowTrace = buildMockRowTrace()
      const { getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText('Trace')).toBeDisabled()
    })

    it('calls trace with column and value when Trace clicked', async () => {
      const trace = vi.fn()
      const rowTrace = buildMockRowTrace({ trace })
      const { user, getByPlaceholderText, getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      await user.type(getByPlaceholderText(/Column/), 'order_id')
      await user.type(getByPlaceholderText(/Value/), '12345')
      await user.click(getByText('Trace'))
      expect(trace).toHaveBeenCalledWith(
        'conn-1',
        '{"type":"ORIGINAL"}',
        [{ column: 'order_id', type: 'TEXT', value: '12345' }], { mode: 'EXISTENCE' }
      )
    })
  })

  describe('Running state', () => {
    it('shows Cancel button when trace is running', () => {
      const rowTrace = buildMockRowTrace({ status: 'RUNNING', traceId: 'trace-1' })
      const { getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText('Cancel')).toBeInTheDocument()
    })

    it('shows progress steps', () => {
      const rowTrace = buildMockRowTrace({
        status: 'RUNNING',
        traceId: 'trace-1',
        steps: mockTraceSteps,
      })
      const { getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText('Full Query')).toBeInTheDocument()
      expect(getByText('orders')).toBeInTheDocument()
    })

    it('shows elapsed time', () => {
      const rowTrace = buildMockRowTrace({
        status: 'RUNNING',
        traceId: 'trace-1',
        totalElapsedMs: 250,
      })
      const { getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText('250ms')).toBeInTheDocument()
    })

    it('calls cancel when Cancel button clicked', async () => {
      const cancel = vi.fn()
      const rowTrace = buildMockRowTrace({ status: 'RUNNING', traceId: 'trace-1', cancel })
      const { user, getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      await user.click(getByText('Cancel'))
      expect(cancel).toHaveBeenCalledOnce()
    })
  })

  describe('Completed state', () => {
    it('shows elimination point when present', () => {
      const eliminationStep = mockTraceSteps[1] // NO_ROWS step
      const rowTrace = buildMockRowTrace({
        status: 'COMPLETED',
        traceId: 'trace-1',
        steps: mockTraceSteps,
        eliminationStep,
      })
      const { getByText, getAllByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText(/Conclusion scope is unknown/)).toBeInTheDocument()
      expect(getAllByText(eliminationStep.fragmentLabel).length).toBeGreaterThanOrEqual(1)
    })

    it('shows diagnostic findings', () => {
      const rowTrace = buildMockRowTrace({
        status: 'COMPLETED',
        traceId: 'trace-1',
        steps: mockTraceSteps,
        findings: [mockFinding],
      })
      const { getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText('Diagnostic Findings')).toBeInTheDocument()
      expect(getByText(mockFinding.title)).toBeInTheDocument()
    })

    it('shows Re-trace and Clear buttons when completed', () => {
      const rowTrace = buildMockRowTrace({ status: 'COMPLETED', traceId: 'trace-1' })
      const { getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText('Re-trace')).toBeInTheDocument()
      expect(getByText('Clear')).toBeInTheDocument()
    })
  })

  describe('Error state', () => {
    it('shows error banner when trace has error', () => {
      const rowTrace = buildMockRowTrace({
        status: 'FAILED',
        traceId: 'trace-1',
        error: 'Connection timeout',
      })
      const { getByText } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText('Connection timeout')).toBeInTheDocument()
    })
  })

  describe('EliminationOutcome four-state rendering (2026-08-27)', () => {
    it('shows error outcome when outcome is ERROR', () => {
      const errorStep = mockTraceSteps[1]
      const rowTrace = buildMockRowTrace({
        status: 'COMPLETED',
        traceId: 'trace-1',
        steps: mockTraceSteps,
        eliminationStep: null,
        eliminationOutcome: {
          outcomeType: 'ERROR',
          step: errorStep,
          reason: 'Query timed out after 30s',
        },
        errorReason: 'Query timed out after 30s',
        evidence: { scope: 'CURRENT_TREE', coverage: 'PARTIAL', totalNodes: 2, observedNodes: 1, rootPresence: 'UNKNOWN', limitations: [] },
      })
      const { getByText, container } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText(/Cannot determine elimination point/)).toBeInTheDocument()
      expect(getByText(/Query timed out after 30s/)).toBeInTheDocument()
      expect(container.querySelector('.trace-outcome-error')).not.toBeNull()
    })

    it('shows missing-at-source when outcome is MISSING_AT_SOURCE', () => {
      const leaf = mockTraceSteps[0]
      const rowTrace = buildMockRowTrace({
        status: 'COMPLETED',
        traceId: 'trace-1',
        steps: mockTraceSteps,
        eliminationStep: null,
        eliminationOutcome: { outcomeType: 'MISSING_AT_SOURCE', leafStep: leaf },
        errorReason: null,
      })
      const { getByText, getAllByText, container } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText(/source data is unverified/)).toBeInTheDocument()
      // fragmentLabel appears both in the steps list and in the outcome
      // summary — assert at least one match (uses getAllByText).
      expect(getAllByText(leaf.fragmentLabel).length).toBeGreaterThanOrEqual(1)
      expect(container.querySelector('.trace-outcome-missing')).not.toBeNull()
    })

    it('shows no-elimination when outcome is NONE', () => {
      const rowTrace = buildMockRowTrace({
        status: 'COMPLETED',
        traceId: 'trace-1',
        steps: mockTraceSteps,
        eliminationStep: null,
        eliminationOutcome: { outcomeType: 'NONE' },
        evidence: { scope: 'CURRENT_TREE', coverage: 'ALL_OBSERVED', totalNodes: 2, observedNodes: 2, rootPresence: 'PRESENT', limitations: [] },
        errorReason: null,
      })
      const { getByText, container } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText(/The root query found the target key/)).toBeInTheDocument()
      expect(container.querySelector('.trace-outcome-none')).not.toBeNull()
    })

    it('falls back to eliminationStep rendering when outcome is null', () => {
      // Backward-compat: pre-2026-08-27 backend leaves outcome === null.
      const eliminationStep = mockTraceSteps[1]
      const rowTrace = buildMockRowTrace({
        status: 'COMPLETED',
        traceId: 'trace-1',
        steps: mockTraceSteps,
        eliminationStep,
        eliminationOutcome: null,
        errorReason: null,
      })
      const { getByText, container } = renderWithProviders(
        <RowTracePanel {...defaultProps} rowTrace={rowTrace} />
      )
      expect(getByText(/Conclusion scope is unknown/)).toBeInTheDocument()
      // Legacy class is the only summary container — no new outcome-* classes.
      expect(container.querySelector('.trace-outcome-error')).toBeNull()
      expect(container.querySelector('.trace-outcome-missing')).toBeNull()
      expect(container.querySelector('.trace-outcome-none')).toBeNull()
    })
  })
})
