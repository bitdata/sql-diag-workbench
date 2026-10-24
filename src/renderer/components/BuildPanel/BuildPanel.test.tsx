import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import BuildPanel from './BuildPanel'

const baseProps = {
  seedLabel: 'a',
  candidates: [
    { parentId: 'a JOIN b', label: 'a JOIN b', revealed: false },
    { parentId: 'a JOIN c', label: 'a JOIN c', revealed: true },
  ],
  skipped: [] as string[],
  degraded: false,
  reason: null,
  onToggleCandidate: vi.fn(),
  onClose: vi.fn(),
}

describe('BuildPanel', () => {
  it('lists one toggle row per candidate facet, checked when revealed', () => {
    render(<BuildPanel {...baseProps} />)
    expect(screen.getByRole('checkbox', { name: 'a JOIN b' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'a JOIN c' })).toBeChecked()
    expect(screen.getByText(/Seed: a/)).toBeInTheDocument()
  })

  it('reports the parent id when a candidate is toggled', async () => {
    const onToggleCandidate = vi.fn()
    render(<BuildPanel {...baseProps} onToggleCandidate={onToggleCandidate} />)
    await userEvent.click(screen.getByRole('checkbox', { name: 'a JOIN b' }))
    expect(onToggleCandidate).toHaveBeenCalledWith('a JOIN b')
  })

  it('renders unresolvable parents as disabled rows with a reason, never hides them', () => {
    render(<BuildPanel {...baseProps} candidates={[]} skipped={['ghost']} />)
    const row = screen.getByRole('checkbox', { name: /This parent is not in the current view/ })
    expect(row).toBeDisabled()
  })

  it('shows the index degradation copy instead of pretending single-parent', () => {
    render(<BuildPanel {...baseProps} degraded reason="MULTI_PARENT_NOT_PERSISTED" />)
    expect(screen.getByText('Only tree parents shown (multiple parents were not persisted)')).toBeInTheDocument()
  })

  it('says the facet is already the original SQL when nothing is addable and nothing is degraded', () => {
    render(<BuildPanel {...baseProps} candidates={[]} />)
    expect(screen.getByText('This is the original SQL; no parent facets to add back')).toBeInTheDocument()
  })

  // 非模态化（spec §5.1 / D1）去掉了 overlay 的点击关闭，关闭动作仍由本按钮承担
  // —— 反向约束：不能只剩 Esc 或干脆没有出口（票 01）。
  it('closes on the header button', async () => {
    const onClose = vi.fn()
    render(<BuildPanel {...baseProps} onClose={onClose} />)
    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalled()
  })

  it('does not close when the area outside the panel is clicked (non-modal, spec §5.1)', async () => {
    const onClose = vi.fn()
    const { container } = render(<BuildPanel {...baseProps} onClose={onClose} />)
    const overlay = container.querySelector('.build-panel-overlay')
    expect(overlay).not.toBeNull()
    // 旧实现在 overlay 上挂 onClick={onClose}（全视口遮罩的点击关闭）—— 非模态下面板
    // 之外的区域必须把指针事件交给下面真实的控件，所以这个 handler 必须消失。
    await userEvent.click(overlay as HTMLElement)
    expect(onClose).not.toHaveBeenCalled()
  })
})
