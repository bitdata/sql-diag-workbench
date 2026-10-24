import { tr, useLocale } from '../../i18n'
import * as Tooltip from '@radix-ui/react-tooltip'
import { FragmentNode, getFragmentIcon, isDegraded, getDegradedReason } from '../../types/fragment'
import './NodeTooltip.css'
import { useExperimentSummary } from '../Editor/ExperimentSources'

interface NodeTooltipProps {
  node: FragmentNode
  children: React.ReactNode
}

const TYPE_LABELS: Record<string, string> = {
  ORIGINAL: 'Original Query',
  STRIPPED: 'Stripped',
  SET_SPLIT: 'Set Operation',
  PREDICATE_DECOMPOSED: 'Predicate',
  COLUMN_PRUNED: 'Column Pruned',
  JOIN_SPLIT: 'Join Split',
  SUBQUERY_EXTRACTED: 'Subquery',
  CTE_EXPANDED: 'CTE',
  USER_EDITED: 'User Edit',
}

function truncateSql(sql: string, maxLen: number): string {
  if (sql.length <= maxLen) return sql
  return sql.slice(0, maxLen) + '...'
}

/** Tooltip content without the Radix shell — exported so unit tests can assert
 *  it directly (Radix renders content in a Portal only after the hover delay). */
export function NodeTooltipBody({ node }: { node: FragmentNode }) {
  useLocale()
  const experiment = useExperimentSummary(node.id)
  const typeLabel = tr(TYPE_LABELS[node.type] ?? node.type)
  const degraded = isDegraded(node)
  const degradedReason = getDegradedReason(node)

  return (
    <>
      <div className="node-tooltip-header">
        <span className="node-tooltip-icon">{getFragmentIcon(node.type)}</span>
        <span className="node-tooltip-type">{typeLabel}</span>
      </div>
      <div className="node-tooltip-sql">
        {truncateSql(node.sqlFragment, 200)}
      </div>
      {experiment && <div className="node-tooltip-meta">{experiment}</div>}
      <div className="node-tooltip-meta">
        {node.nodeCount > 0 && (
          <span className="node-tooltip-stat">
            {tr(node.nodeCount === 1 ? "{0} node" : "{0} nodes", node.nodeCount)}
          </span>
        )}
        {node.ruleName && (
          <span className="node-tooltip-stat">{node.ruleName}</span>
        )}
        <span className="node-tooltip-stat">
          {node.executable ? tr("Runnable") : tr("View-only")}
        </span>
      </div>
      {degraded && (
        <div className="node-tooltip-degraded">
          <div className="node-tooltip-degraded-line">
            {degradedReason ? tr("Identity degraded — {0}", degradedReason) : tr("Identity degraded")}
          </div>
          <div className="node-tooltip-degraded-note">
            {tr("the id was derived from raw text, so equivalent rewrites will not be recognized as the same fragment")}{" "}</div>
        </div>
      )}
    </>
  )
}

/**
 * Rich tooltip for tree nodes, powered by Radix UI.
 * Wraps children as the trigger; tooltip content renders in a Portal.
 * 500ms delay before showing, matching the original behavior.
 */
export default function NodeTooltip({ node, children }: NodeTooltipProps) {
  useLocale()
  return (
    <Tooltip.Root delayDuration={500}>
      <Tooltip.Trigger asChild>
        {children}
      </Tooltip.Trigger>
      <Tooltip.Portal container={document.querySelector('.app')}>
        <Tooltip.Content
          className="node-tooltip"
          side="right"
          sideOffset={8}
          align="center"
          avoidCollisions
          collisionPadding={8}
        >
          <NodeTooltipBody node={node} />
          <Tooltip.Arrow className="node-tooltip-arrow" />
        </Tooltip.Content>
      </Tooltip.Portal>
    </Tooltip.Root>
  )
}
