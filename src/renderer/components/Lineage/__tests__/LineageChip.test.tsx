import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import LineageChip from '../LineageChip'
import type { FragmentNode, FragmentType } from '../../../types/fragment'

/**
 * LineageChip props are { chain: FragmentNode[], onOpen: () => void } only
 * (LineageChip.tsx:4-7) — the brief's label/onClick/degraded props are
 * fictional; there is no degraded badge on the chip.
 * Empty chain → non-interactive div title "No lineage yet"; otherwise a
 * <button title="View lineage"> with `Lineage ▸` + step labels joined by →.
 */

function chipNode(
  id: string,
  type: FragmentType,
  opts: { description?: string; label?: string } = {}
): FragmentNode {
  return {
    id,
    type,
    label: opts.label ?? id,
    sqlFragment: `SELECT 1 -- ${id}`,
    executable: true,
    executionHint: null,
    nodeCount: 0,
    ruleName: null,
    children: [],
    ...(opts.description ? { transform: { kind: 'test', description: opts.description } } : {}),
  }
}

function renderChip(chain: FragmentNode[], onOpen = vi.fn()) {
  const user = userEvent.setup()
  render(<LineageChip chain={chain} onOpen={onOpen} />)
  return { user, onOpen }
}

describe('LineageChip', () => {
  it('empty chain renders a non-interactive div, never a button', async () => {
    const { user, onOpen } = renderChip([])
    const empty = screen.getByTitle('No lineage yet')
    expect(empty).toBeInTheDocument()
    expect(screen.getByText('Lineage ▸ (no chain)')).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()

    await user.click(empty) // nothing is wired on the empty state
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('single-step chain renders the View-lineage button and opens on click', async () => {
    const { user, onOpen } = renderChip([chipNode('a', 'ORIGINAL', { description: 'Root query' })])
    const button = screen.getByRole('button', { name: /Lineage/ })
    expect(button).toHaveAttribute('title', 'View lineage')
    expect(screen.getByText('Lineage ▸')).toBeInTheDocument()
    expect(screen.getByText('Root query')).toBeInTheDocument()
    expect(screen.queryByText('→')).toBeNull() // no separators for one step

    await user.click(button)
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('multi-step chain joins labels with → arrows in order', async () => {
    const { user, onOpen } = renderChip([
      chipNode('a', 'ORIGINAL', { description: 'Root' }),
      chipNode('b', 'USER_EDITED', { description: 'Edited' }),
      chipNode('c', 'PREDICATE_DECOMPOSED', { description: 'Isolated' }),
    ])
    expect(screen.getByText('Root')).toBeInTheDocument()
    expect(screen.getByText('Edited')).toBeInTheDocument()
    expect(screen.getByText('Isolated')).toBeInTheDocument()
    expect(screen.getAllByText('→')).toHaveLength(2) // n-1 separators

    await user.click(screen.getByRole('button', { name: /Lineage/ }))
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('falls back to type-name labels when transform.description is absent', () => {
    renderChip([
      chipNode('a', 'ORIGINAL'), // switch case → 'Original'
      chipNode('b', 'USER_EDITED'), // switch case → 'User edit'
      chipNode('c', 'STRIPPED', { label: 'Stripped alias' }), // default → node.label
      chipNode('d', 'COLUMN_PRUNED', { label: 'Column pruned' }), // default → node.label
    ])
    expect(screen.getByText('Original')).toBeInTheDocument()
    expect(screen.getByText('User edit')).toBeInTheDocument()
    expect(screen.getByText('Stripped alias')).toBeInTheDocument()
    expect(screen.getByText('Column pruned')).toBeInTheDocument()
  })

  it('truncates step labels over 30 chars', () => {
    const longDesc = 'L'.repeat(35)
    renderChip([chipNode('a', 'ORIGINAL', { description: longDesc })])
    expect(screen.getByText(`${'L'.repeat(30)}…`)).toBeInTheDocument()
    expect(screen.queryByText(longDesc)).toBeNull()
  })

  it('shows a leading cross-tab stub and jumps to the source tab', async () => {
    const onOpenOrigin = vi.fn()
    render(
      <LineageChip
        chain={[chipNode('a', 'USER_EDITED')]}
        onOpen={vi.fn()}
        origin={{ kind: 'link', text: '← 来自 Tab 1', sessionId: 'sess-A' }}
        onOpenOrigin={onOpenOrigin}
      />
    )
    await userEvent.click(screen.getByRole('button', { name: '← 来自 Tab 1' }))
    expect(onOpenOrigin).toHaveBeenCalledWith('sess-A')
  })

  it('states a closed source tab instead of offering a dead jump', () => {
    render(
      <LineageChip
        chain={[chipNode('a', 'USER_EDITED')]}
        onOpen={vi.fn()}
        origin={{ kind: 'orphan', text: '← 来自已关闭的 Tab', sessionId: null }}
      />
    )
    expect(screen.getByText('← 来自已关闭的 Tab')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '← 来自已关闭的 Tab' })).toBeNull()
  })

  it('renders exactly today\'s chip when there is no origin', () => {
    const { container } = render(<LineageChip chain={[chipNode('a', 'ORIGINAL')]} onOpen={vi.fn()} />)
    expect(container.firstChild).toHaveClass('lineage-chip')
  })
})
