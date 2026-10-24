import { describe, it, expect, vi } from 'vitest'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { mockHistoryEntries } from '../../../test/fixtures'
import type { HistoryEntry } from '../../../types/session'
import HistoryDock from '../HistoryDock'

describe('HistoryDock', () => {
  const defaultProps = {
    entries: mockHistoryEntries,
    loading: false,
    onRefresh: vi.fn(),
    onOpenInNewTab: vi.fn(),
  }

  it('renders history entries', () => {
    const { getByText } = renderWithProviders(<HistoryDock {...defaultProps} />)
    expect(getByText(/SELECT \* FROM orders/)).toBeInTheDocument()
    expect(getByText(/SELECT count/)).toBeInTheDocument()
  })

  it('shows duration and row count when available', () => {
    const { getByText } = renderWithProviders(<HistoryDock {...defaultProps} />)
    expect(getByText('42ms')).toBeInTheDocument()
    expect(getByText('15 rows returned · Completeness unknown')).toBeInTheDocument()
  })

  it('hides duration/rows when the keys are absent (non_null strips null columns)', () => {
    // A NULL duration_ms/row_count column is removed from the wire by
    // `spring.jackson.default-property-inclusion: non_null`, so the parsed entry
    // has the key *absent* (undefined), not null. The old `!== null` check
    // rendered a stray "ms"/"rows".
    const entryWithoutMetrics = {
      id: 'hist-x',
      sessionId: 'session-x',
      sqlText: 'SELECT 1',
      executedAt: '2026-07-22T09:00:00Z',
      // durationMs / rowCount deliberately absent
    } as unknown as HistoryEntry
    const { container } = renderWithProviders(
      <HistoryDock entries={[entryWithoutMetrics]} loading={false} onRefresh={vi.fn()} />
    )
    expect(container.querySelector('.history-duration')).toBeNull()
    expect(container.querySelector('.history-rows')).toBeNull()
  })

  it('shows empty state when no entries and not loading', () => {
    const { getByText } = renderWithProviders(
      <HistoryDock {...defaultProps} entries={[]} />
    )
    expect(getByText('No SQL executed yet')).toBeInTheDocument()
  })

  it('shows loading state', () => {
    const { getByText } = renderWithProviders(
      <HistoryDock {...defaultProps} entries={[]} loading={true} />
    )
    expect(getByText('Loading...')).toBeInTheDocument()
  })

  it('calls onRefresh when refresh button clicked', async () => {
    const onRefresh = vi.fn()
    const { user, getByTitle } = renderWithProviders(
      <HistoryDock {...defaultProps} onRefresh={onRefresh} />
    )
    await user.click(getByTitle('Refresh history'))
    expect(onRefresh).toHaveBeenCalledOnce()
  })

  it('calls onOpenInNewTab with SQL text when ↗ button clicked', async () => {
    const onOpenInNewTab = vi.fn()
    const { user, getAllByTitle } = renderWithProviders(
      <HistoryDock {...defaultProps} onOpenInNewTab={onOpenInNewTab} />
    )
    await user.click(document.querySelector('.history-header button')!)
    const openButtons = getAllByTitle('Open SQL offline in a new tab; historical connection was not recorded')
    await user.click(openButtons[0])
    expect(onOpenInNewTab).toHaveBeenCalledWith(mockHistoryEntries[0].sqlText)
  })

  it('does not render open-in-tab buttons when onOpenInNewTab is omitted', () => {
    const { queryAllByTitle } = renderWithProviders(
      <HistoryDock entries={mockHistoryEntries} loading={false} onRefresh={vi.fn()} />
    )
    expect(queryAllByTitle('Open in new tab')).toHaveLength(0)
  })

  it('truncates long SQL text at 60 characters', () => {
    const longSql = 'SELECT ' + 'a'.repeat(100) + ' FROM big_table'
    const { getByTitle } = renderWithProviders(
      <HistoryDock
        entries={[{ ...mockHistoryEntries[0], sqlText: longSql }]}
        loading={false}
        onRefresh={vi.fn()}
      />
    )
    // title attribute has full SQL
    expect(getByTitle(longSql)).toBeInTheDocument()
  })
})
