import { tr, useLocale } from '../../i18n'
import { metricText, nodeMetric } from '../../utils/planMetrics'
import { PlanNode, PlanCapture } from '../../types/execution'
import './PlanView.css'

interface PlanViewProps {
  capture?: PlanCapture | null
  durationMs?: number
  plan: PlanNode | null
  loading: boolean
  /**
   * Reason the EXPLAIN could not be captured, if any. Shown in the empty
   * state so the user can tell "haven't executed yet" from "EXPLAIN failed
   * — here's why". null when there is no failure to report.
   */
  planError?: string | null
}

export default function PlanView({ plan, loading, planError, capture, durationMs }: PlanViewProps) {
  useLocale()
  if (loading) {
    return (
      <div className="plan-loading" aria-busy="true">
        {[1, 2, 3].map(i => (
          <div key={i} className="plan-skeleton-row" style={{ marginLeft: `${i * 16}px` }}>
            <div className="plan-skeleton-bar" style={{ width: `${80 - i * 15}%` }} />
          </div>
        ))}
      </div>
    )
  }

  if (!plan) {
    return (
      <div className="plan-empty">
        {capture && <CaptureDetails capture={capture} durationMs={durationMs} />}
        {planError ? (
          <>
            <p className="placeholder-text">{tr("Execution plan is unavailable")}</p>
            <p className="placeholder-text-sub" role="alert">{planError}</p>
          </>
        ) : (
          <p className="placeholder-text">{tr("Run Analyze to view the execution plan")}</p>
        )}
      </div>
    )
  }

  return (
    <div className="plan-view">
      <CaptureDetails capture={capture} durationMs={durationMs} />
      <div className="plan-header">
        <span className="plan-header-op">{tr("Operation")}</span>
        <span className="plan-header-rows">{tr("Estimated / Actual rows")}</span>
        <span className="plan-header-time">{tr("Time ms / Loops")}</span>
        <span className="plan-header-cost">{tr("Cost")}</span>
      </div>
      <PlanNodeRow node={plan} depth={0} />
    </div>
  )
}

function PlanNodeRow({ node, depth }: { node: PlanNode; depth: number }) {
  useLocale()
  const text = (key: Parameters<typeof nodeMetric>[1]) => metricText(nodeMetric(node.metrics, key))

  return (
    <>
      <div className="plan-row" style={{ paddingLeft: `${8 + depth * 20}px` }}>
        <span className="plan-op">{node.operation}</span>
        <span className="plan-rows">{text('estimatedRows')} / {text('actualRows')}</span>
        <span className="plan-time">
          <span className="plan-time-text">{text('actualDurationMs')} / {text('loops')}</span>
        </span>
        <span className="plan-cost">{text('totalCost')} ({nodeMetric(node.metrics, 'totalCost')?.basis ?? 'UNKNOWN'})</span>
      </div>
      {node.children?.map((child, i) => (
        <PlanNodeRow key={i} node={child} depth={depth + 1} />
      ))}
    </>
  )
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
        <p>{tr("Application elapsed:")}{" "}{durationMs ?? tr("Unknown")} {" "}{tr("ms. Runtime rows/time are per-loop averages; inclusive node times cannot be added.")}</p>
      </div>)
}
