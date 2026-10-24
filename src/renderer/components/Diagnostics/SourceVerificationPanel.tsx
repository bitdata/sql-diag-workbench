import { tr, useLocale } from '../../i18n'
import { useLayoutEffect, useRef, useState } from 'react'
import type { KeyValue } from '../../types/diagnostics'
import { apiFetch } from '../../utils/api'
import { sourceKeys, sourceSql } from '../../utils/sourceVerification'
import { useRowTrace } from '../../hooks/useRowTrace'

interface Props { backendUrl: string; connectionId: string; keys: KeyValue[] }

/** An independent observation of a user-declared relation, never inferred from DAG leaves. */
export default function SourceVerificationPanel({ backendUrl, connectionId, keys }: Props) {
  useLocale()
  const [schema, setSchema] = useState(''), [relation, setRelation] = useState('')
  const [columns, setColumns] = useState(() => keys.map(k => k.column.split('.').pop()!))
  const [preparing, setPreparing] = useState(false), [error, setError] = useState<string | null>(null)
  const trace = useRowTrace(backendUrl, connectionId)
  const request = useRef<AbortController | null>(null), generation = useRef(0)
  useLayoutEffect(() => () => { generation.current++; request.current?.abort() }, [])
  const reset = () => { generation.current++; request.current?.abort(); request.current = null; setPreparing(false); setError(null); trace.clear() }
  const run = async () => {
    reset()
    const version = generation.current, controller = new AbortController(); request.current = controller
    setPreparing(true)
    try {
      const mapped = sourceKeys(keys, columns)
      const response = await apiFetch(backendUrl + '/api/connections/' + encodeURIComponent(connectionId), { signal: controller.signal })
      if (generation.current !== version) return
      if (!response.ok) throw new Error('SOURCE_CONNECTION_UNAVAILABLE')
      const config = await response.json()
      if (generation.current !== version) return
      if (config.id !== connectionId) throw new Error('SOURCE_CONNECTION_MISMATCH')
      const sql = sourceSql(config.dialect, schema, relation)
      const tree = { id: crypto.randomUUID(), type: 'ORIGINAL', label: tr("Declared source relation"), sqlFragment: sql,
        executable: true, nodeCount: 0, children: [], parentIds: [] }
      await trace.trace(connectionId, JSON.stringify(tree), mapped, { mode: 'CARDINALITY', queryTimeoutSeconds: 5 })
    } catch (failure) {
      if (generation.current === version) setError(failure instanceof Error ? failure.message : 'SOURCE_PREPARATION_FAILED')
    } finally {
      if (generation.current === version) { request.current = null; setPreparing(false) }
    }
  }
  const count = trace.status === 'COMPLETED' && !trace.error && trace.quantity?.verdict === 'OBSERVATION_ONLY' ? trace.quantity.rootCount : null
  const busy = preparing || trace.blocked
  return <details className="trace-evidence source-verification">
    <summary>{tr("Check declared source")}</summary>
    <p>{tr("Declare a source relation and review each source column. This does not infer lineage from the query.")}</p>
    <div className="row-trace-form">
      <label>{tr("Schema (optional)")}<input aria-label={tr("Source schema")} value={schema} onChange={e => { reset(); setSchema(e.target.value) }} /></label>
      <label>{tr("Relation")}<input aria-label={tr("Source relation")} value={relation} onChange={e => { reset(); setRelation(e.target.value) }} placeholder="sales_invoice_item" /></label>
      <p>{tr("Names use 1–63 ASCII letters, digits or underscores, starting with a letter or underscore. Use exact case.")}</p>
      {keys.map((key, i) => <label key={i}>{tr("Source column")}{" "}{i + 1}
        <input aria-label={tr("Source column {0}", i + 1)} value={columns[i]} onChange={e => { reset(); setColumns(prev => prev.map((c, j) => j === i ? e.target.value : c)) }} />
        <span>{key.type ?? 'legacy'} = {JSON.stringify(key.value)}</span>
      </label>)}
      <button onClick={() => void run()} disabled={!relation || !!busy}>{tr("Check source relation")}</button>
      {busy && <button onClick={() => { if (preparing) reset(); else void trace.cancel() }}>{tr("Cancel source check")}</button>}
      {trace.errorReason === 'STATUS_UNAVAILABLE' && <button onClick={trace.retryStatus}>{tr("Retry source status")}</button>}
    </div>
    <div role="status" aria-label={tr("Source observation")} data-error-reason={error ?? trace.errorReason ?? undefined}>
      {error || trace.error ? <>{trace.status} {" "}{tr("· UNKNOWN:")}{" "}{error ?? trace.errorReason ?? trace.error}</> : count != null ?
        <>{count === '0' ? tr("No visible match in declared relation") : tr("Declared relation has a visible match")} {" "}{tr("· Count:")}{" "}{count}</> :
        <>{tr("Source observation:")}{" "}{preparing ? 'PREPARING' : trace.status} · UNKNOWN</>}
      {trace.steps.map(step => <p key={step.fragmentId}>{step.reasonCode ?? step.status} · {step.observedAt ?? tr("Observation time unavailable")}</p>)}
      {trace.snapshot && <><p>{tr("Connection:")}{" "}{trace.snapshot.connectionId}</p><pre>{Object.values(trace.snapshot.sqlById).join('\n')}</pre><pre>{JSON.stringify(trace.snapshot.keys, null, 2)}</pre></>}
      {trace.cancelSignal && <p>{tr("Database cancellation signal:")}{" "}{trace.cancelSignal.state}{tr(". Server execution may still be running.")}</p>}
    </div>
    <p>{tr("Only rows visible to this connection in the declared relation are checked. Views, row-level security and permissions can hide data. Source mapping is user-declared; separate observations have no guaranteed shared snapshot. This does not prove absence from every physical source. Each statement has a 5-second timeout.")}</p>
  </details>
}
