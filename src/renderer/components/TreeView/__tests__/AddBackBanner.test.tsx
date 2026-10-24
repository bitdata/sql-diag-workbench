import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import AddBackBanner from '../AddBackBanner'

const baseProps = {
  seedLabel: 'a',
  breadcrumb: ['a', 'a JOIN b', 'a JOIN b JOIN c (original SQL)'],
  reachesOrigin: true,
  isOriginRoot: true,
  degraded: false,
  reason: null,
  onExit: vi.fn(),
}

describe('AddBackBanner', () => {
  it('shows the seed and the hop chain that reached the original SQL', () => {
    render(<AddBackBanner {...baseProps} />)
    expect(screen.getByText('Seed: a')).toBeInTheDocument()
    expect(screen.getByText('a → a JOIN b → a JOIN b JOIN c (original SQL)')).toBeInTheDocument()
    expect(screen.getByTitle('Reached the original SQL')).toBeInTheDocument()
  })

  it('exits back to top-down when the button is clicked', async () => {
    const onExit = vi.fn()
    render(<AddBackBanner {...baseProps} onExit={onExit} />)
    await userEvent.click(screen.getByRole('button', { name: 'Back to top-down' }))
    expect(onExit).toHaveBeenCalledTimes(1)
  })

  it('renders the shared degradation copy when the reverse index is degraded (rule #2)', () => {
    render(<AddBackBanner {...baseProps} degraded reason="MULTI_PARENT_NOT_PERSISTED" />)
    expect(screen.getByRole('status')).toHaveTextContent('Only tree parents shown (multiple parents were not persisted)')
  })

  it('marks an unresolved chain without restating the terminal suffix', () => {
    render(
      <AddBackBanner
        {...baseProps}
        reachesOrigin={false}
        isOriginRoot={false}
        breadcrumb={['a', 'a JOIN b (not restored to original SQL)']}
      />
    )
    const path = screen.getByTitle('Did not reach the original SQL')
    expect(path).toHaveTextContent('a → a JOIN b (not restored to original SQL)')
    // 终端后缀只出现一次（由投影产出），横幅不重复书写。
    expect(path.textContent?.match(/not restored to original SQL/g)).toHaveLength(1)
  })
})
