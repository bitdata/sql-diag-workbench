import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import DiffGraph from '../DiffGraph'
import type { VirtualEdge } from '../../../hooks/useDiffGraph'
import type { ChangeOp } from '../../../hooks/useChangeAttribution'
import type { FragmentNode, FragmentType } from '../../../types/fragment'

/**
 * DiffGraph props are { chain, edges, loading, error } — ALL required
 * (DiffGraph.tsx:6-11); the brief's `edges={[{from,to,op}]}` shape is
 * fictional: real edges are VirtualEdge { from, to: FragmentNode, ops,
 * isVirtual, degraded, diffMethod } (useDiffGraph.ts:15-41).
 * There are no data-node-id attributes in the DOM (recon fact) — nodes and
 * edges are pinned via svg <text> content and geometry classes instead.
 */

function node(id: string, type: FragmentType, sql: string, description: string): FragmentNode {
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
  }
}

const root = node('n-root', 'ORIGINAL', 'SELECT * FROM orders', 'Original query')
const current = node('n-cur', 'JOIN_SPLIT', 'SELECT * FROM orders JOIN c', 'Join split')
const ghostA = node('n-ga', 'PREDICATE_DECOMPOSED', 'SELECT * FROM a WHERE x', 'Ghost A')
const ghostB = node('n-gb', 'STRIPPED', 'SELECT a FROM orders', 'Ghost B')

function op(oldText: string, newText: string): ChangeOp {
  return { kind: 'MOD', nodeType: 'SqlIdentifier', path: '/select[0]', oldText, newText }
}

function edge(from: FragmentNode, to: FragmentNode, ops: ChangeOp[], isVirtual: boolean): VirtualEdge {
  return { from, to, ops, isVirtual, degraded: false, diffMethod: 'ast-lcs' }
}

function renderGraph(
  chain: FragmentNode[],
  edges: VirtualEdge[],
  loading = false,
  error: string | null = null
) {
  return render(<DiffGraph chain={chain} edges={edges} loading={loading} error={error} />)
}

describe('DiffGraph', () => {
  it('renders the svg graph: chain nodes, ghost nodes, tree edge, virtual edge labels', () => {
    const { container } = renderGraph(
      [root, current],
      [
        edge(current, ghostA, [op('a', 'b'), op('c', 'd')], true), // 2 ops, subset case
        edge(current, ghostB, [op('x', 'y')], false), // 1 op, not virtual
      ]
    )
    const svg = screen.getByRole('img', { name: 'Diff graph with virtual edges' })
    expect(svg).toBeInTheDocument()

    // chain node labels + ghost sub-labels ("ghost · " prefix, DiffGraph.tsx:197)
    expect(screen.getByText('Original query')).toBeInTheDocument()
    expect(screen.getByText('Join split')).toBeInTheDocument()
    expect(screen.getByText('ghost · SELECT * FROM a WHERE x')).toBeInTheDocument()
    expect(screen.getByText('ghost · SELECT a FROM orders')).toBeInTheDocument()

    // edge labels pin the ops-count plural branch AND the ⊆ subset marker
    expect(screen.getByText('Δ = 2 ops (⊆ root→ghost)')).toBeInTheDocument()
    expect(screen.getByText('Δ = 1 op')).toBeInTheDocument()

    // geometry: 1 solid chain edge (2 nodes), 2 dashed virtual edges, 2 ghost rects
    expect(container.querySelectorAll('line.diff-graph-edge-tree')).toHaveLength(1)
    expect(container.querySelectorAll('line.diff-graph-edge-virtual')).toHaveLength(2)
    expect(container.querySelectorAll('rect.diff-graph-node-ghost')).toHaveLength(2)
    // exactly the last chain node is styled "current"
    expect(container.querySelectorAll('rect.diff-graph-node-current')).toHaveLength(1)
    expect(container.querySelectorAll('rect.diff-graph-node-chain')).toHaveLength(1)
  })

  it('loading state wins: shows "Computing diff graph…" even with edges present', () => {
    const { container } = renderGraph([root, current], [edge(current, ghostA, [op('a', 'b')], true)], true)
    expect(screen.getByText('Computing diff graph…')).toBeInTheDocument()
    expect(container.querySelector('svg')).toBeNull()
  })

  it('error state wins over empty states', () => {
    const { container } = renderGraph([root], [], false, 'diff service down')
    expect(screen.getByText('Diff graph error: diff service down')).toBeInTheDocument()
    expect(screen.queryByText('No diff graph')).toBeNull()
    expect(container.querySelector('svg')).toBeNull()
  })

  it('empty chain renders "No diff graph" (no svg)', () => {
    const { container } = renderGraph([], [])
    expect(screen.getByText('No diff graph')).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'Diff graph with virtual edges' })).toBeNull()
    expect(container.querySelector('svg')).toBeNull()
  })

  it('chain but zero virtual edges renders the dead-end-siblings empty text', () => {
    const { container } = renderGraph([root, current], [])
    expect(screen.getByText('No virtual edges (current node has no dead-end siblings)')).toBeInTheDocument()
    expect(screen.queryByText('No diff graph')).toBeNull() // distinct from the no-chain state
    expect(container.querySelector('svg')).toBeNull()
  })

  it('truncates over-long node labels to 22 chars and collapses whitespace in SQL subs', () => {
    const long = node('n-long', 'ORIGINAL', 'SELECT   a,\n  b FROM t', 'A'.repeat(30))
    renderGraph([long], [edge(long, ghostB, [op('p', 'q')], false)])
    // label truncated to 22 + … (DiffGraph.tsx:161), sub collapses \s+ (DiffGraph.tsx:169)
    expect(screen.getByText(`${'A'.repeat(22)}…`)).toBeInTheDocument()
    expect(screen.getByText('SELECT a, b FROM t')).toBeInTheDocument()
    expect(screen.getByText('ghost · SELECT a FROM orders')).toBeInTheDocument()
  })
})
