import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import LineagePanel from '../LineagePanel'
import type { FragmentNode, FragmentType } from '../../../types/fragment'

/**
 * LineagePanel is a Radix Dialog.Portal with `open` hard-coded
 * (LineagePanel.tsx:99) — always visible while mounted, so it is queried
 * through `screen`. Props are { chain, selectedNodeId, backendUrl, dialect,
 * onClose, onSelectNode } (+ optional allNodes, executionSummaries); the
 * brief's `sessionId` / `lineage={{degraded}}` props do not exist —
 * `degraded` is derived inside useChangeAttribution and cannot be forced
 * from props.
 *
 * backendUrl: null is load-bearing: both internal fetch hooks
 * early-return on it (useChangeAttribution.ts:55-64 → error
 * "Backend not connected"; useDiffGraph.ts:152-155 → edges stay empty),
 * so mounting never hits fetch. The resulting "Diff error: Backend not
 * connected" text in the diff pane is the observable proof of that
 * short-circuit and doubles as the real stand-in for the brief's
 * "degraded/error path" scenario.
 */

type LineagePanelProps = React.ComponentProps<typeof LineagePanel>

function chainNode(
  id: string,
  type: FragmentType,
  sql: string,
  description: string,
  extra: Partial<FragmentNode> = {}
): FragmentNode {
  return {
    id,
    type,
    label: id,
    sqlFragment: sql,
    executable: true,
    executionHint: null,
    nodeCount: 0,
    ruleName: null,
    children: [],
    transform: { kind: 'test', description },
    ...extra,
  }
}

const step1 = chainNode('step-1', 'ORIGINAL', 'SELECT * FROM orders', 'First step')
const step2 = chainNode('step-2', 'PREDICATE_DECOMPOSED', "SELECT * FROM orders WHERE status = 'active'", 'Second step')

// allNodes MUST be passed explicitly with a stable identity: the component
// default `allNodes = []` (LineagePanel.tsx:60) re-creates the array every
// render, which churns useDiffGraph's effect deps into an infinite
// setState/render loop when the prop is omitted (product hazard — see test
// report; App.tsx always passes allNodes, so production never hits it).
const NO_OFF_CHAIN_NODES: FragmentNode[] = []
const DEFAULT_CHAIN: FragmentNode[] = [step1, step2]

function renderPanel(props: Partial<LineagePanelProps> = {}) {
  const user = userEvent.setup()
  const onClose = vi.fn()
  const onSelectNode = vi.fn()
  const result = render(
    <LineagePanel
      chain={DEFAULT_CHAIN}
      allNodes={NO_OFF_CHAIN_NODES}
      selectedNodeId="step-2"
      backendUrl={null}
      dialect="postgresql"
      onClose={onClose}
      onSelectNode={onSelectNode}
      {...props}
    />
  )
  return { user, onClose, onSelectNode, ...result }
}

describe('LineagePanel', () => {
  it('discloses an incomplete source path without presenting it as a true root', () => {
    renderPanel({ chain: [step1], selectedNodeId: step1.id, pathNotice: 'Source path is incomplete: an ancestor is unavailable in this tab.' })
    expect(screen.getByRole('status')).toHaveTextContent('ancestor is unavailable')
    expect(screen.getByText(/Start of available path/)).toBeInTheDocument()
    expect(screen.queryByText(/Root node/)).not.toBeInTheDocument()
  })

  it('mounts as an always-open portal dialog showing the chain and the selected step', async () => {
    renderPanel()
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toBeInTheDocument()
    // Dialog.Title is derived from the active node's transform.description
    expect(screen.getByText('Lineage · Second step')).toBeInTheDocument()

    const chain = screen.getByLabelText('Operation chain')
    expect(chain).toBeInTheDocument()
    // idx 0 gets the ◉ origin marker; labels come from transform.description
    expect(screen.getByText(/◉ First step/)).toBeInTheDocument()
    expect(screen.getByText('Second step')).toBeInTheDocument()
    // selectedNodeId marks the current step ●, ancestors ○ (in chain order)
    const markers = dialog.querySelectorAll('.lineage-panel-step-marker')
    expect(markers).toHaveLength(2)
    expect(markers[0].textContent).toBe('○')
    expect(markers[1].textContent).toBe('●')
  })

  it('with backendUrl null, the diff pane shows the hooks\' "Backend not connected" error, not a fetch', async () => {
    // step-2 is active and has a parent (step-1) → DiffViewer renders.
    // useChangeAttribution early-returns with error 'Backend not connected'
    // (no fetch fired); DiffViewer surfaces it as "Diff error: …".
    renderPanel()
    expect(await screen.findByText('Diff error: Backend not connected')).toBeInTheDocument()
    expect(screen.queryByText('Computing diff…')).toBeNull() // never entered loading
  })

  it('empty chain renders both empty texts and a "No selection" title', () => {
    renderPanel({ chain: [], selectedNodeId: null })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('No chain steps')).toBeInTheDocument()
    expect(screen.getByText('Select a chain step to see diff')).toBeInTheDocument()
    expect(screen.getByText('Lineage · No selection')).toBeInTheDocument()
  })

  it('selecting the root step shows the root-node hint instead of a diff', () => {
    renderPanel({ selectedNodeId: 'step-1' })
    expect(screen.getByText('Lineage · First step')).toBeInTheDocument()
    expect(screen.getByText('Root node — select a child step to see the diff')).toBeInTheDocument()
    expect(screen.queryByText(/Diff error/)).toBeNull()
  })

  it('clicking a chain step switches the active node without touching onSelectNode', async () => {
    const { user, onSelectNode, onClose } = renderPanel()
    expect(screen.getByText('Lineage · Second step')).toBeInTheDocument()

    await user.click(screen.getByText(/◉ First step/))
    // active node → step-1: title and diff pane follow, callback untouched
    expect(screen.getByText('Lineage · First step')).toBeInTheDocument()
    expect(screen.getByText('Root node — select a child step to see the diff')).toBeInTheDocument()
    expect(onSelectNode).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('"jump to tree →" calls onSelectNode(id) + onClose and does NOT change the active step (stopPropagation)', async () => {
    const { user, onSelectNode, onClose } = renderPanel()
    const jumps = screen.getAllByText('jump to tree →')
    expect(jumps).toHaveLength(2)

    await user.click(jumps[1]) // the step-2 row
    expect(onSelectNode).toHaveBeenCalledTimes(1)
    expect(onSelectNode).toHaveBeenCalledWith('step-2')
    expect(onClose).toHaveBeenCalledTimes(1)
    // jump div stops propagation (LineagePanel.tsx:148) — active step unchanged
    expect(screen.getByText('Lineage · Second step')).toBeInTheDocument()
  })

  it('step meta: own executionSummary wins over the fallback map, fallback fills nodes without one', () => {
    renderPanel({
      chain: [
        chainNode('step-1', 'ORIGINAL', 'SELECT 1', 'First step'),
        chainNode('step-2', 'USER_EDITED', 'SELECT 1 -- edited', 'Second step', {
          executionSummary: { rows: 5, durationMs: 99 },
          createdAt: '2026-01-02T03:04:05Z',
        }),
      ],
      executionSummaries: {
        'step-1': { rows: 120, durationMs: 345 },
        'step-2': { rows: 9999, durationMs: 9999 }, // must be ignored (?? precedence)
      },
    })
    const dialog = screen.getByRole('dialog')
    const metas = dialog.querySelectorAll('.lineage-panel-step-meta')
    expect(metas[0].textContent).toBe('120 rows returned · 345ms · Completeness unknown') // fallback, no createdAt
    expect(metas[1].textContent).toBe('5 rows returned · 99ms · Completeness unknown · 2026-01-02T03:04:05Z')
  })

  it('chain step meta shows Identity degraded with the reason, and stays silent when clean', async () => {
    const cleanStep = chainNode('step-1', 'ORIGINAL', 'SELECT * FROM orders', 'First step')
    const degradedStep = chainNode(
      'step-2',
      'PREDICATE_DECOMPOSED',
      "SELECT * FROM orders WHERE status = 'active'",
      'Second step',
      {
        createdAt: '2026-10-06T00:00:00Z',
        canonicalizationError: 'unsupported node types: SqlExplain',
      },
    )
    renderPanel({ chain: [cleanStep, degradedStep] })

    // The degraded step states the reason; the clean step's meta must not.
    expect(screen.getByText(/Identity degraded — unsupported node types: SqlExplain/)).toBeInTheDocument()
    const metas = document.querySelectorAll('.lineage-panel-step-meta')
    expect(metas).toHaveLength(2)
    expect(metas[0].textContent).not.toMatch(/Identity degraded/)
    expect(metas[1].textContent).toMatch(/Identity degraded — unsupported node types: SqlExplain/)
    expect(metas[1].textContent).toContain('2026-10-06T00:00:00Z')
  })

  it('chain step meta carries no degraded text when the marker key is absent (legacy JSON)', () => {
    renderPanel() // DEFAULT_CHAIN is clean — no canonicalizationError key at all
    expect(screen.queryByText(/Identity degraded/)).toBeNull()
  })

  it('chain step meta shows the bare label for a blank marker, with no empty tail (spec §6)', () => {
    const blankStep = chainNode('step-1', 'ORIGINAL', 'SELECT 1', 'First step', {
      canonicalizationError: '',
    })
    renderPanel({ chain: [blankStep], selectedNodeId: 'step-1' })
    expect(screen.getByText(/^Identity degraded$/)).toBeInTheDocument()
  })

  it('chain step meta shows the bare label for a whitespace-only marker, with no whitespace tail (spec §6)', () => {
    const whitespaceStep = chainNode('step-1', 'ORIGINAL', 'SELECT 1', 'First step', {
      canonicalizationError: '   ',
    })
    renderPanel({ chain: [whitespaceStep], selectedNodeId: 'step-1' })
    expect(screen.getByText(/^Identity degraded$/)).toBeInTheDocument()
    const metas = document.querySelectorAll('.lineage-panel-step-meta')
    expect(metas).toHaveLength(1)
    // Sensitivity pin: an implementation reading the raw `canonicalizationError`
    // instead of `getDegradedReason` passes truthiness on '   ' and emits the
    // tail 'Identity degraded —    '. Assert the tail is absent specifically —
    // the blank-marker case above stays green under that regression, this one
    // catches it.
    expect(metas[0].textContent).not.toMatch(/Identity degraded —/)
  })

  it('diff graph toggle: collapsed by default, opens the Diff graph region with empty edges, closes again', async () => {
    const { user } = renderPanel()
    const toggle = screen.getByRole('button', { name: 'Show diff graph' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: 'Diff graph' })).toBeNull()

    await user.click(toggle)
    expect(screen.getByRole('button', { name: 'Hide diff graph' })).toHaveAttribute(
      'aria-expanded',
      'true'
    )
    const region = screen.getByRole('region', { name: 'Diff graph' })
    // backendUrl null → useDiffGraph returns edges: [] → empty-state text, no svg
    expect(region).toHaveTextContent('No virtual edges (current node has no dead-end siblings)')
    expect(region.querySelector('svg')).toBeNull() // empty edges → no graph drawn

    await user.click(screen.getByRole('button', { name: 'Hide diff graph' }))
    expect(screen.getByRole('button', { name: 'Show diff graph' })).toHaveAttribute(
      'aria-expanded',
      'false'
    )
    expect(screen.queryByRole('region', { name: 'Diff graph' })).toBeNull()
  })

  it('Close (×) button fires onClose', async () => {
    const { user, onClose } = renderPanel()
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
