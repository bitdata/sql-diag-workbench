import { useEffect, useRef, useState } from 'react'
import { tr, useLocale } from '../../i18n'
import { compareMetricsDescending, metricText, nodeMetric } from '../../utils/planMetrics'
import type { Metric, PlanNode, PlanCapture, PlanSqlMapping } from '../../types/execution'
import './PlanView.css'
import MappingDetails from './MappingTargetPreview'
import type { MappingPreviewSource } from '../../utils/mappingTargets'

interface PlanViewProps {
  capture?: PlanCapture | null
  durationMs?: number
  plan: PlanNode | null
  loading: boolean
  planError?: string | null
  selectedPath?: string
  onSelect?: (path: string) => void
  mapping?: PlanSqlMapping | null
  mappingPreview?: MappingPreviewSource
}
export function flattenPlan(plan: PlanNode | null): { node: PlanNode; depth: number }[] {
  if (!plan) return []
  const rows: { node: PlanNode; depth: number }[] = [], todo = [{ node: plan, depth: 0 }]
  while (todo.length) {
    const row = todo.pop()!
    rows.push(row)
    for (let i = row.node.children.length - 1; i >= 0; i--) todo.push({ node: row.node.children[i], depth: row.depth + 1 })
  }
  return rows
}
export default function PlanView({ plan, loading, planError, capture, durationMs, selectedPath, onSelect, mapping, mappingPreview }: PlanViewProps) {
  useLocale()
  const [order, setOrder] = useState('tree'), [filter, setFilter] = useState(''), [localPath, setLocalPath] = useState<string>()
  const path = selectedPath ?? localPath
  const detailsRef = useRef<HTMLDivElement>(null)
  useEffect(() => { if (selectedPath) { setFilter(''); detailsRef.current?.scrollIntoView?.({ block: 'nearest' }) } }, [selectedPath])
  const all = flattenPlan(plan)
  const rows = all.filter(({ node }) => `${node.operation} ${node.tableName ?? ''} ${node.metrics?.sourcePath ?? ''}`.toLowerCase().includes(filter.toLowerCase()))
  if (order !== 'tree') rows.sort((a, b) => compareMetricsDescending(nodeMetric(a.node.metrics, order as 'actualDurationMs'), nodeMetric(b.node.metrics, order as 'actualDurationMs')))
  const selected = path ? all.find(({ node }) => node.metrics?.version === 1 && node.metrics.sourcePath === path)?.node : undefined
  const evidence = capture?.version === 1 && capture.details?.version === 1 ? capture.details.nodes.find(n => n.sourcePath === path) : undefined
  if (loading) return <div className="plan-loading" aria-busy="true">{tr("Loading plan...")}</div>
  return <div className="plan-view">
    <CaptureDetails capture={capture} durationMs={durationMs} />
    {!plan ? <div className="plan-empty"><p>{tr(planError ? "Execution plan is unavailable" : "Run Analyze to view the execution plan")}</p>{planError && <p role="alert">{planError}</p>}</div> : <>
      <div className="plan-tools">
        <label>{tr("Order")} <select aria-label={tr("Plan order")} value={order} onChange={e => setOrder(e.target.value)}>
          <option value="tree">{tr("Tree order")}</option><option value="actualDurationMs">{tr("Node time descending")}</option>
          <option value="loops">{tr("Loops descending")}</option><option value="actualRows">{tr("Actual rows descending")}</option><option value="estimatedRows">{tr("Estimated rows descending")}</option>
        </select></label>
        <label>{tr("Filter nodes")} <input aria-label={tr("Filter nodes")} value={filter} onChange={e => setFilter(e.target.value)} /></label>
        <span>{tr("{0} of {1} nodes", rows.length, all.length)}</span>
      </div>
      <div className="plan-header"><span className="plan-header-op">{tr("Operation")}</span><span className="plan-header-rows">{tr("Estimated / Actual rows")}</span><span className="plan-header-time">{tr("Time ms / Loops")}</span><span className="plan-header-cost">{tr("Cost")}</span></div>
      {rows.map(({ node, depth }, i) => {
        const source = node.metrics?.version === 1 ? node.metrics.sourcePath : undefined
        const text = (key: Parameters<typeof nodeMetric>[1]) => metricText(nodeMetric(node.metrics, key))
        return <button type="button" className="plan-row" data-plan-path={source} key={source ?? i} aria-pressed={!!source && source === path} style={{ paddingLeft: `${8 + (order === 'tree' ? depth * 16 : 0)}px` }} onClick={() => { if (source) { setLocalPath(source); onSelect?.(source) } }}>
          <span className="plan-op" title={node.operation}>{node.operation}</span><span className="plan-rows">{text('estimatedRows')} / {text('actualRows')}</span>
          <span className="plan-time"><span className="plan-time-text">{text('actualDurationMs')} / {text('loops')}</span></span>
          <span className="plan-cost">{text('totalCost')} ({nodeMetric(node.metrics, 'totalCost')?.basis ?? 'UNKNOWN'})</span>
        </button>
      })}
      {selected && <div className="plan-node-details" tabIndex={-1} ref={detailsRef} aria-label={tr("Selected plan evidence")}>
        <h3>{selected.operation}</h3><p>{path} · {selected.metrics?.origin}</p>
        <MetricDetail label="Estimated rows" metric={nodeMetric(selected.metrics, 'estimatedRows')} />
        <MetricDetail label="Actual rows" metric={nodeMetric(selected.metrics, 'actualRows')} />
        <MetricDetail label="First row ms" metric={evidence?.firstRowMs} />
        <MetricDetail label="Last row ms" metric={nodeMetric(selected.metrics, 'actualDurationMs')} />
        <MetricDetail label="Loops" metric={nodeMetric(selected.metrics, 'loops')} />
        <MetricDetail label="Startup cost" metric={nodeMetric(selected.metrics, 'startupCost')} />
        <MetricDetail label="Total cost" metric={nodeMetric(selected.metrics, 'totalCost')} />
        <details open><summary>{tr("Original node evidence")}</summary><pre>{evidence?.raw ?? tr("Node source unavailable")}</pre></details>
        <MappingDetails mapping={mapping} path={path} source={mappingPreview} />
      </div>}
    </>}
  </div>
}

function MetricDetail({ label, metric }: { label: string; metric?: Metric | null }) {
  return <p className="plan-metric"><strong>{tr(label)}</strong> {metricText(metric)} · {metric?.state ?? 'UNKNOWN'} · {metric?.unit ?? 'UNKNOWN'} · {metric?.basis ?? 'UNKNOWN'} · {tr("Source field")}: {metric?.sourceField ?? tr("Unknown")}{metric?.errorReason ? ` · ${metric.errorReason}` : ''}</p>
}

function CaptureDetails({ capture, durationMs }: { capture?: PlanCapture | null; durationMs?: number }) {
  useLocale()
  return (<div className="plan-capture" role="status">
        {capture?.version === 1 ? <>
          <p>{tr("Requested:")}{" "}{capture.requestedMode} {" "}{tr("· Attempted:")}{" "}{capture.attemptedMode ?? tr("Not sent")} {" "}{tr("· Parse:")}{" "}{capture.parseStatus}</p>
          <p>{capture.serverProduct ?? tr("Unknown server")} {capture.serverVersion}</p>
          <details><summary>{tr("Captured SQL and limitations")}</summary><pre>{capture.explainSql ?? capture.submittedSql}</pre>
            <p>{capture.connectionId} · {capture.capturedAt ?? tr("No capture time")}</p>
            {capture.issues.map((issue, i) => <p key={i}>{issue.code} · {issue.nodePath}: {issue.message}</p>)}
          </details>
        </> : <p>{tr("Plan source unknown: legacy or unsupported capture")}</p>}
        {capture?.version === 1 && capture.details?.version === 1 ? <details className="plan-raw"><summary>{tr("Raw plan and environment")}</summary>
          <p>{capture.details.channel} · {capture.details.database ?? tr("Unknown")} · {capture.details.databaseUser ?? tr("Unknown")}</p>
          {capture.details.errorReason && <p role="alert">{capture.details.errorReason}</p>}
          <MetricDetail label="Planning time ms" metric={capture.details.planningTimeMs} /><MetricDetail label="Query execution time ms" metric={capture.details.executionTimeMs} />
          <pre>{JSON.stringify(capture.details.settings, null, 2)}</pre>
          <pre className="plan-raw-text">{capture.details.rawPlan ?? tr("Raw plan unavailable")}</pre>
        </details> : <p>{tr("Raw plan and environment unavailable for this capture")}</p>}
        <p>{tr("Application elapsed:")}{" "}{durationMs ?? tr("Unknown")} {" "}{tr("ms. Runtime rows/time are per-loop averages; inclusive node times cannot be added.")}</p>
      </div>)
}
