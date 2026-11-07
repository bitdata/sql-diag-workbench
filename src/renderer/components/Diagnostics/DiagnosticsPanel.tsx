import { tr, useLocale } from '../../i18n'
import { useEffect, useRef } from 'react'
import type { Evaluation } from '../../types/execution'
import { evaluationText } from '../../utils/planMetrics'
import type { DiagnosticFinding } from '../../types/diagnostics'
import DiagnosticCard from './DiagnosticCard'
import type { DiagnosticLifecycle } from '../../hooks/useDiagnostics'
import './DiagnosticsPanel.css'

interface DiagnosticsPanelProps {
  lifecycle?: DiagnosticLifecycle
  onRetry?: () => void
  onPlanEvidence?: (path: string) => void
  canPlanEvidence?: (path: string) => boolean
  evaluation?: Evaluation | null
  findings: DiagnosticFinding[]
  loading: boolean
  error: string | null
  onVerify?: (findingId: string, verificationSql: string) => void
  verifyingIds?: Set<string>
  verifyErrors?: Record<string, string>
  onClearVerifyError?: (findingId: string) => void
}

export default function DiagnosticsPanel({ lifecycle, onRetry, evaluation, findings, loading, error, onVerify, verifyingIds, verifyErrors, onClearVerifyError, onPlanEvidence, canPlanEvidence }: DiagnosticsPanelProps) {
  useLocale()
  const panel = useRef<HTMLDivElement>(null)
  const retryFocus = useRef(false)
  useEffect(() => {
    if (!retryFocus.current || !lifecycle || !['ready', 'unavailable', 'error'].includes(lifecycle.status)) return
    retryFocus.current = false
    const target = lifecycle.status === 'error' ? panel.current?.querySelector<HTMLButtonElement>('button') : panel.current
    target?.focus()
  }, [lifecycle?.status])
  if (loading) {
    return (
      <div ref={panel} className="diagnostics-panel diagnostics-loading" role="status">
        <span>{tr("Analyzing diagnostic rules...")}</span>
      </div>
    )
  }

  if (error) {
    return (
      <div ref={panel} tabIndex={-1} className="diagnostics-panel diagnostics-error" role="alert" data-error-reason={lifecycle?.errorReason}>
        <span>{tr("Analysis failed:")}{" "}{tr(error)}</span>
        {lifecycle?.errorReason && <code>{lifecycle.errorReason}</code>}
        {onRetry && <button className="toolbar-btn" onClick={() => { retryFocus.current = true; onRetry() }}>{tr("Retry diagnostics")}</button>}
      </div>
    )
  }

  if (!lifecycle || lifecycle.status === 'idle') return <div ref={panel} className="diagnostics-panel" role="status">
    <p>{tr("No diagnostics for the current plan yet. Analyze a plan in Performance mode.")}</p>
  </div>
  const available = lifecycle.status === 'ready'

  const coverage = <div className="diagnostics-coverage" role="status">
    {!available && <p data-error-reason={lifecycle.errorReason}>
      {tr("Plan source unavailable. Any findings below are limited observations. Analyze again to capture a new plan.")}
      {" "}{lifecycle.errorReason}
    </p>}
    <p>{evaluationText(evaluation)}</p>
    {available && evaluation?.degraded && <p>{tr("Evaluation is incomplete.")}{" "}{evaluation.errorReason}</p>}
    {evaluation?.version === 1 && Array.isArray(evaluation.rules) && <details><summary>{tr("Rule coverage and limitations")}</summary>
      {evaluation.rules.map(rule => <p key={rule.ruleId}>{rule.ruleId}: {rule.status}{rule.errorReason ? ` · ${rule.errorReason}` : ''}</p>)}
    </details>}
  </div>
  if (findings.length === 0) return <div ref={panel} tabIndex={-1} className="diagnostics-panel">
    {coverage}{available && <p className="diagnostics-empty-title">{tr("No findings from the evaluated rules")}</p>}
  </div>

  // Sort by severity: HIGH → MEDIUM → LOW
  const sorted = [...findings].sort((a, b) => {
    const order = { HIGH: 0, MEDIUM: 1, LOW: 2 }
    return order[a.severity] - order[b.severity]
  })

  return (
    <div ref={panel} tabIndex={-1} className="diagnostics-panel">
      {coverage}
      {sorted.map(finding => (
        <DiagnosticCard
          key={finding.id}
          finding={finding}
          onPlanEvidence={available && finding.planNodePath && canPlanEvidence?.(finding.planNodePath) ? onPlanEvidence : undefined}
          onVerify={available ? onVerify : undefined}
          isVerifying={verifyingIds?.has(finding.id) ?? false}
          verifyError={verifyErrors?.[finding.id]}
          onClearVerifyError={onClearVerifyError}
        />
      ))}
    </div>
  )
}
