import { describe, it, expect, vi, afterEach } from 'vitest'
import { act, screen } from '@testing-library/react'
import type { ComponentProps } from 'react'
import type { ExecutionStatus, ExecutionResult } from '../../../types/execution'
import type { WorkspaceMode } from '../../../types/mode'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { mockSuccessResult, mockPlanResult } from '../../../test/fixtures'
import ExecutionStatusInline from '../ExecutionStatusInline'

type StatusProps = ComponentProps<typeof ExecutionStatusInline>

/**
 * Real prop surface (verified against ExecutionStatusInline.tsx): ALL 9 props
 * required — the brief's rowCount/durationMs props are fictional (row count
 * and duration are derived from dataResult/planResult + mode).
 * Real state matrix (source-verified):
 *   loading=true → "▶ Executing... {elapsed}" (role=button, click → onCancel)
 *   COMPLETED    → "✓ {dur}s" (+ " · {n} rows" in data mode when rows exist)
 *   TIMED_OUT / FAILED / CANCELLED → timeout text / "✗ {error}" / "— {reason}"
 *     (spec §5.3: the backend publishes a cancellation reason on CANCELLED —
 *      the component shows it, falling back to "— Cancelled" when absent)
 *   QUEUED, RUNNING-without-loading, or !status && !loading → renders NULL
 * mode is WorkspaceMode = 'data' | 'performance' (the brief's 'plan' value
 * does not type-check). Timer discipline: 1Hz setInterval while loading;
 * after COMPLETED the fade flag is set ON at mount and CLEARED after 5s
 * (inverted vs. the docstring — pinned as-is, matching Task 11-13 precedent).
 */
function renderStatus(overrides: Partial<StatusProps> = {}) {
  const props: StatusProps = {
    loading: false,
    status: null as ExecutionStatus | null,
    error: null as string | null,
    dataResult: null as ExecutionResult | null,
    planResult: null as ExecutionResult | null,
    startedAt: null as number | null,
    mode: 'data' as WorkspaceMode,
    queryTimeoutSeconds: 30,
    onCancel: vi.fn(),
    ...overrides,
  }
  const utils = renderWithProviders(<ExecutionStatusInline {...props} />)
  return { ...utils, props }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('ExecutionStatusInline', () => {
  it('renders nothing when idle (no status, no loading) — and reacts once loading starts', () => {
    const { container, rerender, props } = renderStatus()
    expect(container).toBeEmptyDOMElement()
    // positive liveness: same tree with loading=true → indicator appears
    rerender(<ExecutionStatusInline {...props} loading status="RUNNING" />)
    expect(screen.getByText('▶ Executing... 0.0s')).toBeInTheDocument()
  })

  it('renders NULL for QUEUED without loading (source-verified matrix)', () => {
    const { container } = renderStatus({ status: 'QUEUED' })
    expect(container).toBeEmptyDOMElement()
  })

  it('renders NULL for RUNNING without loading (the brief text never appears)', () => {
    const { container } = renderStatus({ status: 'RUNNING' })
    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByText(/running/i)).toBeNull()
  })

  it('shows the Executing indicator while loading and cancels on click', async () => {
    const { user, props } = renderStatus({ loading: true, status: 'RUNNING' })
    const el = screen.getByText('▶ Executing... 0.0s')
    expect(el).toHaveAttribute('role', 'button')
    expect(el).toHaveAttribute('aria-label', 'Cancel execution')
    expect(el).toHaveClass('execution-status-inline', 'execution-status-normal')
    await user.click(el)
    expect(props.onCancel).toHaveBeenCalledTimes(1)
    // liveness pair: completed-state glyph is absent while executing
    expect(screen.queryByText(/^✓/)).toBeNull()
  })

  it('ticks elapsed time at 1Hz and flips to the warn class past 30s', () => {
    vi.useFakeTimers()
    const startedAt = Date.now()
    renderStatus({ loading: true, status: 'RUNNING', startedAt })
    expect(screen.getByText('▶ Executing... 0.0s')).toBeInTheDocument()
    // interval fires on whole seconds only: 3 ticks → exactly "3s" (not 2.5s)
    act(() => vi.advanceTimersByTime(3000))
    expect(screen.getByText('▶ Executing... 3s')).toHaveClass('execution-status-normal')
    act(() => vi.advanceTimersByTime(27000)) // total 30s → SLOW_QUERY_THRESHOLD_SECONDS
    expect(screen.getByText('▶ Executing... 30s')).toHaveClass('execution-status-warn')
  })

  it('shows duration + row count on COMPLETED (data mode) with the mount-on / 5s-off fade class', () => {
    vi.useFakeTimers()
    const { container } = renderStatus({ status: 'COMPLETED', dataResult: mockSuccessResult })
    // mockSuccessResult: timing 42ms → "0.0s"; rows.length 3 → " · 3 rows"
    // source-verified: fade class is ON at mount and CLEARED after 5s (exact string pinned)
    expect(screen.getByText('✓ 0.0s · 3 rows returned · Completeness unknown')).toHaveAttribute(
      'class',
      'execution-status-inline execution-status-success execution-status-fade'
    )
    act(() => vi.advanceTimersByTime(5000))
    expect(container).not.toBeEmptyDOMElement()
    expect(screen.getByText('✓ 0.0s · 3 rows returned · Completeness unknown')).toHaveAttribute(
      'class',
      'execution-status-inline execution-status-success'
    )
  })

  it('COMPLETED in performance mode uses planResult and drops the rows suffix', () => {
    // mockPlanResult: timing 150ms → "0.2s"; dataResult present but must be ignored
    renderStatus({
      status: 'COMPLETED',
      mode: 'performance',
      dataResult: mockSuccessResult,
      planResult: mockPlanResult,
    })
    // fmtElapsed(150) = (0.15).toFixed(1) = "0.1" (binary rounding), NOT 0.2
    const el = screen.getByText('✓ Application elapsed: 0.1s')
    expect(el).toBeInTheDocument()
    expect(screen.queryByText(/3 rows/)).toBeNull()
    // non-clickable after completion
    expect(el).not.toHaveAttribute('role')
    expect(el).toHaveAttribute('tabindex', '-1')
  })

  it('shows the timeout message with queryTimeoutSeconds, ignoring error text', () => {
    renderStatus({ status: 'TIMED_OUT', error: 'canceling statement due to statement timeout' })
    expect(screen.getByText('⚠ Timed out after 30s')).toBeInTheDocument()
    expect(screen.queryByText(/canceling statement/)).toBeNull()
  })

  it('shows FAILED with the error text and the error color class', () => {
    renderStatus({ status: 'FAILED', error: 'boom' })
    expect(screen.getByText('✗ boom')).toHaveClass('execution-status-error')
  })

  it('truncates FAILED errors at 60 chars (59 chars + "...")', () => {
    const long = 'x'.repeat(80)
    renderStatus({ status: 'FAILED', error: long })
    expect(screen.getByText(`✗ ${'x'.repeat(59)}...`)).toBeInTheDocument()
    expect(screen.queryByText(`✗ ${long}`)).toBeNull()
  })

  it('shows the cancellation reason instead of a bare dash (spec §5.3)', () => {
    renderStatus({ status: 'CANCELLED', error: 'Cancelled while fetching rows' })
    expect(screen.getByText('— Cancelled while fetching rows')).toBeInTheDocument()
  })

  it('falls back to a plain label when the reason is missing', () => {
    // 404/回收后的本地兜底路径没有原因可显示（useExecution 的 fallback）。
    renderStatus({ status: 'CANCELLED', error: null })
    expect(screen.getByText('— Cancelled')).toBeInTheDocument()
  })
})
