import { tr, useLocale } from '../../i18n'
import { useState } from 'react'
import type { KeyValue, TraceStep, DiagnosticFinding, EliminationOutcome } from '../../types/diagnostics'
import type { UseRowTraceReturn } from '../../hooks/useRowTrace'
import './RowTracePanel.css'

interface RowTracePanelProps {
  fragmentTreeJson: string
  connectionId: string | null
  rowTrace: UseRowTraceReturn
  onLocateEvidence?: (id: string, sql: string) => void
  canLocateEvidence?: (id: string, sql: string) => boolean
}

const STATUS_ICONS: Record<string, string> = {
  HAS_ROWS: '✅',
  NO_ROWS: '❌',
  SKIPPED: '⏭️',
  ERROR: '⚠️',
  TIMEOUT: '⏰',
}

const REASON_LABELS: Record<string, string> = {
  KEY_NOT_PROJECTED: 'Key is absent from this output', AMBIGUOUS_KEY: 'Output key is ambiguous',
  NOT_EXECUTABLE: 'Fragment cannot run independently', STEP_ERROR: 'Observation failed',
  STEP_TIMEOUT: 'Observation timed out', CANCELLED: 'Observation cancelled',
}

function renderOutcomeSummary(outcome: NonNullable<UseRowTraceReturn['eliminationOutcome']>) {
  switch (outcome.outcomeType) {
    case 'EDGE': {
      // Union narrows to the EDGE variant: parentStep is non-optional.
      const parent = outcome.parentStep
      return (
        <div className="trace-elimination-summary">
          <strong>{tr("Observed difference:")}</strong> {outcome.childStep.fragmentLabel} {" "}{tr("found the key;")}{" "}{parent.fragmentLabel} {" "}{tr("did not.")}{" "}</div>
      )
    }
    case 'ERROR': {
      const step = outcome.step
      return (
        <div className="trace-outcome-error">
          <strong>{tr("Cannot determine elimination point:")}</strong> {outcome.reason}
          <div className="trace-outcome-error-step">
            {tr("Failed fragment:")}{" "}{step.fragmentLabel}
          </div>
        </div>
      )
    }
    case 'MISSING_AT_SOURCE': {
      const leaf = outcome.leafStep
      return (
        <div className="trace-outcome-missing">
          <strong>{tr("Current leaf did not find the key; source data is unverified:")}</strong> {leaf.fragmentLabel}
        </div>
      )
    }
    case 'NONE':
      return (
        <div className="trace-outcome-none">
          <strong>{tr("The root query found the target key.")}</strong> {" "}{tr("Existence tracing does not validate duplicates or values.")}{" "}</div>
      )
    case 'INCONCLUSIVE':
      return <div className="trace-outcome-none trace-outcome-inconclusive"><strong>{tr("Inconclusive:")}</strong> {outcome.reason} {" "}{tr("Continue expanding or choose a projected key.")}</div>
    default:
      return <div className="trace-outcome-none trace-outcome-unknown">{tr("Conclusion scope is unknown. Review the observed steps.")}</div>
  }
}

export default function RowTracePanel({ fragmentTreeJson, connectionId, rowTrace, onLocateEvidence, canLocateEvidence }: RowTracePanelProps) {
  useLocale()
  const [keys, setKeys] = useState<{ column: string; value: string; type: NonNullable<KeyValue['type']> }[]>([{ column: '', value: '', type: 'TEXT' }])
  const [mode, setMode] = useState<'EXISTENCE' | 'CARDINALITY'>('EXISTENCE')
  const [expected, setExpected] = useState('1')
  const [hasExpected, setHasExpected] = useState(true)
  const [inputError, setInputError] = useState<string | null>(null)

  const isRunning = rowTrace.status === 'QUEUED' || rowTrace.status === 'RUNNING'

  const handleTrace = () => {
    if (!connectionId || !fragmentTreeJson || rowTrace.blocked) return
    for (let i = 0; i < keys.length; i++) {
      const key = keys[i]
      if (!/^[a-zA-Z_][a-zA-Z0-9_.]*$/.test(key.column.trim()) ||
        key.type === 'DECIMAL' && (!/^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(key.value) || key.value.length > 256)) {
        setInputError(tr("Key {0}: enter a valid column and {1} value", i + 1, key.type.toLowerCase()))
        document.getElementById(`trace-column-${i}`)?.focus(); return
      }
    }
    if (mode === 'CARDINALITY' && hasExpected && (!/^\d{1,256}$/.test(expected) || BigInt(expected) > 9223372036854775807n)) {
      setInputError(tr("Expected maximum must be an unsigned integer up to 9223372036854775807")); return
    }
    setInputError(null)
    void rowTrace.trace(connectionId, fragmentTreeJson, keys.map(key => ({ column: key.column.trim(), type: key.type,
      value: key.type === 'NULL' ? null : key.type === 'BOOLEAN' ? key.value === 'true' : key.value })),
      { mode, ...(mode === 'CARDINALITY' ? { expectedMax: hasExpected ? expected : null } : {}) })
  }

  // Empty states
  if (!fragmentTreeJson) {
    return (
      <div className="row-trace-panel">
        <p className="placeholder-text">{tr("Parse SQL first to enable Row Trace")}</p>
      </div>
    )
  }
  if (!connectionId) {
    return (
      <div className="row-trace-panel">
        <p className="placeholder-text">{tr("Connect to a database first")}</p>
      </div>
    )
  }

  // Idle state — show input form
  if (rowTrace.status === 'idle') {
    return (
      <div className="row-trace-panel">
        <div className="row-trace-instructions">
          <h4>{tr("Expected Row Trace")}</h4><p className="storage-description">{tr("Enter a key projected by the fragments, for example emp_no = 10001. Existence checks presence; Cardinality checks the number of matching rows.")}</p>
          <p>{tr("Choose explicit output keys to investigate existence or quantity in the submitted exploration graph. Both modes support up to 64 unique fragments and 512 node occurrences, including the root.")}</p>
        </div>
        <div className="row-trace-form">
          <label>{tr("Investigation")}{" "}<select aria-label={tr("Investigation mode")} value={mode} onChange={e => setMode(e.target.value as typeof mode)}>
            <option value="EXISTENCE">{tr("Existence")}</option><option value="CARDINALITY">{tr("Quantity")}</option>
          </select></label>
          {keys.map((key, i) => <div className="trace-key-row" key={i}>
            <label>{tr("Key")}{" "}{i + 1} {" "}{tr("column")}<input id={`trace-column-${i}`} aria-label={tr("Key {0} column", i + 1)} placeholder={tr("Column (e.g., order_id)")}
              value={key.column} onChange={e => setKeys(prev => prev.map((k, j) => j === i ? { ...k, column: e.target.value } : k))} /></label>
            <label>{tr("Type")}<select aria-label={tr("Key {0} type", i + 1)} value={key.type} onChange={e => setKeys(prev => prev.map((k, j) => j === i ?
              { ...k, type: e.target.value as typeof key.type, value: e.target.value === 'BOOLEAN' ? 'true' : k.value } : k))}>
              {['TEXT', 'DECIMAL', 'BOOLEAN', 'NULL'].map(type => <option key={type}>{type}</option>)}
            </select></label>
            <label>{tr("Value")}{key.type === 'BOOLEAN' ? <select aria-label={tr("Key {0} value", i + 1)} value={key.value}
              onChange={e => setKeys(prev => prev.map((k, j) => j === i ? { ...k, value: e.target.value } : k))}>
              <option value="true">true</option><option value="false">false</option></select> :
              <input aria-label={tr("Key {0} value", i + 1)} placeholder={tr("Value (e.g., 12345)")} value={key.value} disabled={key.type === 'NULL'}
                onChange={e => setKeys(prev => prev.map((k, j) => j === i ? { ...k, value: e.target.value } : k))} />}</label>
            <button aria-label={tr("Remove key {0}", i + 1)} disabled={keys.length === 1} onClick={() => setKeys(prev => prev.filter((_, j) => j !== i))}>{tr("Remove")}</button>
          </div>)}
          <button onClick={() => setKeys(prev => [...prev, { column: '', value: '', type: 'TEXT' }])}>{tr("Add key")}</button>
          {mode === 'CARDINALITY' && <label><input type="checkbox" checked={hasExpected} onChange={e => setHasExpected(e.target.checked)} />{tr("Expected maximum")}{" "}<input aria-label={tr("Expected maximum")} value={expected} disabled={!hasExpected} onChange={e => setExpected(e.target.value)} /></label>}
          <span>{tr("Submitted fragments:")}{" "}{(() => { try {
            const ids = new Set<string>(); const walk = (n: { id: string; children?: unknown[] }) => { ids.add(n.id); n.children?.forEach(c => walk(c as typeof n)) }
            walk(JSON.parse(fragmentTreeJson)); return ids.size
          } catch { return 'unknown' } })()}</span>
          {inputError && <p role="alert">{inputError}</p>}
          <button onClick={handleTrace} disabled={keys.some(k => !k.column.trim()) || rowTrace.blocked}>
            {tr("Trace")}{" "}</button>
        </div>
      </div>
    )
  }

  // Running / completed / failed state — show progress
  return (
    <div className="row-trace-panel">
      <div className="row-trace-header">
        <h4 className="row-trace-title">
          {tr("Trace:")}{" "}{rowTrace.submittedKeys?.map(key => `${key.column} (${key.type ?? 'legacy'}) = ${JSON.stringify(key.value)}`).join(', ') ?? keys.map(k => `${k.column} = ${k.value}`).join(', ')}
        </h4>
        <div className="row-trace-actions">
          {rowTrace.errorReason === 'STATUS_UNAVAILABLE' && rowTrace.retryStatus &&
            <button className="trace-retry-btn" onClick={rowTrace.retryStatus}>{tr("Retry status")}</button>}
          {isRunning && (
            <button className="trace-cancel-btn" onClick={rowTrace.cancel}>
              {tr("Cancel")}{" "}</button>
          )}
          {!isRunning && (
            <button className="trace-retry-btn" disabled={rowTrace.blocked} onClick={() => {
              if (rowTrace.snapshot && !rowTrace.blocked) void rowTrace.trace(rowTrace.snapshot.connectionId,rowTrace.snapshot.fragmentTreeJson,rowTrace.snapshot.keys,rowTrace.snapshot.options)
              else handleTrace()
            }}>
              {tr("Re-trace")}{" "}</button>
          )}
          <button className="trace-clear-btn" onClick={rowTrace.clear} disabled={rowTrace.blocked}>
            {tr("Clear")}{" "}</button>
        </div>
      </div>

      <p className="trace-scope">{tr("Current submitted exploration graph only. Source data is unverified;")}{" "}{rowTrace.mode === 'CARDINALITY' ? tr("quantity is measured for the submitted output keys, without a shared snapshot guarantee.") : tr("existence does not validate duplicates or values, and separate observations have no guaranteed shared snapshot.")}</p>
      {rowTrace.traceId && <p>{tr("Trace")}{" "}{rowTrace.traceId}</p>}
      {rowTrace.snapshot && rowTrace.snapshot.fragmentTreeJson !== fragmentTreeJson && <p>{tr("Submitted snapshot only; newly expanded fragments are not included.")}</p>}
      {rowTrace.cancelSignal && <p data-error-reason={rowTrace.cancelSignal.errorReason ?? undefined}>{tr("Database cancellation signal:")}{" "}{rowTrace.cancelSignal.state}{tr(". This does not prove server execution stopped.")}</p>}
      {rowTrace.mode === 'CARDINALITY' && <div className="trace-quantity" aria-label={tr("Quantity evidence")}>
        <strong>{rowTrace.quantity?.verdict ?? 'UNKNOWN'}</strong>
        <p>{tr("Root count:")}{" "}{rowTrace.quantity?.rootCount ?? 'unknown'} {" "}{tr("· Expected maximum:")}{" "}{rowTrace.quantity?.expectedMax ?? tr("not declared")}</p>
        {rowTrace.quantity?.comparisons.map((comparison, i) => <div key={i}>
          <strong>{comparison.kind}</strong> · {comparison.parentFragmentId}
          <ul>{comparison.observations.map((o, j) => <li key={j}>{tr("Branch")}{" "}{o.ordinal ?? 'input'}: {o.fragmentId} {" "}{tr("· count")}{" "}{rowTrace.steps.find(s => s.fragmentId === o.fragmentId)?.count ?? 'unknown'}
            {o.reusedObservation && tr(" · reused observation")}</li>)}</ul>
          {comparison.limitations.map((l, j) => <p key={j}>{l.code}: {l.message}</p>)}
        </div>)}
      </div>}
      {rowTrace.evidence && <div className="trace-evidence" aria-label={tr("Trace evidence coverage")}>
        <strong>{rowTrace.evidence.coverage === 'PARTIAL' ? tr("Partial observations") : tr("All submitted nodes observed")}</strong>
        <span> — {rowTrace.evidence.observedNodes}/{rowTrace.evidence.totalNodes} {" "}{tr("nodes; root:")}{" "}{rowTrace.evidence.rootPresence}</span>
        {rowTrace.evidence.limitations.length > 0 && <details><summary>{tr("Investigation limits (")}{rowTrace.evidence.limitations.length})</summary>
          <ul>{rowTrace.evidence.limitations.map((limit, index) => <li key={index}>{limit.code}: {limit.message}</li>)}</ul>
        </details>}
      </div>}

      {/* Progress info */}
      <div className="trace-progress-info">
        <span className={`trace-status trace-status-${rowTrace.status.toLowerCase()}`}>
          {rowTrace.status}
        </span>
        {rowTrace.totalElapsedMs > 0 && (
          <span className="trace-elapsed">{rowTrace.totalElapsedMs}ms</span>
        )}
      </div>

      {/* Steps */}
      {rowTrace.steps.length > 0 && (
        <div className="row-trace-steps">
          {rowTrace.steps.map((step, i) => (
            <div
              key={i}
              className={`trace-step trace-step-${step.status.toLowerCase()} ${
                rowTrace.eliminationStep?.fragmentId === step.fragmentId ? 'elimination-point' : ''
              }`}
            >
              <div className="trace-step-index">
                {STATUS_ICONS[step.status] ?? '░'}
              </div>
              <div className="trace-step-content">
                <div className="trace-step-label">{step.fragmentLabel}</div>
                {step.count != null && <p>{tr("Count:")}{" "}{step.count} · {step.observedAt ?? tr("Observation time unknown")}</p>}
                {step.fragmentSql && <details><summary>{tr("Fragment SQL and key parameters")}</summary><pre>{step.fragmentSql}</pre><pre>{JSON.stringify(rowTrace.submittedKeys, null, 2)}</pre></details>}
                {onLocateEvidence && step.fragmentSql && <button className="trace-locate-btn" disabled={canLocateEvidence?.(step.fragmentId,step.fragmentSql) === false}
                  title={canLocateEvidence?.(step.fragmentId,step.fragmentSql) === false ? 'EVIDENCE_TARGET_STALE' : tr("Locate fragment without executing")}
                  onClick={() => onLocateEvidence(step.fragmentId,step.fragmentSql!)}>{tr("Locate fragment")}</button>}
                {step.sql && (
                  <code className="trace-step-sql">{step.sql}</code>
                )}
                <div className="trace-step-meta">
                  <span className="trace-step-status">{step.status}</span>
                  {step.elapsedMs > 0 && (
                    <span className="trace-step-elapsed">({step.elapsedMs}ms)</span>
                  )}
                  {step.error && (
                    <span className="trace-step-error">{step.error}</span>
                  )}
                  {step.reasonCode && <span className="trace-step-reason">{tr(REASON_LABELS[step.reasonCode] ?? step.reasonCode) ?? tr("Observation unavailable")} ({step.reasonCode}){step.unavailableKeys?.length ? `: ${step.unavailableKeys.join(', ')}` : ''}</span>}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Missing evidence or unknown historical payloads cannot establish a conclusion. */}
      {rowTrace.mode === 'CARDINALITY' ? null : rowTrace.eliminationOutcome && (rowTrace.evidence || rowTrace.eliminationOutcome.outcomeType === 'MISSING_AT_SOURCE') ? (
        renderOutcomeSummary(rowTrace.eliminationOutcome)
      ) : !isRunning ? (
        <div className="trace-elimination-summary">
          <strong>{tr("Conclusion scope is unknown.")}</strong> {" "}{tr("Review the observed steps.")}{" "}</div>
      ) : null}

      {/* Findings */}
      {rowTrace.findings.length > 0 && (
        <div className="trace-findings">
          <h5>{tr("Diagnostic Findings")}</h5>
          {rowTrace.findings.map((f, i) => (
            <div key={i} className={`trace-finding trace-finding-${f.severity.toLowerCase()}`}>
              <div className="finding-header">
                <span className="finding-severity">{f.severity}</span>
                <span className="finding-title">{f.title}</span>
              </div>
              <p className="finding-message">{f.degraded && tr("Analysis incomplete: ")}{f.message}</p>
            </div>
          ))}
        </div>
      )}

      {/* Global error */}
      {rowTrace.error && (
        <div className="trace-error-banner">
          {rowTrace.error}
        </div>
      )}
    </div>
  )
}
