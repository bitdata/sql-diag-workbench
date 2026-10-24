import { tr, useLocale } from '../../i18n'
import type { DiagnosticFinding, FindingStatus, Severity } from '../../types/diagnostics'
import './DiagnosticCard.css'

interface DiagnosticCardProps {
  finding: DiagnosticFinding
  onVerify?: (findingId: string, verificationSql: string) => void
  isVerifying?: boolean
  verifyError?: string
  onClearVerifyError?: (findingId: string) => void
}

const SEVERITY_COLORS: Record<Severity, string> = {
  HIGH: 'var(--error)',
  MEDIUM: 'var(--warning)',
  LOW: 'var(--accent)'
}

const STATUS_CONFIG: Record<FindingStatus, { label: string; color: string; icon: string }> = {
  SUSPECTED: { get label() { return tr("Suspected") }, color: 'var(--warning)', icon: '⚠' },
  CONFIRMED: { get label() { return tr("Confirmed") }, color: 'var(--error)', icon: '✗' },
  REFUTED:   { get label() { return tr("Refuted") }, color: 'var(--text-muted)', icon: '✓' },
}

export default function DiagnosticCard({ finding, onVerify, isVerifying, verifyError, onClearVerifyError }: DiagnosticCardProps) {
  useLocale()
  const statusConfig = STATUS_CONFIG[finding.status]

  return (
    <div className="diagnostic-card" style={{ borderLeftColor: SEVERITY_COLORS[finding.severity] }}>
      <div className="diagnostic-card-header">
        <span className="diagnostic-severity-badge" style={{ background: SEVERITY_COLORS[finding.severity] }}>
          {finding.severity}
        </span>
        <span className="diagnostic-status-badge" style={{ background: statusConfig.color, color: '#fff', borderRadius: '4px', padding: '1px 6px', fontSize: '0.75rem' }}>
          {finding.degraded ? tr("Analysis incomplete") : `${statusConfig.icon} ${statusConfig.label}`}
        </span>
        <span className="diagnostic-title">{finding.title}</span>
      </div>
      <p className="diagnostic-message">{finding.message}</p>
      {finding.evidence.length > 0 && (
        <ul className="diagnostic-evidence">
          {finding.evidence.map((e, i) => (
            <li key={i}>{e}</li>
          ))}
        </ul>
      )}
      <div className="diagnostic-footer">
        {!finding.degraded && <div className="diagnostic-confidence">
          <span>{tr("Confidence:")}</span>
          <div className="confidence-bar">
            <div className="confidence-fill" style={{ width: `${finding.confidence * 100}%` }} />
          </div>
          <span>{Math.round(finding.confidence * 100)}%</span>
        </div>}
        {finding.verificationSql && !finding.degraded && (
          <div className="diagnostic-verify">
            <button
              className="verify-button"
              onClick={() => {
                onClearVerifyError?.(finding.id)
                onVerify?.(finding.id, finding.verificationSql!)
              }}
              disabled={isVerifying || finding.status !== 'SUSPECTED'}
            >
              {isVerifying ? tr("Verifying...") : tr("Verify")}
            </button>
            <code className="diagnostic-sql">{finding.verificationSql}</code>
            {finding.verificationResult && (
              <p className="verification-result">
                {finding.verificationResult}
              </p>
            )}
            {verifyError && (
              <p className="verify-error">{verifyError}</p>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
