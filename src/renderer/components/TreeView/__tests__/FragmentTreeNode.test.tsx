import { describe, it, expect, vi } from 'vitest'
import { fireEvent } from '@testing-library/react'
import * as Tooltip from '@radix-ui/react-tooltip'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { buildMockTree, mockDecomposeOptions } from '../../../test/fixtures'
import { isDegraded, getDegradedReason, truncateDegradedReason } from '../../../types/fragment'
import FragmentTreeNode from '../FragmentTreeNode'

const defaultNodeProps = {
  depth: 0,
  isExpanded: false,
  hasChildren: true,
  onToggleExpand: vi.fn(),
  selectedNodeId: null,
  onSelectNode: vi.fn(),
  optionsByNodeId: {} as Record<string, typeof mockDecomposeOptions>,
  optionsLoading: {} as Record<string, boolean>,
  decomposing: {} as Record<string, boolean>,
  onDecompose: vi.fn(),
}

/** FragmentTreeNode renders NodeTooltip (Radix Tooltip.Root) which requires
 *  a Tooltip.Provider ancestor. FragmentTree normally provides it; in unit
 *  tests we wrap manually. */
function renderNode(ui: React.ReactElement) {
  return renderWithProviders(
    <Tooltip.Provider>{ui}</Tooltip.Provider>
  )
}

describe('FragmentTreeNode', () => {
  it('renders the node label', () => {
    const node = buildMockTree()
    const { getByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} />
    )
    expect(getByText('Full Query')).toBeInTheDocument()
  })

  it('renders the fragment icon', () => {
    const node = buildMockTree()
    const { getByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} />
    )
    // ORIGINAL type → ◉ icon
    expect(getByText('◉')).toBeInTheDocument()
  })

  it('calls onSelectNode when clicked', async () => {
    const node = buildMockTree()
    const onSelectNode = vi.fn()
    const { user, getByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} onSelectNode={onSelectNode} />
    )
    await user.click(getByText('Full Query'))
    expect(onSelectNode).toHaveBeenCalledWith(node.id)
  })

  it('applies selected class when node is selected', () => {
    const node = buildMockTree()
    const { getByRole } = renderNode(
      <FragmentTreeNode
        node={node}
        {...defaultNodeProps}
        selectedNodeId={node.id}
      />
    )
    expect(getByRole('treeitem')).toHaveAttribute('aria-selected', 'true')
  })

  it('shows expand chevron when hasChildren is true', () => {
    const node = buildMockTree()
    const { getByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} hasChildren={true} />
    )
    expect(getByText('▾')).toBeInTheDocument()
  })

  it('calls onToggleExpand when chevron clicked', async () => {
    const node = buildMockTree()
    const onToggleExpand = vi.fn()
    const { user, getByText } = renderNode(
      <FragmentTreeNode
        node={node}
        {...defaultNodeProps}
        hasChildren={true}
        onToggleExpand={onToggleExpand}
      />
    )
    await user.click(getByText('▾'))
    expect(onToggleExpand).toHaveBeenCalledOnce()
  })

  describe('Context menu', () => {
    // Context menu buttons contain icon spans (e.g. <span>✎</span> Edit this fragment),
    // so getByText exact match won't work. Use getByRole('button', { name: /regex/ })
    // which matches the accessible name (full text content) via substring regex.

    it('calls onDecompose when a decompose option is clicked', () => {
      const node = buildMockTree()
      const optionsMap = { [node.id]: mockDecomposeOptions }
      const onDecompose = vi.fn()
      const { getByText, getByRole } = renderNode(
        <FragmentTreeNode
          node={node}
          {...defaultNodeProps}
          optionsByNodeId={optionsMap}
          onDecompose={onDecompose}
          onEditNode={vi.fn()}
        />
      )
      fireEvent.contextMenu(getByText('Full Query'))
      fireEvent.click(getByRole('menuitem', { name: /Strip JOIN/ }))

      expect(onDecompose).toHaveBeenCalledWith(node.id, 'join-split')
    })
  })

  it('offers Build upward from here on the facet it right-clicked', () => {
    const onBuildUpward = vi.fn()
    const node = buildMockTree()
    const { getByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} onBuildUpward={onBuildUpward} />
    )
    fireEvent.contextMenu(getByText(node.label))
    fireEvent.click(getByText('Build upward from here'))
    expect(onBuildUpward).toHaveBeenCalledWith(node.id)
  })

  it('omits the add-back item when the parent passes no handler', () => {
    const node = buildMockTree()
    const { getByText, queryByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} />
    )
    fireEvent.contextMenu(getByText(node.label))
    expect(queryByText('Build upward from here')).toBeNull()
  })

  it('labels the display root Original SQL when it is the DAG root', () => {
    const node = buildMockTree()
    const { getByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} originMark="origin" />
    )
    expect(getByText('Original SQL')).toHaveClass('tree-node-origin-mark')
  })

  it('labels the display root Not restored to original SQL when the DAG root is out of reach', () => {
    const node = buildMockTree()
    const { getByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} originMark="not-restored" />
    )
    expect(getByText('Not restored to original SQL')).toHaveClass('tree-node-origin-mark')
  })

  it('renders no origin label when the prop is omitted or null', () => {
    const node = buildMockTree()
    const omitted = renderNode(<FragmentTreeNode node={node} {...defaultNodeProps} />)
    expect(omitted.queryByText('Original SQL')).toBeNull()
    const explicitNull = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} originMark={null} />
    )
    expect(explicitNull.queryByText('Not restored to original SQL')).toBeNull()
  })

  it('renders the edit mark on the source facet and jumps to the target tab', () => {
    const node = buildMockTree()
    const onJumpToEdit = vi.fn()
    const marks = new Map([
      [node.id, { kind: 'link' as const, text: 'Edited → e2e edited', targetSessionId: 'sess-B' }],
    ])
    const { getByText, getByRole } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} editMarks={marks} onJumpToEdit={onJumpToEdit} />
    )
    expect(getByText('Edited → e2e edited')).toBeInTheDocument()
    fireEvent.click(getByRole('button', { name: 'Edited → e2e edited' }))
    expect(onJumpToEdit).toHaveBeenCalledWith('sess-B')
  })

  it('renders a non-clickable mark when the target tab is gone', () => {
    const node = buildMockTree()
    const marks = new Map([
      [node.id, { kind: 'orphan' as const, text: 'Edited (target closed)', targetSessionId: null }],
    ])
    const { getByText, queryByRole } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} editMarks={marks} />
    )
    expect(getByText('Edited (target closed)')).toBeInTheDocument()
    expect(queryByRole('button', { name: 'Edited (target closed)' })).toBeNull()
  })

  it('shows no mark for a facet that was never edited', () => {
    const node = buildMockTree()
    const { queryByText, container } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} editMarks={new Map()} />
    )
    // Both halves are needed, and neither is a bug-vs-fix discriminator (pre-change the
    // prop is ignored, so nothing renders either way). They pin two DIFFERENT regressions:
    // queryByText is an exact, whitespace-normalized FULL-STRING match, so `('已编辑')`
    // catches only a blanket `legacy` mark whose text is exactly that — a blanket `link`
    // mark (text `已编辑 → X`) slips past it. The class query below catches both kinds.
    expect(queryByText('Edited')).toBeNull()
    expect(container.querySelector('.tree-node-edit-mark')).toBeNull()
  })
})

describe('FragmentTreeNode — degraded-identity mark (ADR-0001)', () => {
  it('renders Identity degraded mark when canonicalizationError is set, with the reason in title', () => {
    const node = { ...buildMockTree(), canonicalizationError: 'unsupported node types: SqlExplain' }
    const { getByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} />
    )
    const mark = getByText('Identity degraded')
    expect(mark).toHaveAttribute('title', 'unsupported node types: SqlExplain')
  })

  it('omits the mark when canonicalizationError is null (clean identity)', () => {
    const node = { ...buildMockTree(), canonicalizationError: null }
    const { queryByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} />
    )
    expect(queryByText('Identity degraded')).toBeNull()
  })

  it('omits the mark and does not throw when the marker key is absent (legacy JSON)', () => {
    const node = buildMockTree() // fixture has no canonicalizationError key at all
    const { queryByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} />
    )
    expect(queryByText('Identity degraded')).toBeNull()
  })

  it('still renders the mark for a blank marker, without an empty title (spec §6)', () => {
    const node = { ...buildMockTree(), canonicalizationError: '' }
    const { getByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} />
    )
    const mark = getByText('Identity degraded')
    // Label carries the semantics; no empty tooltip attribute.
    expect(mark).not.toHaveAttribute('title')
  })

  it('still renders the mark for a whitespace-only marker, with no title (plan: 空白 ⇒ 不出 reason 尾巴)', () => {
    const node = { ...buildMockTree(), canonicalizationError: '   ' }
    const { getByText } = renderNode(
      <FragmentTreeNode node={node} {...defaultNodeProps} />
    )
    const mark = getByText('Identity degraded')
    // `reason ? …` truthiness lets '   ' through as "text" → a tooltip box that
    // looks empty. Presence still marks it degraded; only the tail is suppressed.
    expect(mark).not.toHaveAttribute('title')
  })
})

describe('isDegraded / getDegradedReason / truncateDegradedReason (types/fragment)', () => {
  it('truncates a long reason to 120 chars plus ellipsis', () => {
    const long = 'x'.repeat(200)
    const out = truncateDegradedReason(long)
    expect(out).toBe('x'.repeat(120) + '…')
  })

  it('leaves a short reason untouched', () => {
    expect(truncateDegradedReason('parse failed: boom')).toBe('parse failed: boom')
  })

  it('isDegraded is a presence check: blank marker is degraded, absent/null is not', () => {
    expect(isDegraded({ ...buildMockTree() })).toBe(false)              // key absent
    expect(isDegraded({ ...buildMockTree(), canonicalizationError: null })).toBe(false)
    expect(isDegraded({ ...buildMockTree(), canonicalizationError: '' })).toBe(true)   // blank ≠ clean
    expect(isDegraded({ ...buildMockTree(), canonicalizationError: 'boom' })).toBe(true)
  })

  it('getDegradedReason is the text half: null when there is no text to show', () => {
    expect(getDegradedReason({ ...buildMockTree() })).toBeNull()
    expect(getDegradedReason({ ...buildMockTree(), canonicalizationError: null })).toBeNull()
    expect(getDegradedReason({ ...buildMockTree(), canonicalizationError: '' })).toBeNull()
  })

  it('getDegradedReason returns the truncated reason for a degraded node', () => {
    const node = { ...buildMockTree(), canonicalizationError: 'y'.repeat(150) }
    expect(getDegradedReason(node)).toBe('y'.repeat(120) + '…')
  })
})
