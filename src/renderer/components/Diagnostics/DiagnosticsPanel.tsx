import { tr, useLocale } from '../../i18n'
import type { Evaluation } from '../../types/execution'
import { evaluationText } from '../../utils/planMetrics'
import type { DiagnosticFinding } from '../../types/diagnostics'
import DiagnosticCard from './DiagnosticCard'
import './DiagnosticsPanel.css'

interface DiagnosticsPanelProps {
  evaluation?: Evaluation | null
  findings: DiagnosticFinding[]
  loading: boolean
  error: string | null
  onVerify?: (findingId: string, verificationSql: string) => void
  verifyingIds?: Set<string>
  verifyErrors?: Record<string, string>
  onClearVerifyError?: (findingId: string) => void
}

export default function DiagnosticsPanel({ evaluation, findings, loading, error, onVerify, verifyingIds, verifyErrors, onClearVerifyError }: DiagnosticsPanelProps) {
  useLocale()
  if (loading) {
    return (
      <div className="diagnostics-panel diagnostics-loading">
        <span>{tr("Analyzing with 7 diagnostic rules...")}</span>
      </div>
    )
  }

  if (error) {
    return (
      <div className="diagnostics-panel diagnostics-error">
        <span>{tr("Analysis failed:")}{" "}{error}</span>
      </div>
    )
  }

  const coverage = <div className="diagnostics-coverage" role="status">
    <p>{evaluationText(evaluation)}</p>
    {evaluation?.version === 1 && Array.isArray(evaluation.rules) && <details><summary>{tr("Rule coverage and limitations")}</summary>
      {evaluation.rules.map(rule => <p key={rule.ruleId}>{rule.ruleId}: {rule.status}{rule.errorReason ? ` · ${rule.errorReason}` : ''}</p>)}
    </details>}
  </div>
  if (findings.length === 0) return <div className="diagnostics-panel">
    {coverage}<p className="diagnostics-empty-title">{tr("No findings from the evaluated rules")}</p>
  </div>

  // Sort by severity: HIGH → MEDIUM → LOW
  const sorted = [...findings].sort((a, b) => {
    const order = { HIGH: 0, MEDIUM: 1, LOW: 2 }
    return order[a.severity] - order[b.severity]
  })

  return (
    <div className="diagnostics-panel">
      {coverage}
      {sorted.map(finding => (
        <DiagnosticCard
          key={finding.id}
          finding={finding}
          onVerify={onVerify}
          isVerifying={verifyingIds?.has(finding.id) ?? false}
          verifyError={verifyErrors?.[finding.id]}
          onClearVerifyError={onClearVerifyError}
        />
      ))}
    </div>
  )
}
