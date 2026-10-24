import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import type { ComponentProps } from 'react'
import type { DecomposeOption, FragmentNode } from '../../../types/fragment'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { buildMockTree, mockDecomposeOptions } from '../../../test/fixtures'
import { createDAGState, indexTreeIntoDAG } from '../../../hooks/dagState'
import { buildParentIndex } from '../../../utils/dagReverseIndex'
import { buildAddBackProjection, createAddBackState, revealParent } from '../../../utils/addBackProjection'
import FragmentTree from '../FragmentTree'

type FragmentTreeProps = ComponentProps<typeof FragmentTree>

/**
 * Real prop surface (verified against FragmentTree.tsx): 11 required props —
 * the plan brief's `nodes` / `selectedId` / `onSelect` do not exist. The
 * fixture tree is root 'node-root' + children 'node-join' / 'node-where'
 * (3 rows; rows render through TanStack useVirtualizer, which measures the
 * scroll container via offsetHeight — see stub below).
 */
function renderTree(overrides: Partial<FragmentTreeProps> = {}) {
  const props: FragmentTreeProps = {
    tree: buildMockTree(),
    loading: false,
    error: null,
    selectedNodeId: null,
    onSelectNode: vi.fn(),
    optionsByNodeId: {} as Record<string, DecomposeOption[]>,
    optionsLoading: {} as Record<string, boolean>,
    decomposing: {} as Record<string, boolean>,
    onDecompose: vi.fn(),
    ...overrides,
  }
  const utils = renderWithProviders(<FragmentTree {...props} />)
  return { ...utils, props }
}

/** The role=treeitem row that owns the given label text. */
function rowOf(label: string): HTMLElement {
  const rows = screen.getAllByRole('treeitem')
  const row = rows.find((el) => el.textContent?.includes(label))
  if (!row) throw new Error(`no treeitem row containing "${label}" (found ${rows.length} rows)`)
  return row
}

// TanStack virtual-core measures the scroll element via offsetHeight at
// subscribe time; jsdom reports 0 for everything, calculateRange() then
// returns null and NO rows mount (verified failing in this environment —
// a regression that drops the stub would silently render an empty
// `<div role="tree">` and break every row-scoped assertion below).
// A non-zero fake viewport height makes the overscan window render so
// the fixture tree's 3 rows become real treeitems.
//
// GUARDRAIL: if the fixture tree grows past the visible window (default
// ~5 overscan rows visible), the row count assertions below will start
// to fail — the stub height of 400px is tuned for the current 3-row
// fixture. Either keep the tree small or also stub rowHeight/estimateSize
// before bumping the fixture.
const offsetHeightDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight')
beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
    configurable: true,
    get: () => 400,
  })
})
afterAll(() => {
  if (offsetHeightDesc) {
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', offsetHeightDesc)
  }
})

describe('FragmentTree', () => {
  it('shows the paste-a-query placeholder when there is no tree', () => {
    renderTree({ tree: null })
    expect(screen.getByText('Paste a SQL query')).toBeInTheDocument()
    expect(screen.getByText('and press Ctrl+Shift+Enter to explore')).toBeInTheDocument()
    // liveness pair: the real tree surface is absent in this state
    expect(screen.queryByRole('tree', { name: 'SQL Fragment Tree' })).toBeNull()
  })

  it('renders an aria-busy skeleton while loading, hiding tree/error/empty states', () => {
    const { container } = renderTree({ loading: true, tree: null, error: 'unused while loading' })
    expect(container.querySelector('.tree-loading[aria-busy="true"]')).toBeTruthy()
    expect(container.querySelectorAll('.tree-skeleton-node')).toHaveLength(3)
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByText('Paste a SQL query')).toBeNull()
    expect(screen.queryByRole('tree', { name: 'SQL Fragment Tree' })).toBeNull()
  })

  it('shows role=alert with the error text and a Retry button when parsing failed', () => {
    renderTree({ tree: null, error: 'calcite parse failed at token SELECT' })
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('calcite parse failed at token SELECT')
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    // liveness pair: empty placeholder must NOT also show
    expect(screen.queryByText('Paste a SQL query')).toBeNull()
  })

  it('renders root + children rows (depth < 2 auto-expanded) in a role=tree', () => {
    renderTree()
    const tree = screen.getByRole('tree', { name: 'SQL Fragment Tree' })
    // root (depth 0) and children (depth 1) are all < depth 2 → auto-expanded,
    // so all 3 flattened rows mount even through the virtualizer.
    expect(within(tree).getAllByRole('treeitem')).toHaveLength(3)
    expect(screen.getByText('Full Query')).toBeInTheDocument()
    expect(screen.getByText('orders')).toBeInTheDocument()
    expect(screen.getByText('WHERE clause')).toBeInTheDocument()
  })

  it('calls onSelectNode with exactly the clicked node id', () => {
    const { props } = renderTree()
    // TanStack-virtualized rows are unreliable targets for userEvent in jsdom
    // (zero-height scroll rect) — fireEvent.click is the verified idiom.
    fireEvent.click(screen.getByText('WHERE clause'))
    expect(props.onSelectNode).toHaveBeenCalledTimes(1)
    expect(props.onSelectNode).toHaveBeenCalledWith('node-where')
    // NOTE: selection is fully controlled (selectedNodeId prop) — aria-selected
    // changes only via the parent state loop, which the next test pins directly.
  })

  it('marks the selectedNodeId row with aria-selected=true', () => {
    renderTree({ selectedNodeId: 'node-join' })
    expect(rowOf('orders')).toHaveAttribute('aria-selected', 'true')
    expect(rowOf('Full Query')).toHaveAttribute('aria-selected', 'false')
  })

  it('collapses and re-expands the root via its chevron without selecting it', () => {
    const { props } = renderTree()
    // only the root has children → exactly one '▾' chevron glyph
    fireEvent.click(screen.getByText('▾'))
    expect(screen.queryByText('orders')).toBeNull()
    expect(screen.queryByText('WHERE clause')).toBeNull()
    expect(screen.getByText('Full Query')).toBeInTheDocument() // root row stays mounted
    expect(props.onSelectNode).not.toHaveBeenCalled() // chevron stops propagation

    fireEvent.click(screen.getByText('▾'))
    expect(screen.getByText('orders')).toBeInTheDocument()
    expect(screen.getByText('WHERE clause')).toBeInTheDocument()
  })

  it('fires onDecompose(nodeId, ruleName) from the right-click context menu', () => {
    const { props } = renderTree({
      optionsByNodeId: { 'node-root': mockDecomposeOptions },
    })
    fireEvent.contextMenu(screen.getByText('Full Query'))
    fireEvent.click(screen.getByRole('menuitem', { name: /Strip JOIN/ }))
    expect(props.onDecompose).toHaveBeenCalledTimes(1)
    expect(props.onDecompose).toHaveBeenCalledWith('node-root', 'join-split')
  })

  it('mounts both occurrences of a repeated fragment without a React key collision', () => {
    // React logs "Encountered two children with the same key" whenever two rows
    // collide on a React key. That warning is the DETERMINISTIC signal of the
    // defect this step fixes (Review Focus 2): with id keys it fires on mount for
    // both 'shared' and 'leaf'. (The post-toggle row counts are NOT reliable
    // evidence for the KEY half — duplicate keys make React's reconciliation
    // behavior unsupported, so stale/mismatched rows can coincidentally reproduce
    // the fixed count.)
    //
    // VACUITY RISK, stated so nobody mistakes this for a strong pin: if React ever
    // stops routing that warning through console.error, reword it, or a production
    // React build is used, `consoleErrors` stays `[]` and this assertion passes on
    // a broken implementation. The behavioural pin for per-position keying is the
    // unit test `flattenTree.test.ts` → "expands by key, so only the toggled
    // occurrence opens" (verified red on id-keyed semantics); the chevron
    // assertions below pin the WIRING half only.
    const consoleErrors: string[] = []
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) => {
      consoleErrors.push(args.map(String).join(' '))
    })
    try {
      const leaf = { ...buildMockTree(), id: 'leaf', label: 'leaf', children: [], nodeCount: 0 }
      const shared = { ...buildMockTree(), id: 'shared', label: 'shared', children: [leaf], nodeCount: 1 }
      const tree = {
        ...buildMockTree(),
        children: [
          { ...buildMockTree(), id: 'node-p1', label: 'p1', children: [{ ...shared }], nodeCount: 1 },
          { ...buildMockTree(), id: 'node-p2', label: 'p2', children: [{ ...shared }], nodeCount: 1 },
        ],
      }
      renderTree({ tree })
      // Both 'shared' occurrences mount at depth 2, and the initial auto-expand
      // effect (prevHasChildrenRef starts empty) expands every node that has
      // children — so each occurrence renders its own 'leaf' child.
      expect(screen.getAllByText('shared')).toHaveLength(2)
      expect(screen.getAllByText('leaf')).toHaveLength(2)
      // Collapsing one occurrence must leave the other expanded. This is the WIRING
      // pin: the warning only covers `key={flat.key}` — with the toggle left wired to
      // `flat.id` the click adds a bare id to a set of PATH keys, so no row matches
      // and nothing collapses. Both assertions below are red on that bug (observed:
      // the leaf count stays 2 and occurrence 1 still reads 'expanded'). The count is
      // a pin for the WIRING half only — under id KEYS it is not evidence at all,
      // because React's reconciliation leaves a stale row set whose count coincides.
      const firstSharedRow = screen.getAllByText('shared')[0].closest('[role="treeitem"]') as HTMLElement
      fireEvent.click(within(firstSharedRow).getByText('▾'))
      expect(screen.getAllByText('leaf')).toHaveLength(1)
      const sharedRowsAfter = screen
        .getAllByText('shared')
        .map((el) => el.closest('[role="treeitem"]') as HTMLElement)
      expect(sharedRowsAfter[0].querySelector('.tree-chevron')).toHaveClass('collapsed')
      expect(sharedRowsAfter[1].querySelector('.tree-chevron')).toHaveClass('expanded')
      // THE Review Focus 2 assertion: React sees a unique key per row.
      expect(consoleErrors.filter((e) => /same key/i.test(e))).toEqual([])
    } finally {
      consoleSpy.mockRestore()
    }
  })

  it('shows the add-back banner only while add-back mode is active', () => {
    const addBack = {
      active: true, seedLabel: 'a', breadcrumb: ['a', 'b（原 SQL）'], reachesOrigin: true,
      isOriginRoot: true,
      degraded: false, reason: null, onExit: vi.fn(),
    }
    const { rerender, props } = renderTree({ addBack })
    expect(screen.getByText('Seed: a')).toBeInTheDocument()
    rerender(<FragmentTree {...props} addBack={{ ...addBack, active: false }} />)
    expect(screen.queryByText('Seed: a')).toBeNull()
  })

  // ── fix round 1: spec 2026-09-29 §7.1「编辑标记跨投影存活」+ its MANDATORY
  //    sensitivity probe. Closes whole-branch review I2 (Ruling T11-6): the row and
  //    the probe had ZERO coverage, while App.tsx asserted the property in a comment
  //    as if verified. ──
  it('keeps the edit mark across a top-down ⇄ add-back projection switch (spec §7.1 sensitivity probe)', () => {
    // MECHANISM THAT MUST MAKE THIS RED, stated before running it (§7.1 demands the
    // probe be non-vacuous): the mark is looked up by the RENDERED ROW's `node.id`
    // (`FragmentTreeNode`: `editMarks?.get(node.id)`), and the marks map is keyed by
    // DAG node ids. So the probe can only redden if the projection emits an id that
    // is NOT the DAG node's own id — i.e. if `buildDown` re-keys or synthesises ids.
    // That is not hypothetical: the implementation this branch deleted did exactly
    // that (`treeProjection.ts` at 0addc65 built `id: \`composition-${parent.id}-${node.id}\``).
    // Recorded red (one-line mutation of `buildDown`'s return, same round, green
    // before it): `{ ...node, id: `${nodeId}-proj`, children, nodeCount }` → the
    // projected-row assertion below fails with no `.tree-node-edit-mark` on the
    // '已编辑 → e2e edited' row, while the top-down half still passes.
    // 3-level top-down fixture: root 'Full Query' → 'mid' → 'WHERE clause'. The
    // add-back projection contains exactly the seed and its REVEALED ancestors
    // (`collect` walks up from the seed), so a deeper chain is what puts more than
    // two facets in the projected tree.
    const topDown: FragmentNode = {
      ...buildMockTree(),
      children: [
        { ...buildMockTree(), id: 'node-mid', label: 'mid', nodeCount: 1, children: [
          { ...buildMockTree(), id: 'node-where', label: 'WHERE clause', children: [], nodeCount: 0 },
        ] },
      ],
    }
    const dag = createDAGState()
    indexTreeIntoDAG(dag, topDown)
    const index = buildParentIndex(dag, topDown)

    // Seed = 'node-where' (deepest), TWO revealed hops up to the DAG root.
    let state = createAddBackState('node-where')
    state = revealParent(state, 'node-where', 'node-mid')
    state = revealParent(state, 'node-mid', 'node-root')
    const projection = buildAddBackProjection(state, dag, index)

    const editMarks = new Map([
      ['node-where', { kind: 'link' as const, text: '已编辑 → e2e edited', targetSessionId: 'sess-B' }],
    ])

    // 1. Top-down: the mark is on the source facet's row.
    const { rerender, props } = renderTree({ tree: topDown, editMarks })
    expect(within(rowOf('WHERE clause')).getByText('已编辑 → e2e edited')).toBeInTheDocument()

    // 2. Switch to the add-back projection (≥1 revealed hop, projection root ≠ seed).
    rerender(<FragmentTree {...props} tree={projection.tree} addBack={{
      active: true, seedLabel: 'WHERE clause', breadcrumb: ['WHERE clause', 'Full Query（原 SQL）'],
      reachesOrigin: true, isOriginRoot: true, degraded: false, reason: null, onExit: vi.fn(),
    }} />)
    expect(within(rowOf('WHERE clause')).getByText('已编辑 → e2e edited')).toBeInTheDocument()
    // Negative half (the trap pinned in FragmentTreeNode.test.tsx): a blanket mark
    // would satisfy the positive assertion above, so the two OTHER facets of the same
    // projected tree ('mid' and the projection root) must carry NO mark.
    expect(rowOf('mid').querySelector('.tree-node-edit-mark')).toBeNull()
    expect(rowOf('Full Query').querySelector('.tree-node-edit-mark')).toBeNull()
    expect(screen.queryAllByText('已编辑 → e2e edited')).toHaveLength(1)

    // 3. And back to top-down — the property is symmetric, not a one-way accident.
    rerender(<FragmentTree {...props} tree={topDown} />)
    expect(within(rowOf('WHERE clause')).getByText('已编辑 → e2e edited')).toBeInTheDocument()

    // Fixture identity, asserted LAST on purpose: it is what makes the probe
    // non-trivial (projection root ≠ seed, marked row below the root), but it must
    // not be the assertion that reddens first — the recorded mutation red must land
    // on a MARK assertion, otherwise this test's discriminator is the fixture shape
    // rather than the property under test. Verified: under the `buildDown`
    // re-keying mutation the failure is `getByText('已编辑 → e2e edited')` on the
    // projected row; the identity checks below also fail there, but second.
    expect(projection.tree?.id).toBe('node-root')
    const projectedMid = projection.tree?.children.find((c) => c.label === 'mid')
    expect(projectedMid?.id).toBe('node-mid')
    expect(projectedMid?.children.find((c) => c.label === 'WHERE clause')?.id).toBe('node-where')
    expect(projection.tree?.id).not.toBe('node-where')
  })

  // ── fix round 1, I1(b) / M-2: the add-back panel's empty state is STATED, not the
  //    no-parse placeholder. ──
  it('states the add-back no-projection case instead of the paste-a-query placeholder', () => {
    const addBack = {
      active: true, seedLabel: '19bfa798cd02bfe8458cfea42ef1c221423cb7b4b4a55eb135ca061a88c5b231',
      breadcrumb: [], reachesOrigin: false, isOriginRoot: false,
      degraded: false, reason: null, onExit: vi.fn(),
    }
    renderTree({ tree: null, addBack, addBackSkipped: ['19bfa798cd02bfe8458cfea42ef1c221423cb7b4b4a55eb135ca061a88c5b231'] })
    // liveness pair: this is NOT "no SQL parsed yet" — the copy must not tell the user
    // to paste a query while a parse result exists and a banner is showing.
    expect(screen.queryByText('Paste a SQL query')).toBeNull()
    const notice = screen.getByRole('alert')
    expect(notice).toHaveTextContent('Add-back exploration has nothing to show')
    expect(notice).toHaveTextContent('19bfa798cd02bfe8458cfea42ef1c221423cb7b4b4a55eb135ca061a88c5b231')
  })

})
