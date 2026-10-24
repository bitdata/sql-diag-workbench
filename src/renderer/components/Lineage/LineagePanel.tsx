import { tr, useLocale, getLocale } from '../../i18n'
import { useState, useMemo, useEffect } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { isDegraded, getDegradedReason, type FragmentNode } from '../../types/fragment'
import type { SqlDialect } from '../../types/session'
import DiffViewer from './DiffViewer'
import DiffGraph from './DiffGraph'
import { useChangeAttribution } from '../../hooks/useChangeAttribution'
import { useDiffGraph } from '../../hooks/useDiffGraph'
import './LineagePanel.css'
import { completenessText } from '../../utils/resultCompleteness'
import type { ExecutionSummaryLike } from '../../hooks/useExecution'
import { useExperimentSummary } from '../Editor/ExperimentSources'

function ExperimentSummary({ nodeId }: { nodeId: string }) {
  useLocale()
  const text = useExperimentSummary(nodeId)
  return text ? <div className="lineage-panel-step-meta">{text}</div> : null
}

interface LineagePanelProps {
  chain: FragmentNode[]
  pathNotice?: string | null
  /** Full set of DAG nodes — needed by useDiffGraph to find dead-end ghosts
   *  that are not on the operation chain. */
  allNodes?: FragmentNode[]
  selectedNodeId: string | null
  backendUrl: string | null
  dialect: SqlDialect
  /** Optional runtime summary cache, used when a node's record doesn't
   *  carry an executionSummary (spec §4.2 fallback). */
  executionSummaries?: Record<string, ExecutionSummaryLike>
  onClose: () => void
  onSelectNode: (id: string) => void
}

/**
 * Module-level stable empty array for the {@link LineagePanel} `allNodes`
 * default. Using a fresh `[]` inline would re-create an array on every render,
 * churn the `useDiffGraph` effect deps, and trigger an infinite render→effect→
 * setState loop when a future caller omits the optional prop (current
 * OverlayHost always passes it, so production is safe — the test file pins the
 * same constant inline). See CLAUDE.md rule #1 (exhaustive same-class sweep).
 */
const EMPTY_NODES: FragmentNode[] = []

function stepLabel(node: FragmentNode): string {
  if (node.transform?.description) return node.transform.description
  switch (node.type) {
    case 'ORIGINAL':    return tr("Parsed · Original")
    case 'USER_EDITED': return tr("User edit")
    case 'PREDICATE_DECOMPOSED': return tr("Auto · Decompose")
    default:            return node.type
  }
}

function formatMeta(
  node: FragmentNode,
  fallback?: Record<string, ExecutionSummaryLike>
): string {
  const es = node.executionSummary ?? fallback?.[node.id]
  const ts = node.createdAt ?? ''
  const reason = getDegradedReason(node)
  const parts: string[] = []
  if (es) parts.push(tr("{0} rows returned · {1}ms · {2}", es.rows, es.durationMs, completenessText(es.resultCompleteness)))
  if (ts) parts.push(ts)
  if (isDegraded(node)) parts.push(reason ? tr("Identity degraded — {0}", reason) : tr("Identity degraded"))
  return parts.join(' · ')
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + '…' : s
}

/**
 * IntelliJ Git Log-style lineage panel. Spec §5.2.
 * Left: operation-tree chain. Right: diff viewer. Bottom: diff-graph toggle.
 */
export default function LineagePanel({
  chain,
  pathNotice,
  allNodes = EMPTY_NODES,
  selectedNodeId,
  backendUrl,
  dialect,
  executionSummaries,
  onClose,
  onSelectNode,
}: LineagePanelProps) {
  useLocale()
  const [activeStepId, setActiveStepId] = useState<string | null>(selectedNodeId)
  const [showDiffGraph, setShowDiffGraph] = useState(false)

  // Default the active step to the deepest chain node (currently-selected).
  useEffect(() => {
    if (selectedNodeId) setActiveStepId(selectedNodeId)
  }, [selectedNodeId])

  const activeIndex = useMemo(
    () => chain.findIndex((n) => n.id === activeStepId),
    [chain, activeStepId, getLocale()]
  )
  const activeNode = activeIndex >= 0 ? chain[activeIndex] : null
  const parentNode = activeIndex > 0 ? chain[activeIndex - 1] : null

  const { ops, loading, error, degraded, diffMethod } = useChangeAttribution(
    backendUrl,
    parentNode?.sqlFragment ?? '',
    activeNode?.sqlFragment ?? '',
    dialect
  )

  // Spec §3.3 L2 / §5.2.3 — diff-graph view: chain + dead-end ghost nodes,
  // virtual edges for the diagnostic subset relationship. Toggle controls
  // visibility (default collapsed so the main chain view isn't disturbed).
  const { ghosts, edges: virtualEdges, loading: diffGraphLoading, error: diffGraphError } = useDiffGraph(
    chain,
    allNodes,
    backendUrl,
    dialect
  )

  return (
    <Dialog.Root open onOpenChange={(open) => { if (!open) onClose() }}>
      <Dialog.Portal>
        <Dialog.Overlay className="lineage-panel-overlay" />
        <Dialog.Content className="lineage-panel-modal" aria-describedby={undefined}>
          <div className="lineage-panel-header">
            <Dialog.Title className="lineage-panel-title">
              {tr("Lineage ·")}{" "}{activeNode ? stepLabel(activeNode) : tr("No selection")}
            </Dialog.Title>
            <button
              type="button"
              className="lineage-panel-btn"
              onClick={() => setShowDiffGraph((v) => !v)}
              aria-expanded={showDiffGraph}
            >
              {showDiffGraph ? tr("Hide") : tr("Show")} {" "}{tr("diff graph")}{" "}</button>
            <Dialog.Close asChild>
              <button type="button" className="lineage-panel-btn" aria-label={tr("Close")}>×</button>
            </Dialog.Close>
          </div>

          <div className="lineage-panel-body">
            <div className="lineage-panel-chain" aria-label={tr("Operation chain")}>
              {pathNotice && <div className="lineage-panel-step-meta" role="status">{pathNotice}</div>}
              {chain.length === 0 ? (
                <div className="lineage-panel-empty">{tr("No chain steps")}</div>
              ) : (
                chain.map((node, idx) => {
                  const isCurrent = node.id === selectedNodeId
                  const isActive = node.id === activeStepId
                  return (
                    <div
                      key={node.id}
                      className={`lineage-panel-step ${isActive ? 'selected' : ''}`}
                      onClick={() => setActiveStepId(node.id)}
                    >
                      <span
                        className={`lineage-panel-step-marker ${isCurrent ? 'current' : 'ancestor'}`}
                      >
                        {isCurrent ? '●' : '○'}
                      </span>
                      <span className="lineage-panel-step-title">
                        {idx === 0 ? '◉ ' : ''}{stepLabel(node)}
                      </span>
                      <div className="lineage-panel-step-desc">
                        {truncate(node.sqlFragment, 80)}
                      </div>
                      <div className="lineage-panel-step-meta">{formatMeta(node, executionSummaries)}</div>
                      <ExperimentSummary nodeId={node.id} />
                      <div
                        className="lineage-panel-step-jump"
                        onClick={(e) => { e.stopPropagation(); onSelectNode(node.id); onClose() }}
                      >
                        {tr("jump to tree →")}{" "}</div>
                    </div>
                  )
                })
              )}
            </div>

            <div className="lineage-panel-diff">
              {activeNode && parentNode ? (
                <DiffViewer
                  parentSql={parentNode.sqlFragment}
                  currentSql={activeNode.sqlFragment}
                  ops={ops}
                  loading={loading}
                  error={error}
                  degraded={degraded}
                  diffMethod={diffMethod}
                />
              ) : activeNode ? (
                <div className="lineage-panel-empty">
                  {pathNotice ? tr("Start of available path") : tr("Root node")} {" "}{tr("— select a child step to see the diff")}{" "}</div>
              ) : (
                <div className="lineage-panel-empty">{tr("Select a chain step to see diff")}</div>
              )}
            </div>
          </div>

          {showDiffGraph && (
            <div className="lineage-panel-diffgraph" role="region" aria-label={tr("Diff graph")}>
              <DiffGraph
                chain={chain}
                edges={virtualEdges}
                loading={diffGraphLoading}
                error={diffGraphError}
              />
              {ghosts.length > 0 && virtualEdges.length > 0 && (
                <div style={{ marginTop: 8, fontSize: 10, color: 'var(--text-muted, #888)' }}>
                  {tr(ghosts.length === 1 ? "Found {0} ghost node" : "Found {0} ghost nodes", ghosts.length)} {" "}{tr("(dead-end siblings of the operation chain). Virtual edges show Δ(currentNode, ghost) — \"⊆ root→ghost\" marks the diagnostic subset case (spec §3.3 / §5.2.3).")}{" "}</div>
              )}
            </div>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
