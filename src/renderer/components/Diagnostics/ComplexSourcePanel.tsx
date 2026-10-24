import { tr, useLocale } from '../../i18n'
import { useLayoutEffect, useRef, useState } from 'react'
import type { KeyValue } from '../../types/diagnostics'
import { useRowTrace } from '../../hooks/useRowTrace'
import { apiFetch } from '../../utils/api'

interface Props { backendUrl: string; connectionId: string; fragmentTreeJson: string; keys: KeyValue[] }
interface Source { occurrence: string; schema: string; relation: string; path: string[]; sourceSql: string; keys: KeyValue[];
  mappings: { keyIndex: number; outputColumn: string; sourceExpression: string }[] }
interface Plan { status: string; verified: boolean; errorReason?: string; connectionId: string; originalSql: string; metadataSql: string;
  sources: Source[]; issues: { occurrence?: string; keyIndex?: number; errorReason: string; schema?: string; relation?: string; path?: string[] }[]; observedAt: string }

function SourceObservation({ backendUrl, connectionId, source }: { backendUrl: string; connectionId: string; source: Source }) {
  useLocale()
  const trace = useRowTrace(backendUrl, source.occurrence)
  const [started, setStarted] = useState(false)
  const count = started && !trace.error && trace.status === 'COMPLETED' && trace.quantity?.verdict === 'OBSERVATION_ONLY' ? trace.quantity.rootCount : null
  const run = () => {
    setStarted(true)
    const tree = { id: crypto.randomUUID(), type: 'ORIGINAL', label: tr("Mapped physical source"), sqlFragment: source.sourceSql,
      executable: true, nodeCount: 0, children: [], parentIds: [] }
    void trace.trace(connectionId, JSON.stringify(tree), source.keys, { mode: 'CARDINALITY', queryTimeoutSeconds: 5 })
  }
  return <section aria-label={tr("Source {0}", source.occurrence)}>
    <p>{source.occurrence} · {source.schema}.{source.relation}</p>
    <div className="row-trace-form">
      <button onClick={run} disabled={!!trace.blocked}>{tr("Observe")}{" "}{source.occurrence}</button>
      {trace.blocked && <button onClick={() => void trace.cancel()}>{tr("Cancel")}{" "}{source.occurrence}</button>}
      {trace.errorReason === 'STATUS_UNAVAILABLE' && <button onClick={trace.retryStatus}>{tr("Retry")}{" "}{source.occurrence} status</button>}
    </div>
    <div role="status" aria-label={tr("Observation {0}", source.occurrence)}>
      {count != null ? `${count === '0' ? tr("No visible match in this physical source") : tr("This physical source has a visible match")} · Count: ${count}` :
        tr("Source: {0} · UNKNOWN{1}", trace.status, trace.error ? ': ' + (trace.errorReason ?? trace.error) : '')}
      {trace.steps.map(step => <p key={step.fragmentId}>{step.reasonCode ?? step.status} · {step.observedAt ?? tr("Observation time unavailable")}</p>)}
      {trace.cancelSignal && <p>{tr("Database cancellation signal:")}{" "}{trace.cancelSignal.state}{tr(". Server execution may still be running.")}</p>}
    </div>
    <details><summary>{tr("Mapping and source SQL for")}{" "}{source.occurrence}</summary>
      <p>{source.path.join(' → ')}</p><pre>{source.sourceSql}</pre><pre>{JSON.stringify(source.mappings, null, 2)}</pre><pre>{JSON.stringify(source.keys, null, 2)}</pre>
    </details>
  </section>
}

export default function ComplexSourcePanel({ backendUrl, connectionId, fragmentTreeJson, keys }: Props) {
  useLocale()
  const [plan, setPlan] = useState<Plan | null>(null), [preparing, setPreparing] = useState(false)
  const [error, setError] = useState<string | null>(null), [stopped, setStopped] = useState(false)
  const generation = useRef(0), request = useRef<AbortController | null>(null)
  const reset = () => { generation.current++; request.current?.abort(); request.current = null; setPlan(null); setPreparing(false); setError(null); setStopped(false) }
  useLayoutEffect(() => { reset(); return () => { generation.current++; request.current?.abort() } }, [backendUrl, connectionId, fragmentTreeJson, JSON.stringify(keys)])
  const resolve = async () => {
    reset(); const version = generation.current, controller = new AbortController(); request.current = controller; setPreparing(true)
    try {
      const root = JSON.parse(fragmentTreeJson)
      if (typeof root?.sqlFragment !== 'string') throw new Error('SOURCE_SQL_UNAVAILABLE')
      const response = await apiFetch(backendUrl + '/api/diagnostics/physical-sources-plan', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        signal: controller.signal, body: JSON.stringify({ connectionId, sql: root.sqlFragment, keys }) })
      if (generation.current !== version) return
      if (!response.ok) throw new Error('SOURCE_PLAN_UNAVAILABLE')
      const data = await response.json() as Plan
      if (generation.current !== version) return
      if (data.status === 'UNKNOWN') throw new Error(data.issues?.map(i => i.errorReason).join(', ') || data.errorReason || 'SOURCE_UNVERIFIED')
      if (!['COMPLETED', 'PARTIAL'].includes(data.status) || data.connectionId !== connectionId || data.originalSql !== root.sqlFragment || !data.metadataSql || !data.observedAt ||
        !Array.isArray(data.issues) || (data.status === 'COMPLETED' ? !data.verified || !!data.errorReason || data.issues.length !== 0 : data.verified || data.errorReason !== 'SOURCE_COVERAGE_INCOMPLETE' || data.issues.length === 0) ||
        !Array.isArray(data.sources) || data.sources.length === 0 || data.sources.length > 64 || new Set(data.sources.map(s => s.occurrence)).size !== data.sources.length ||
        data.sources.some(s => !s.occurrence || !s.schema || !s.relation || !s.sourceSql || !Array.isArray(s.path) || !s.path.length || !Array.isArray(s.keys) || !s.keys.length ||
          !Array.isArray(s.mappings) || s.keys.length !== s.mappings.length || new Set(s.mappings.map(m => m.keyIndex)).size !== s.mappings.length ||
          s.mappings.some((m, i) => !Number.isInteger(m.keyIndex) || m.keyIndex < 0 || m.keyIndex >= keys.length || !m.sourceExpression || !m.outputColumn ||
            s.keys[i].column !== `source_key_${m.keyIndex}` || s.keys[i].type !== keys[m.keyIndex].type || JSON.stringify(s.keys[i].value) !== JSON.stringify(keys[m.keyIndex].value)))) throw new Error('SOURCE_PLAN_INVALID')
      setPlan(data)
    } catch (failure) { if (generation.current === version) setError(failure instanceof Error ? failure.message : 'SOURCE_PLAN_FAILED') }
    finally { if (generation.current === version) { request.current = null; setPreparing(false) } }
  }
  return <details className="trace-evidence physical-source-verification">
    <summary>{tr("Inspect complex query sources")}</summary>
    <p>{tr("Follow output keys through joins, CTEs, derived tables, readable views and set branches. Observe each mapped physical source independently.")}</p>
    <div className="row-trace-form">
      <button onClick={() => void resolve()} disabled={preparing}>{tr("Resolve query sources")}</button>
      {preparing && <button onClick={() => { reset(); setStopped(true) }}>{tr("Cancel source resolution")}</button>}
      {plan && <button onClick={reset}>{tr("Clear source observations")}</button>}
    </div>
    <div role="status" aria-label={tr("Complex source coverage")}>
      {preparing ? tr("PREPARING · UNKNOWN") : error ? `UNKNOWN: ${error}` : plan ?
        `${plan.status === 'PARTIAL' ? tr("Partial coverage · UNKNOWN for unmapped relations or keys") : tr("Submitted keys mapped")} · ${plan.sources.length} independent source observations available` : tr("UNKNOWN · No source resolution accepted")}
      {stopped && <p>{tr("Stopped waiting. The server request may still be completing; no source observation is accepted.")}</p>}
      {plan?.issues.map((issue, i) => <p key={i}>{issue.occurrence ?? (issue.keyIndex == null ? tr("Query") : tr("Key {0}", keys[issue.keyIndex]?.column))}{issue.relation ? ` · ${issue.schema}.${issue.relation}` : ''} {" "}{tr("· UNKNOWN:")}{" "}{issue.errorReason}</p>)}
    </div>
    {plan && <><p>{tr("Connection:")}{" "}{plan.connectionId} {" "}{tr("· Metadata observed:")}{" "}{plan.observedAt}</p>
      <details><summary>{tr("Complex source resolution SQL")}</summary><pre>{plan.originalSql}</pre><pre>{plan.metadataSql}</pre></details>
      {plan.sources.map(source => <SourceObservation key={generation.current + ':' + source.occurrence} backendUrl={backendUrl} connectionId={connectionId} source={source} />)}</>}
    <p>{tr("Counts apply to each source occurrence and its mapped keys only. Cross-source keys are observed separately; counts are not added and do not prove a join match or explain the whole query. Scalar keys are evaluated at the source, without reversing the expression. Source filters are removed, including view filters. Current permissions and row-level security apply; observations do not share a guaranteed snapshot.")}</p>
  </details>
}
