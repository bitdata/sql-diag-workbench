import { describe, it, expect, vi } from 'vitest'
import { fireEvent } from '@testing-library/react'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { mockTabs, mockTab1, mockTab2 } from '../../../test/fixtures'
import TabBar from '../TabBar'

describe('TabBar', () => {
  const defaultProps = {
    tabs: mockTabs,
    activeSessionId: mockTab1.sessionId,
    onSwitch: vi.fn(),
    onClose: vi.fn(),
    onCreate: vi.fn(),
    onContextMenu: vi.fn(),
  }

  it('renders all tab names', () => {
    const { getByText } = renderWithProviders(<TabBar {...defaultProps} />)
    expect(getByText('Query 1')).toBeInTheDocument()
    expect(getByText('Query 2')).toBeInTheDocument()
  })

  it('truncates long tab names', () => {
    const { getByTitle } = renderWithProviders(<TabBar {...defaultProps} />)
    // The third tab has a long name — title attribute holds full name
    const tab = getByTitle('Long Query Name That Should Be Truncated')
    expect(tab).toBeInTheDocument()
    // Visible text should be truncated (28 chars + ellipsis)
    expect(tab.textContent).toContain('…')
  })

  it('marks the active tab with aria-selected', () => {
    const { getAllByRole } = renderWithProviders(<TabBar {...defaultProps} />)
    const tabs = getAllByRole('tab')
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
    expect(tabs[1]).toHaveAttribute('aria-selected', 'false')
  })

  it('shows dirty indicator for unsaved tabs', () => {
    const { getByLabelText } = renderWithProviders(<TabBar {...defaultProps} />)
    // mockTab2 is dirty
    expect(getByLabelText('Unsaved changes')).toBeInTheDocument()
  })

  it('calls onSwitch when a tab is clicked', async () => {
    const onSwitch = vi.fn()
    const { user, getByText } = renderWithProviders(
      <TabBar {...defaultProps} onSwitch={onSwitch} />
    )
    await user.click(getByText('Query 2'))
    expect(onSwitch).toHaveBeenCalledWith(mockTab2.sessionId)
  })

  it('calls onCreate when the + button is clicked', async () => {
    const onCreate = vi.fn()
    const { user, getByLabelText } = renderWithProviders(
      <TabBar {...defaultProps} onCreate={onCreate} />
    )
    await user.click(getByLabelText('New tab'))
    expect(onCreate).toHaveBeenCalledOnce()
  })

  it('calls onClose with sessionId when close button clicked', async () => {
    const onClose = vi.fn()
    const { user, getByLabelText } = renderWithProviders(
      <TabBar {...defaultProps} onClose={onClose} />
    )
    await user.click(getByLabelText(`Close ${mockTab1.name}`))
    expect(onClose).toHaveBeenCalledWith(mockTab1.sessionId)
  })

  it('calls onContextMenu on right-click', async () => {
    const onContextMenu = vi.fn()
    const { getByText } = renderWithProviders(
      <TabBar {...defaultProps} onContextMenu={onContextMenu} />
    )
    // Simulate right-click via fireEvent (user-event doesn't have right-click)
    fireEvent.contextMenu(getByText('Query 1'))
    expect(onContextMenu).toHaveBeenCalledWith(
      mockTab1,
      expect.any(Number),
      expect.any(Number)
    )
  })
})
