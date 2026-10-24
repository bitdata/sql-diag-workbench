import { tr, useLocale } from '../../i18n'
import { useLayoutEffect, useRef, useState } from 'react'
import type { KeyValue } from '../../types/diagnostics'
import { useRowTrace } from '../../hooks/useRowTrace'
import { apiFetch } from '../../utils/api'

interface Props { backendUrl: string; connectionId: string; fragmentTreeJson: string; keys: KeyValue[] }
interface Plan { status: string; verified: boolean; errorReason?: string; connectionId: string; originalSql: string;
  metadataSql: string; schema: string; relation: string; sourceSql: string; keys: KeyValue[]; observedAt: string }

export default function PhysicalSourcePanel({ backendUrl, connectionId, fragmentTreeJson, keys }: Props) {
  useLocale()
  const trace = useRowTrace(backendUrl, connectionId)
  const [plan, setPlan] = useState<Plan | null>(null), [preparing, setPreparing] = useState(false)
  const [error, setError] = useState<string | null>(null), [cancelledPreparation, setCancelledPreparation] = useState(false)
  const generation = useRef(0), request = useRef<AbortController | null>(null)
  const reset = () => { generation.current++; request.current?.abort(); request.current = null; setPreparing(false); setPlan(null); setError(null); setCancelledPreparation(false); trace.clear() }
  useLayoutEffect(() => { reset(); return () => { generation.current++; request.current?.abort() } }, [connectionId, fragmentTreeJson, JSON.stringify(keys)])
  const run = async () => {
    reset(); const version = generation.current, controller = new AbortController(); request.current = controller; setPreparing(true)
    try {
      const root = JSON.parse(fragmentTreeJson)
      if (typeof root?.sqlFragment !== 'string') throw new Error('SOURCE_SQL_UNAVAILABLE')
      const response = await apiFetch(backendUrl + '/api/diagnostics/physical-source-plan', { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, signal: controller.signal,
        body: JSON.stringify({ connectionId, sql: root.sqlFragment, keys }) })
      if (generation.current !== version) return
      if (!response.ok) throw new Error('SOURCE_PLAN_UNAVAILABLE')
      const data = await response.json() as Plan
      if (generation.current !== version) return
      if (data.status !== 'COMPLETED' || data.verified !== true) throw new Error(data.errorReason || 'SOURCE_UNVERIFIED')
      if (data.errorReason || data.connectionId !== connectionId || data.originalSql !== root.sqlFragment ||
        !data.sourceSql || !data.schema || !data.relation || !data.metadataSql || !data.observedAt || !Array.isArray(data.keys) || data.keys.length !== keys.length ||
        data.keys.some((key, i) => !key.column || key.type !== keys[i].type || JSON.stringify(key.value) !== JSON.stringify(keys[i].value))) throw new Error('SOURCE_PLAN_INVALID')
      setPlan(data); setPreparing(false)
      const tree = { id: crypto.randomUUID(), type: 'ORIGINAL', label: tr("Verified physical source"), sqlFragment: data.sourceSql,
        executable: true, nodeCount: 0, children: [], parentIds: [] }
      await trace.trace(connectionId, JSON.stringify(tree), data.keys, { mode: 'CARDINALITY', queryTimeoutSeconds: 5 })
    } catch (failure) {
      if (generation.current === version) setError(failure instanceof Error ? failure.message : 'SOURCE_PLAN_FAILED')
    } finally { if (generation.current === version) { request.current = null; setPreparing(false) } }
  }
  const count = plan && !error && !trace.error && trace.status === 'COMPLETED' && trace.quantity?.verdict === 'OBSERVATION_ONLY' ? trace.quantity.rootCount : null
  return <details className="trace-evidence physical-source-verification">
    <summary>{tr("Check physical source")}</summary>
    <p>{tr("Resolve the submitted query's direct key columns to a physical table, then check that table independently.")}</p>
    <div className="row-trace-form">
    <button onClick={() => void run()} disabled={preparing || !!trace.blocked}>{tr("Check query source")}</button>
    {preparing || trace.blocked ? <button onClick={() => {
      if (preparing) { reset(); setCancelledPreparation(true) } else void trace.cancel()
    }}>{tr("Cancel physical source check")}</button> : null}
    {trace.errorReason === 'STATUS_UNAVAILABLE' && <button onClick={trace.retryStatus}>{tr("Retry physical source status")}</button>}
    </div>
    <div role="status" aria-label={tr("Physical source observation")} data-error-reason={error ?? trace.errorReason ?? undefined}>
      {count != null ? <>{count === '0' ? tr("No visible match in verified physical source") : tr("Verified physical source has a visible match")} {" "}{tr("· Count:")}{" "}{count}</> :
        <>{tr("Physical source:")}{" "}{preparing ? 'PREPARING' : trace.status} · UNKNOWN{error || trace.error ? `: ${error ?? trace.errorReason ?? trace.error}` : ''}</>}
      {cancelledPreparation && <p>{tr("Stopped waiting for source preparation. The server request may still be completing; no source observation is accepted.")}</p>}
      {plan && <><p>{tr("Physical table:")}{" "}{plan.schema}.{plan.relation} {" "}{tr("· Connection:")}{" "}{plan.connectionId} {" "}{tr("· Metadata observed:")}{" "}{plan.observedAt}</p>
        <pre>{plan.sourceSql}</pre><pre>{JSON.stringify(plan.keys, null, 2)}</pre>
        <details><summary>{tr("Source resolution SQL")}</summary><pre>{plan.originalSql}</pre><pre>{plan.metadataSql}</pre></details></>}
      {trace.steps.map(step => <p key={step.fragmentId}>{step.reasonCode ?? step.status} · {step.observedAt ?? tr("Observation time unavailable")}</p>)}
      {trace.cancelSignal && <p>{tr("Database cancellation signal:")}{" "}{trace.cancelSignal.state}{tr(". Server execution may still be running.")}</p>}
    </div>
    <p>{tr("Only directly projected keys from one physical table are verified. Views, joins, CTEs, set operations and expression keys remain UNKNOWN. This connection's permissions and row-level security still apply. Separate observations have no guaranteed shared snapshot. A source match does not prove that the original query should return the row.")}</p>
  </details>
}
