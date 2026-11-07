import { useLayoutEffect, useRef, useState, type MutableRefObject } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { tr, useLocale } from '../../i18n'
import { apiFetch } from '../../utils/api'
import { decodeGeneration, decodeOptimizationReport } from '../../utils/optimizationProtocol'
import { decodePerformanceExperiment } from '../../utils/performanceExperimentProtocol'
import { createTabIntent, type TabCreationIntent } from '../../hooks/useTabs'
import { useRemoteTask } from '../../hooks/useRemoteTask'
import type { ExperimentReport, Generation, Report, ReportPage, Source } from '../../types/optimization'
import type { SqlDialect } from '../../types/session'
import './OptimizationPanel.css'

interface Props {
  blockersRef?: MutableRefObject<() => string[]>
  backendUrl: string | null; sessionId: string | null; sql: string; connectionId: string | null
  dialect: SqlDialect; revision: string; connectionRevision: string; disabled?: boolean
  onAdopt: (sql: string, source: Source, intent: TabCreationIntent, onError: (error: string) => void) => Promise<string | null>
}
const conditionText = (code: string) => {
  const labels: Record<string, string> = {
    SUPPORTED_LOCAL_TABLES: 'Supported local tables', BUILTIN_EXACT_TYPES: 'Exact supported column types',
    DETERMINISTIC_SUPPORTED_QUERY: 'Supported deterministic query', SAME_SNAPSHOT_VALIDATION_REQUIRED: 'Shared snapshot required',
    NO_SPEEDUP_CLAIM: 'Performance improvement has not been measured',
  }
  return tr(labels[code] ?? 'An additional condition requires verification')
}
const ruleText = (id: string) => tr(id === 'positive-in-exists' ? 'Positive IN to EXISTS' : id === 'count-star-exists' ? 'COUNT(*) > 0 to EXISTS' : 'Candidate rewrite')
const verdictText = (r: Report) => r.verdict === 'MATCH' ? tr('Complete results match in this snapshot') : r.verdict === 'DIFFERENT' ? tr('Complete results differ') : r.verdict === 'INCONCLUSIVE' ? tr('Inconclusive') : tr('Not evaluated')

export default function OptimizationPanel(props: Props) {
  useLocale()
  const [open, setOpen] = useState(false), [confirm, setConfirm] = useState(false)
  const [generation, setGeneration] = useState<Generation | null>(null), [draft, setDraft] = useState(''), [candidateId, setCandidateId] = useState<string | null>(null)
  const [report, setReport] = useState<Report | null>(null), [history, setHistory] = useState<Report | null>(null), [page, setPage] = useState<ReportPage | null>(null)
  const [experimentPage, setExperimentPage] = useState<{ items: { version: number; experimentId: string; completedAt: string; resultVerdict: string; comparisonState: string }[]; nextCursor: string | null } | null>(null)
  const [experimentHistorical, setExperimentHistorical] = useState(false)
  const [experiment, setExperiment] = useState<ExperimentReport | null>(null)
  const [experimentSeed, setExperimentSeed] = useState('42'), [experimentTimeout, setExperimentTimeout] = useState('120')
  const [busy, setBusy] = useState(false), [adopting, setAdopting] = useState(false), [error, setError] = useState<string | null>(null)
  const scope = JSON.stringify([props.backendUrl, props.sessionId, props.sql, props.connectionId, props.dialect, props.revision, props.connectionRevision])
  const adoptButton = useRef<HTMLButtonElement>(null)
  const adoptionIntent = useRef<TabCreationIntent | null>(null)
  const epoch = useRef(0), currentScope = useRef(scope), evidence = useRef<HTMLDivElement>(null)
  currentScope.current = scope
  const task = useRemoteTask<Report>(props.backendUrl, scope, decodeOptimizationReport)
  const experimentTask = useRemoteTask<ExperimentReport>(props.backendUrl, scope, decodePerformanceExperiment)
  const experimentBusy = experimentTask.loading || experimentTask.submitting || experimentTask.cancelling || ['CANCEL_UNCONFIRMED','STATUS_UNAVAILABLE','SUBMISSION_UNCONFIRMED'].includes(experimentTask.errorReason ?? '')
  const active = task.loading || task.submitting || task.cancelling || task.errorReason === 'CANCEL_UNCONFIRMED' || task.errorReason === 'STATUS_UNAVAILABLE' || experimentBusy
  const valid = !props.disabled && !!generation && !!report && !history && !active && !task.error && report.status === 'COMPLETED' && report.verdict === 'MATCH' && report.snapshot?.state === 'SHARED_RR' && report.original?.complete && report.candidate?.complete && report.candidateSql === draft && JSON.stringify(report.source) === JSON.stringify(generation.source) && report.source.sql === props.sql
  if (props.blockersRef) props.blockersRef.current = () => active || adopting ? [tr('Optimization validation or adoption is still pending.')] : open && generation && draft !== (generation.candidates.find(c => c.id === candidateId)?.sql ?? props.sql) ? [tr('An optimization candidate draft is open. Close it before exiting.')] : []
  useLayoutEffect(() => () => { if (props.blockersRef) props.blockersRef.current = () => [] }, [])
  const invalidate = () => { adoptionIntent.current = null; task.reset(); experimentTask.reset(); setReport(null); setHistory(null); setExperiment(null); setConfirm(false) }
  useLayoutEffect(() => {
    epoch.current++; adoptionIntent.current = null; setGeneration(null); setDraft(''); setCandidateId(null); setReport(null); setHistory(null); setExperiment(null); setExperimentPage(null); setExperimentHistorical(false); setPage(null); setConfirm(false); setBusy(false); setError(open ? tr('Source changed. Discover candidates again.') : null)
    return () => { epoch.current++ }
  }, [scope])
  useLayoutEffect(() => { if (report && ['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(report.status)) evidence.current?.focus() }, [report])
  const request = async (path: string, body?: unknown) => {
    const res = await apiFetch(props.backendUrl + path, body === undefined ? undefined : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    const data: unknown = await res.json()
    if (!res.ok) throw new Error((data as { errorReason?: string })?.errorReason ?? `HTTP ${res.status}`)
    return data
  }
  const discover = async () => {
    if (!props.backendUrl || !props.sessionId || !props.connectionId || props.disabled) return
    invalidate(); setGeneration(null); setError(null);setBusy(true); const version = ++epoch.current
    try {
      const data = decodeGeneration(await request('/api/optimization/candidates', { sessionId: props.sessionId, sourceRevision: props.revision, sql: props.sql, connectionId: props.connectionId, dialect: props.dialect }))
      if (version !== epoch.current) return
      setGeneration(data);setDraft(data.candidates[0]?.sql ?? props.sql);setCandidateId(data.candidates[0]?.id ?? null)
    } catch (e) { if (version === epoch.current) setError(String(e)) }
    finally { if (version === epoch.current) setBusy(false) }
  }
  const show = (next: boolean) => {
    setOpen(next)
    if (next && !generation) void discover()
    if (!next) { epoch.current++;setBusy(false);setConfirm(false);task.reset();experimentTask.reset() }
  }
  const validate = () => {
    if (!generation || active || props.disabled) return
    setReport(null);setHistory(null);setError(null);setConfirm(false)
    const version = epoch.current, sql = draft
    void task.submit('/api/optimization/validations', '/api/optimization/validations', 'validationId', { source: generation.source, candidateSql: sql, candidateId, timeoutSeconds: 30 }, data => {
      if (version === epoch.current && data.candidateSql === sql && JSON.stringify(data.source) === JSON.stringify(generation.source)) setReport(data)
    })
  }
  const adopt = async () => {
    if (!valid || !generation || adopting) return
    setAdopting(true);setError(null);const captured = scope, version = epoch.current
    try {
      const fresh = decodeGeneration(await request('/api/optimization/candidates', { sessionId: props.sessionId, sourceRevision: props.revision, sql: props.sql, connectionId: props.connectionId, dialect: props.dialect }))
      if (currentScope.current !== captured || version !== epoch.current || JSON.stringify(fresh.source) !== JSON.stringify(generation.source)) throw new Error('SOURCE_CHANGED')
      adoptionIntent.current ??= createTabIntent(draft, { connectionId: generation.source.connectionId, dialect: generation.source.dialect, name: tr('Optimization candidate') })
      const id = await props.onAdopt(draft, generation.source, adoptionIntent.current, setError)
      if (id) show(false)
    } catch (e) { if (version === epoch.current) setError(String(e)) }
    finally { setAdopting(false);setConfirm(false) }
  }
  const loadHistory = async (cursor?: string | null) => {
    const version = epoch.current;setError(null)
    try {
      const data = await request('/api/optimization/reports?sessionId=' + encodeURIComponent(props.sessionId ?? '') + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '')) as ReportPage
      if (data.version !== 1 || !Array.isArray(data.items) || !(data.nextCursor === null || typeof data.nextCursor === 'string') || !data.items.every(i => typeof i.validationId === 'string' && typeof i.completedAt === 'string' && typeof i.version === 'number' && typeof i.verdict === 'string')) throw new Error('OPTIMIZATION_PROTOCOL_UNKNOWN')
      if (version === epoch.current) setPage(data)
    } catch (e) { if (version === epoch.current) setError(String(e)) }
  }
  const openHistory = async (id: string) => {
    const version = epoch.current
    try { const data = decodeOptimizationReport(await request('/api/optimization/reports/' + encodeURIComponent(id)));if (version === epoch.current) {setHistory(data);setConfirm(false)} }
    catch (e) { if (version === epoch.current) setError(String(e)) }
  }
  const startExperiment = async () => {
    if (!generation || !props.backendUrl || !props.sessionId || experimentBusy) return
    setError(null); setExperiment(null); setExperimentHistorical(false)
    const version=epoch.current, capturedScope=scope
    await experimentTask.submit('/api/optimization/experiments','/api/optimization/experiments','experimentId', { version: 1, source: generation.source, candidateSql: draft, candidateId, seed: experimentSeed || null, timeoutSeconds: Number(experimentTimeout) || 120 }, data => {
      if (version===epoch.current && currentScope.current===capturedScope) setExperiment(data)
    })
  }
  const loadExperimentHistory = async (cursor?: string | null) => {
    const version=epoch.current;setError(null)
    try {
      const data=await request('/api/optimization/experiment-reports?sessionId='+encodeURIComponent(props.sessionId ?? '')+(cursor?'&cursor='+encodeURIComponent(cursor):'')) as {version:number;items:{version:number;experimentId:string;completedAt:string;resultVerdict:string;comparisonState:string}[];nextCursor:string|null}
      if(data.version!==1||!Array.isArray(data.items)||!(data.nextCursor===null||typeof data.nextCursor==='string')||!data.items.every(i=>typeof i.version==='number'&&typeof i.experimentId==='string'&&typeof i.completedAt==='string'&&typeof i.resultVerdict==='string'&&typeof i.comparisonState==='string'))throw Error('EXPERIMENT_PROTOCOL_UNKNOWN')
      if(version===epoch.current)setExperimentPage(data)
    } catch(e){if(version===epoch.current)setError(String(e))}
  }
  const openExperimentHistory = async (id:string) => {
    const version=epoch.current
    try{const data=decodePerformanceExperiment(await request('/api/optimization/experiment-reports/'+encodeURIComponent(id)));if(version===epoch.current){setExperiment(data);setExperimentHistorical(true);setConfirm(false)}}
    catch(e){if(version===epoch.current)setError(String(e))}
  }
  const stopExperiment = () => experimentTask.cancel()
  const displayed = history ?? report
  return <Dialog.Root open={open} onOpenChange={show}>
    <Dialog.Trigger asChild><button className="toolbar-btn toolbar-btn-ghost" disabled={props.disabled || !props.sql.trim() || !props.connectionId || !props.sessionId || !props.backendUrl || !['postgresql', 'mysql'].includes(props.dialect)}>{tr('Optimization candidates')}</button></Dialog.Trigger>
    <Dialog.Portal container={document.querySelector('.app') as HTMLElement ?? undefined}><Dialog.Overlay className="optimization-overlay" /><Dialog.Content className="optimization-dialog" aria-describedby="optimization-description">
      <header><Dialog.Title>{tr('Optimization candidates')}</Dialog.Title><Dialog.Close asChild><button disabled={adopting}>{tr('Close')}</button></Dialog.Close></header>
      <Dialog.Description id="optimization-description">{tr('Preview a supported rewrite, then explicitly run both queries in one read-only snapshot. Matching data does not prove universal equivalence or a speedup.')}</Dialog.Description>
      <div className="optimization-actions"><button onClick={() => void discover()} disabled={busy || active || adopting || props.disabled}>{tr('Discover candidates')}</button>
        <button onClick={() => void loadHistory()} disabled={!props.sessionId}>{tr('Validation history')}</button><button onClick={() => void loadExperimentHistory()} disabled={!props.sessionId || active}>{tr('Performance history')}</button></div>
      {busy && <p role="status">{tr('Discovering candidates…')}</p>}
      {generation && <>
        <label>{tr('Candidate rule')} <select aria-label={tr('Candidate rule')} value={candidateId ?? ''} disabled={active || adopting} onChange={e => { const c = generation.candidates.find(c => c.id === e.target.value);invalidate();setCandidateId(c?.id ?? null);setDraft(c?.sql ?? draft) }}>
          <option value="">{tr('Manual candidate')}</option>{generation.candidates.map(c => <option key={c.id} value={c.id}>{ruleText(c.ruleId)} · {c.path}</option>)}
        </select></label>
        {candidateId && <p>{tr('Conditions pending validation:')} {generation.candidates.find(c => c.id === candidateId)?.conditions.map(conditionText).join(' · ')}</p>}
        {!generation.candidates.length && <p>{tr('No supported rule matched. A manual candidate must satisfy the same validation limits.')}</p>}
        {!!generation.skipped.length && <details><summary>{tr('Skipped conditions')}</summary>{generation.skipped.map((s, i) => <p key={i}>{s.path} · {s.code}</p>)}</details>}
        <div className="optimization-sql"><label>{tr('Original SQL')}<textarea aria-label={tr('Original SQL')} value={generation.source.sql} readOnly /></label>
          <label>{tr('Candidate SQL')}<textarea aria-label={tr('Candidate SQL')} value={draft} readOnly={active || adopting} onChange={e => {invalidate();setCandidateId(null);setDraft(e.target.value)}} /></label></div>
        <p>{tr('Limits: 30 seconds total; each side 100000 rows, 250000 cells, 16 MiB encoded. Exceeding any limit is inconclusive. Stopping may wait for database cleanup.')}</p>
        <div className="optimization-actions"><button onClick={validate} disabled={active || busy || adopting || !draft.trim() || props.disabled}>{tr('Validate both queries')}</button>
          {(active || task.errorReason === 'CANCEL_UNCONFIRMED') && <button onClick={() => void task.cancel()}>{tr('Stop validation')}</button>}
          {(task.errorReason === 'STATUS_UNAVAILABLE' || task.errorReason === 'CANCEL_UNCONFIRMED') && <button onClick={task.retryStatus}>{tr('Retry status')}</button>}
          <button ref={adoptButton} disabled={!valid || adopting} onClick={() => setConfirm(true)}>{tr('Adopt candidate')}</button></div>
        <fieldset className="optimization-experiment-settings"><legend>{tr('Controlled performance comparison')}</legend><label>{tr('Seed')} <input aria-label={tr('Experiment seed')} value={experimentSeed} disabled={experimentBusy} onChange={e => setExperimentSeed(e.target.value)} /></label><label>{tr('Total timeout (seconds)')} <input aria-label={tr('Experiment timeout')} type="number" min="1" max="300" value={experimentTimeout} disabled={experimentBusy} onChange={e => setExperimentTimeout(e.target.value)} /></label><span>{tr('26 SELECTs · 2 warmup pairs · 10 measured pairs · caches and external load are not isolated')}</span><button onClick={() => void startExperiment()} disabled={active || busy || adopting || !draft.trim() || props.disabled}>{tr('Start performance comparison')}</button><button onClick={() => void stopExperiment()} disabled={!experimentBusy || experimentTask.cancelling}>{tr('Stop comparison')}</button>{['STATUS_UNAVAILABLE','CANCEL_UNCONFIRMED'].includes(experimentTask.errorReason ?? '') && <button onClick={experimentTask.retryStatus}>{tr('Retry status')}</button>}</fieldset>
      </>}
      {task.status && <p role="status">{tr('Validation status:')} {task.status}</p>}
      {(error || task.error || experimentTask.error) && <p role="alert">{error ?? task.error ?? experimentTask.error} {task.errorReason ?? experimentTask.errorReason}</p>}
      {experimentTask.status && <p role="status">{tr('Controlled performance comparison')} · {experimentTask.status}</p>}
      {experimentHistorical && <p>{tr('Historical observation only. This report does not certify the current query.')}</p>}
      {experimentPage && <section aria-label={tr('Performance history')}>{experimentPage.items.map(item=><p key={item.experimentId}><button disabled={active} onClick={()=>void openExperimentHistory(item.experimentId)}>{item.completedAt} · {item.version===1?item.resultVerdict+' · '+item.comparisonState:tr('Unknown report version')}</button></p>)}{experimentPage.nextCursor && <button disabled={active} onClick={()=>void loadExperimentHistory(experimentPage.nextCursor)}>{tr('Next page')}</button>}</section>}
      {experiment && <section className="optimization-evidence" aria-label={tr('Performance evidence')}><strong>{experiment.comparisonState === 'COMPLETE' ? tr('Descriptive performance observations') : tr('Performance comparison unavailable')}</strong><p>{experiment.errorReason ?? ''} · {experiment.status} · {experiment.phase}</p><p>{tr('Original median (ns):')} {experiment.summary.original?.medianNs ?? tr('Unknown')} · {tr('Candidate median (ns):')} {experiment.summary.candidate?.medianNs ?? tr('Unknown')} · {tr('Median A−B (ns):')} {experiment.summary.pairedMedianDeltaNs ?? tr('Unknown')} · {experiment.summary.relativeMedianChangePct ?? tr('Unknown')}%</p><p>{tr('Result validation:')} {experiment.validation?.verdict ?? tr('Unknown')} · {tr('Report storage:')} {experiment.persistence.status} {experiment.persistence.errorReason}</p><p>{tr('Original distribution (ns):')} q1 {experiment.summary.original?.q1Ns ?? '—'} · q3 {experiment.summary.original?.q3Ns ?? '—'} · min {experiment.summary.original?.minNs ?? '—'} · max {experiment.summary.original?.maxNs ?? '—'}</p><p>{tr('Candidate distribution (ns):')} q1 {experiment.summary.candidate?.q1Ns ?? '—'} · q3 {experiment.summary.candidate?.q3Ns ?? '—'} · min {experiment.summary.candidate?.minNs ?? '—'} · max {experiment.summary.candidate?.maxNs ?? '—'}</p><p>{experiment.summary.notes.join(' · ')}</p><table><thead><tr><th>{tr('Phase')}</th><th>{tr('Pair')}</th><th>{tr('Order')}</th><th>{tr('Side')}</th><th>{tr('Duration ns')}</th><th>{tr('Status')}</th><th>{tr('Rows')}</th><th>{tr('Encoded bytes')}</th><th>{tr('Sample error')}</th></tr></thead><tbody>{experiment.samples.map((sample, index) => <tr key={index}><td>{sample.phase}</td><td>{sample.pair + 1}</td><td>{sample.order}</td><td>{sample.side}</td><td>{sample.durationNs ?? '—'}</td><td>{sample.status}</td><td>{sample.rows}</td><td>{sample.bytes}</td><td>{sample.errorReason ?? '—'}</td></tr>)}</tbody></table><details><summary>{tr('Timing breakdown')}</summary><pre>{JSON.stringify(experiment.samples,null,2)}</pre></details>{experiment.environment && <details><summary>{tr('Environment limitations')}</summary><pre>{JSON.stringify(experiment.environment, null, 2)}</pre></details>}{experiment.actualPlans && <details><summary>{tr('Actual plan evidence')} · {experiment.actualPlans.degraded ? tr('Incomplete') : tr('Captured')}</summary><p>{experiment.actualPlans.errorReason ?? ''}</p>{(['original', 'candidate'] as const).map(side => { const plan = experiment.actualPlans![side]; return <details key={side}><summary>{side === 'original' ? tr('Original SQL') : tr('Candidate SQL')} · {plan.status} · {plan.databaseProduct ?? tr('Unknown')} {plan.databaseVersion ?? ''}</summary><p>{plan.errorReason ?? ''} · {plan.sessionIdentity ?? tr('Unknown session')}</p><pre>{JSON.stringify(plan.details, null, 2)}</pre></details> })}</details>}</section>}
      {task.errorReason === 'TASK_EXPIRED' && <p>{tr('Task receipt expired. View saved history or explicitly start a new validation.')}</p>}
      {displayed && <div ref={evidence} tabIndex={-1} className="optimization-evidence" aria-label={tr('Validation evidence')}>
        {history && <p>{tr('Historical observation only. This report does not certify the current query.')} <button onClick={() => setHistory(null)}>{tr('Back to current validation')}</button></p>}
        <strong>{verdictText(displayed)}</strong><p>{displayed.errorReason} · {displayed.completedAt ?? displayed.startedAt}</p>
        <p>{tr('Original rows:')} {displayed.original?.rowCount ?? tr('Unknown')} · {tr('Candidate rows:')} {displayed.candidate?.rowCount ?? tr('Unknown')}</p>
        <p>{tr('Report storage:')} {displayed.persistence.status} {displayed.persistence.errorReason}</p>
        {displayed.differencesTruncated && <p>{tr('Difference evidence is limited; additional differences are omitted.')}</p>}
        <details><summary>{tr('Snapshot, schema and typed differences')}</summary><pre>{JSON.stringify({ snapshot: displayed.snapshot, original: displayed.original, candidate: displayed.candidate, differences: displayed.differences }, null, 2)}</pre></details>
        <details><summary>{tr('Exact report SQL and rule evidence')}</summary><pre>{displayed.source.sql}</pre><pre>{displayed.candidateSql}</pre><pre>{JSON.stringify(displayed.candidateEvidence, null, 2)}</pre></details>
      </div>}
      {page && <section aria-label={tr('Validation history')}>{page.items.map(i => <p key={i.validationId}><button onClick={() => void openHistory(i.validationId)}>{i.completedAt} · {i.version === 1 ? i.verdict : tr('Unknown report version')}</button></p>)}{page.nextCursor && <button onClick={() => void loadHistory(page.nextCursor)}>{tr('Next page')}</button>}</section>}
      <Dialog.Root open={confirm && !!valid} onOpenChange={setConfirm}><Dialog.Portal container={document.querySelector('.app') as HTMLElement ?? undefined}><Dialog.Overlay className="optimization-overlay optimization-confirm-layer" /><Dialog.Content className="optimization-dialog optimization-confirm" onCloseAutoFocus={event => { event.preventDefault(); adoptButton.current?.focus() }} aria-describedby="optimization-adopt-description">
        <Dialog.Title>{tr('Open candidate in a new query?')}</Dialog.Title><Dialog.Description id="optimization-adopt-description">{tr('The original query is preserved. The new query will not run automatically.')}</Dialog.Description>
        {displayed?.persistence.status === 'FAILED' && <p>{tr('This validation report was not saved.')}</p>}
        <div className="optimization-actions"><button onClick={() => setConfirm(false)} disabled={adopting}>{tr('Cancel')}</button><button onClick={() => void adopt()} disabled={!valid || adopting}>{tr('Open in new query')}</button></div>
      </Dialog.Content></Dialog.Portal></Dialog.Root>
    </Dialog.Content></Dialog.Portal>
  </Dialog.Root>
}
