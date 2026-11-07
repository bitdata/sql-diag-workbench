import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import DiagnosticsPanel from '../DiagnosticsPanel'
import { mockFinding } from '../../../test/fixtures'
import type { DiagnosticLifecycle } from '../../../hooks/useDiagnostics'

const lifecycle = (status: DiagnosticLifecycle['status'], errorReason: string | null = null): DiagnosticLifecycle => ({ status, errorReason, ownerKey: 'o', generation: 1 })
const evaluation = { version: 1 as const, executionId: 'e', sourceState: 'VERIFIED' as const, rules: [], degraded: false, errorReason: null }
describe('diagnostic states', () => {
  it('distinguishes idle, loading, and a completed empty evaluation', () => {
    const { rerender } = render(<DiagnosticsPanel findings={[]} loading={false} error={null} lifecycle={lifecycle('idle')} />)
    expect(screen.queryByText('No findings from the evaluated rules')).toBeNull()
    expect(screen.getByText(/No diagnostics for the current plan/)).toBeInTheDocument()
    rerender(<DiagnosticsPanel findings={[]} loading={true} error={null} lifecycle={lifecycle('loading')} />)
    expect(screen.getByText('Analyzing diagnostic rules...')).toBeInTheDocument()
    rerender(<DiagnosticsPanel findings={[]} loading={false} error={null} lifecycle={lifecycle('ready')} evaluation={evaluation} />)
    expect(screen.getByText('No findings from the evaluated rules')).toBeInTheDocument()
  })
  it('shows limited findings without verification or plan navigation when the source is unavailable', () => {
    render(<DiagnosticsPanel findings={[{ ...mockFinding, planNodePath: 'p' }]} loading={false} error={null}
      lifecycle={lifecycle('unavailable', 'PLAN_SOURCE_EXPIRED')} evaluation={{ ...evaluation, sourceState: 'EXPIRED', degraded: true }}
      onVerify={vi.fn()} onPlanEvidence={vi.fn()} canPlanEvidence={() => true} />)
    expect(screen.getByText(mockFinding.title)).toBeInTheDocument()
    expect(screen.getByText(/Plan source unavailable/)).toHaveTextContent('PLAN_SOURCE_EXPIRED')
    expect(screen.queryByRole('button', { name: 'Verify' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'View plan evidence' })).toBeNull()
    expect(screen.queryByText('No findings from the evaluated rules')).toBeNull()
  })
  it('supports keyboard retry and restores focus to the current result after completion', async () => {
    const retry = vi.fn()
    const { rerender, container } = render(<DiagnosticsPanel findings={[]} loading={false} error="network down" lifecycle={lifecycle('error', 'DIAGNOSTICS_REQUEST_FAILED')} onRetry={retry} />)
    screen.getByRole('button', { name: 'Retry diagnostics' }).focus()
    await userEvent.keyboard('{Enter}')
    expect(retry).toHaveBeenCalledTimes(1)
    rerender(<DiagnosticsPanel findings={[]} loading={true} error={null} lifecycle={lifecycle('loading')} onRetry={retry} />)
    rerender(<DiagnosticsPanel findings={[mockFinding]} loading={false} error={null} lifecycle={lifecycle('ready')} evaluation={evaluation} onRetry={retry} onVerify={vi.fn()} />)
    expect(container.querySelector('.diagnostics-panel')).toHaveFocus()
  })
})
