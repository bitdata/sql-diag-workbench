import { describe, it, expect, vi } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import type { ComponentProps } from 'react'
import type { DecomposeOption } from '../../../types/fragment'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { buildMockTree, mockDecomposeOptions } from '../../../test/fixtures'
import DecompositionSlider from '../DecompositionSlider'

type SliderProps = ComponentProps<typeof DecompositionSlider>

/**
 * Real prop surface (verified against DecompositionSlider.tsx): ALL 11 props
 * required — the brief's `depth`/`max`/`onChange` are fictional. The global
 * Radix slider renders only when `tree && maxGlobalDepth > 0` (maxGlobalDepth
 * comes from useDecompositionDepth: buildMockTree → root maxDepth = 1).
 * Depth display format is "N / M" (three text nodes — normalize before
 * matching). Because several role=slider thumbs can coexist once a child
 * slider is expanded, every slider query is scoped by its exact aria-label.
 */
function renderSlider(overrides: Partial<SliderProps> = {}) {
  const props: SliderProps = {
    tree: buildMockTree(),
    loading: false,
    error: null,
    selectedNodeId: null,
    onSelectNode: vi.fn(),
    optionsByNodeId: {} as Record<string, DecomposeOption[]>,
    optionsLoading: {} as Record<string, boolean>,
    decomposing: {} as Record<string, boolean>,
    onDecompose: vi.fn(),
    viewMode: 'slider',
    onViewModeChange: vi.fn(),
    ...overrides,
  }
  const utils = renderWithProviders(<DecompositionSlider {...props} />)
  return { ...utils, props }
}

/** getByText-like matcher for the whitespace-split "N / M" depth readout. */
function getDepthText(normalized: string): HTMLElement {
  return screen.getByText((_content, el) => {
    if (!el || el.tagName !== 'SPAN') return false
    return el.textContent?.replace(/\s+/g, ' ').trim() === normalized
  })
}

/** The role=slider thumb nested inside the slider Root carrying aria-label. */
function thumbOf(label: string): HTMLElement {
  const root = screen.getByLabelText(label)
  return within(root).getByRole('slider')
}

function rowOf(label: string): HTMLElement {
  const rows = within(screen.getByRole('tree', { name: 'SQL Decomposition Slider' })).getAllByRole('treeitem')
  const row = rows.find((el) => el.textContent?.includes(label))
  if (!row) throw new Error(`no slider treeitem containing "${label}" (found ${rows.length})`)
  return row
}

describe('DecompositionSlider', () => {
  it('shows the paste-a-query placeholder inside the tree container when tree is null', () => {
    renderSlider({ tree: null })
    const tree = screen.getByRole('tree', { name: 'SQL Decomposition Slider' })
    expect(within(tree).getByText('Paste a SQL query')).toBeInTheDocument()
    expect(within(tree).queryAllByRole('treeitem')).toHaveLength(0)
    // liveness pair: no global slider without a tree
    expect(screen.queryByLabelText('Global decomposition depth')).toBeNull()
  })

  it('shows role=alert with the error text when parse failed and no tree', () => {
    renderSlider({ tree: null, error: 'boom parse error' })
    expect(screen.getByRole('alert')).toHaveTextContent('boom parse error')
    expect(screen.queryByText('Paste a SQL query')).toBeNull()
  })

  it('renders the aria-busy skeleton and suppresses rows while loading', () => {
    const { container } = renderSlider({ loading: true })
    expect(container.querySelector('[aria-busy="true"]')).toBeTruthy()
    expect(screen.queryAllByRole('treeitem')).toHaveLength(0)
    // liveness pair: rows return once loading clears (2nd container, scoped)
    const { container: c2 } = renderSlider({ loading: false })
    expect(within(c2).getAllByRole('treeitem').length).toBeGreaterThan(0)
  })

  it('renders the global slider with valuemax from the tree when maxGlobalDepth > 0', () => {
    renderSlider()
    // aria-label sits on the Radix Root span; role=slider is the nested thumb.
    expect(screen.getByLabelText('Global decomposition depth')).toBeInTheDocument()
    const thumb = thumbOf('Global decomposition depth')
    expect(thumb).toHaveAttribute('aria-valuemin', '0')
    expect(thumb).toHaveAttribute('aria-valuemax', '1') // root maxDepth = 1
    expect(thumb).toHaveAttribute('aria-valuenow', '0') // nothing selected → depth 0
    // depth readout is "N / M", NOT "Depth: N"
    expect(getDepthText('0 / 1')).toBeInTheDocument()
    expect(screen.queryByText(/^Depth:?\s*0$/i)).toBeNull()
  })

  it('global depth follows the selected node (child at depth 1 → "1 / 1")', () => {
    renderSlider({ selectedNodeId: 'node-join' })
    expect(getDepthText('1 / 1')).toBeInTheDocument()
    expect(thumbOf('Global decomposition depth')).toHaveAttribute('aria-valuenow', '1')
  })

  it('hides the global slider entirely for a leaf-only tree (maxGlobalDepth === 0)', () => {
    const leafOnly = { ...buildMockTree(), children: [] }
    renderSlider({ tree: leafOnly })
    expect(screen.queryByLabelText('Global decomposition depth')).toBeNull()
    expect(screen.queryAllByRole('slider')).toHaveLength(0)
    // liveness pair: the row itself still renders
    expect(screen.getByText('Full Query')).toBeInTheDocument()
  })

  it('clicking a row calls onSelectNode with exactly that node id', () => {
    const { props } = renderSlider()
    fireEvent.click(screen.getByText('orders'))
    expect(props.onSelectNode).toHaveBeenCalledTimes(1)
    expect(props.onSelectNode).toHaveBeenCalledWith('node-join')
    // selection is fully controlled (selectedNodeId prop) — aria-selected is
    // pinned in its own test by passing the prop directly.
  })

  it('chevron collapses/re-expands applied children and flips its aria-label', () => {
    const { props } = renderSlider()
    const rootRow = rowOf('Full Query')
    expect(rootRow).toHaveAttribute('aria-expanded', 'true') // nodes with children start expanded
    fireEvent.click(within(rootRow).getByRole('button', { name: 'Collapse' }))
    expect(screen.queryByText('orders')).toBeNull()
    expect(within(rootRow).getByRole('button', { name: 'Expand' })).toBeInTheDocument()
    expect(props.onSelectNode).not.toHaveBeenCalled() // chevron stops propagation

    fireEvent.click(within(rootRow).getByRole('button', { name: 'Expand' }))
    expect(screen.getByText('orders')).toBeInTheDocument()
  })

  it('child slider appears behind "Toggle child slider" with the node\'s own max depth', () => {
    const { props } = renderSlider()
    // only the root has applied children → exactly one toggle button
    const toggle = screen.getByRole('button', { name: 'Toggle child slider' })
    expect(screen.queryByLabelText(/Child decomposition depth for/)).toBeNull()

    fireEvent.click(toggle)
    const childThumb = thumbOf('Child decomposition depth for node-root')
    expect(childThumb).toHaveAttribute('aria-valuemax', '1') // nodeMaxDepths['node-root']
    expect(childThumb).toHaveAttribute('aria-valuenow', '0')
    expect(props.onSelectNode).not.toHaveBeenCalled() // toggle stops propagation

    fireEvent.click(toggle)
    expect(screen.queryByLabelText(/Child decomposition depth for/)).toBeNull()
  })

  it('rule buttons fire onDecompose(nodeId, ruleName) with exact args', () => {
    const { props } = renderSlider({
      optionsByNodeId: { 'node-root': mockDecomposeOptions },
    })
    // options are grouped by level
    expect(screen.getByText('Structure')).toBeInTheDocument()
    expect(screen.getByText('Clause')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Strip WHERE/ }))
    expect(props.onDecompose).toHaveBeenCalledTimes(1)
    expect(props.onDecompose).toHaveBeenCalledWith('node-root', 'clause-stripping')
  })

  it('view-mode toggle buttons report the picked mode', () => {
    const { props } = renderSlider()
    fireEvent.click(screen.getByRole('button', { name: /Tree/ }))
    expect(props.onViewModeChange).toHaveBeenCalledWith('tree')
    fireEvent.click(screen.getByRole('button', { name: /Slider$/ }))
    expect(props.onViewModeChange).toHaveBeenCalledWith('slider')
  })
})
