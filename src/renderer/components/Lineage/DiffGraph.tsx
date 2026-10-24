import { tr, useLocale, getLocale } from '../../i18n'
import { useMemo } from 'react'
import type { FragmentNode } from '../../types/fragment'
import type { VirtualEdge } from '../../hooks/useDiffGraph'
import './DiffGraph.css'

interface DiffGraphProps {
  chain: FragmentNode[]
  edges: VirtualEdge[]
  loading: boolean
  error: string | null
}

const NODE_W = 160
const NODE_H = 44
const GHOST_X = 280
const ROW_H = 64

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + '…' : s
}

function nodeLabel(node: FragmentNode): string {
  if (node.transform?.description) return node.transform.description
  switch (node.type) {
    case 'ORIGINAL':    return tr("Original")
    case 'USER_EDITED': return tr("User edit")
    case 'PREDICATE_DECOMPOSED': return tr("Decompose")
    case 'JOIN_SPLIT':  return tr("Join split")
    default:            return node.type
  }
}

/**
 * Spec §5.2.3 — diff-graph visualization with virtual edges.
 * Layout: operation chain (vertical, left), ghost nodes (right of the
 * current node), dashed virtual edges connecting current to each ghost.
 */
export default function DiffGraph({ chain, edges, loading, error }: DiffGraphProps) {
  useLocale()
  const layout = useMemo(() => {
    if (chain.length === 0) return null
    const totalRows = Math.max(chain.length, edges.length > 0 ? chain.length : 0)
    const width = 600
    const height = 20 + totalRows * ROW_H + 20
    const currentIdx = chain.length - 1
    const currentY = 20 + currentIdx * ROW_H + NODE_H / 2

    // Chain node positions
    const chainNodes = chain.map((n, i) => ({
      node: n,
      x: 20,
      y: 20 + i * ROW_H,
      isCurrent: i === currentIdx,
    }))

    // Ghost positions: stacked at the right, starting at the current row
    const ghostNodes = edges.map((e, i) => ({
      node: e.to,
      x: GHOST_X,
      y: 20 + (currentIdx + i) * ROW_H,
      edge: e,
    }))

    // Tree edges (operation chain) — between consecutive chain nodes
    const treeEdges = chain.slice(0, -1).map((_, i) => ({
      x1: 20 + NODE_W,
      y1: 20 + i * ROW_H + NODE_H / 2,
      x2: 20,
      y2: 20 + (i + 1) * ROW_H + NODE_H / 2,
    }))

    // Virtual edges — currentNode → each ghost
    const virtualEdges = ghostNodes.map((g) => ({
      x1: 20 + NODE_W,
      y1: currentY,
      x2: g.x,
      y2: g.y + NODE_H / 2,
      edge: g.edge,
    }))

    return {
      width: Math.max(width, GHOST_X + NODE_W + 20),
      height: Math.max(height, 20 + (currentIdx + edges.length + 1) * ROW_H),
      chainNodes,
      ghostNodes,
      treeEdges,
      virtualEdges,
    }
  }, [chain, edges, getLocale()])

  if (loading) {
    return <div className="diff-graph-loading">{tr("Computing diff graph…")}</div>
  }
  if (error) {
    return <div className="diff-graph-error">{tr("Diff graph error:")}{" "}{error}</div>
  }
  if (!layout) {
    return <div className="diff-graph-empty">{tr("No diff graph")}</div>
  }
  if (edges.length === 0) {
    return <div className="diff-graph-empty">{tr("No virtual edges (current node has no dead-end siblings)")}</div>
  }

  return (
    <div className="diff-graph">
      <svg
        width={layout.width}
        height={layout.height}
        viewBox={`0 0 ${layout.width} ${layout.height}`}
        role="img"
        aria-label={tr("Diff graph with virtual edges")}
      >
        {/* Operation chain edges (solid) */}
        {layout.treeEdges.map((e, i) => (
          <line
            key={`tree-${i}`}
            className="diff-graph-edge-tree"
            x1={e.x1}
            y1={e.y1}
            x2={e.x2}
            y2={e.y2}
          />
        ))}

        {/* Virtual edges (dashed) — current → each ghost */}
        {layout.virtualEdges.map((e, i) => (
          <g key={`v-${i}`}>
            <line
              className="diff-graph-edge-virtual"
              x1={e.x1}
              y1={e.y1}
              x2={e.x2}
              y2={e.y2}
            />
            <text
              className="diff-graph-edge-label"
              x={(e.x1 + e.x2) / 2}
              y={(e.y1 + e.y2) / 2 - 4}
              textAnchor="middle"
            >
              Δ = {e.edge.ops.length} op{e.edge.ops.length === 1 ? '' : 's'}
              {e.edge.isVirtual ? ' (⊆ root→ghost)' : ''}
            </text>
          </g>
        ))}

        {/* Chain nodes */}
        {layout.chainNodes.map((c, i) => (
          <g key={`cn-${i}`} transform={`translate(${c.x}, ${c.y})`}>
            <rect
              className={c.isCurrent ? 'diff-graph-node-current' : 'diff-graph-node-chain'}
              width={NODE_W}
              height={NODE_H}
              rx={6}
            />
            <text
              className="diff-graph-node-text"
              x={NODE_W / 2}
              y={18}
              textAnchor="middle"
            >
              {truncate(nodeLabel(c.node), 22)}
            </text>
            <text
              className="diff-graph-node-sub"
              x={NODE_W / 2}
              y={34}
              textAnchor="middle"
            >
              {truncate(c.node.sqlFragment.replace(/\s+/g, ' '), 24)}
            </text>
          </g>
        ))}

        {/* Ghost nodes */}
        {layout.ghostNodes.map((g, i) => (
          <g key={`gn-${i}`} transform={`translate(${g.x}, ${g.y})`}>
            <rect
              className="diff-graph-node-ghost"
              width={NODE_W}
              height={NODE_H}
              rx={6}
            />
            <text
              className="diff-graph-node-text"
              x={NODE_W / 2}
              y={18}
              textAnchor="middle"
            >
              {truncate(nodeLabel(g.node), 22)}
            </text>
            <text
              className="diff-graph-node-sub"
              x={NODE_W / 2}
              y={34}
              textAnchor="middle"
            >
              {tr("ghost ·")}{" "}{truncate(g.node.sqlFragment.replace(/\s+/g, ' '), 24)}
            </text>
          </g>
        ))}
      </svg>
    </div>
  )
}
