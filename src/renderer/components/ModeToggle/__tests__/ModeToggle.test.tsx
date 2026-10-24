import { describe, it, expect, vi } from 'vitest'
import { renderWithProviders } from '../../../test/renderWithProviders'
import ModeToggle from '../ModeToggle'

describe('ModeToggle', () => {
  it('renders Data and Performance segments', () => {
    const { getByRole } = renderWithProviders(
      <ModeToggle value="data" onChange={vi.fn()} />
    )
    const group = getByRole('radiogroup')
    expect(group).toBeInTheDocument()
    expect(getByRole('radio', { name: /data/i })).toBeInTheDocument()
    expect(getByRole('radio', { name: /performance/i })).toBeInTheDocument()
  })

  it('marks the active mode with aria-checked', () => {
    const { getByRole } = renderWithProviders(
      <ModeToggle value="data" onChange={vi.fn()} />
    )
    expect(getByRole('radio', { name: /data/i })).toHaveAttribute('aria-checked', 'true')
    expect(getByRole('radio', { name: /performance/i })).toHaveAttribute('aria-checked', 'false')
  })

  it('calls onChange with "performance" when Performance clicked', async () => {
    const onChange = vi.fn()
    const { user, getByRole } = renderWithProviders(
      <ModeToggle value="data" onChange={onChange} />
    )
    await user.click(getByRole('radio', { name: /performance/i }))
    expect(onChange).toHaveBeenCalledWith('performance')
  })

  it('calls onChange with "data" when Data clicked from performance mode', async () => {
    const onChange = vi.fn()
    const { user, getByRole } = renderWithProviders(
      <ModeToggle value="performance" onChange={onChange} />
    )
    await user.click(getByRole('radio', { name: /data/i }))
    expect(onChange).toHaveBeenCalledWith('data')
  })
})
