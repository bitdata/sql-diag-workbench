import { tr, useLocale } from '../../i18n'
import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react'
import type { SqlDialect } from '../../types/session'
import type { ConnectionConfig } from '../../types/connection'
import type { WorkspaceMode } from '../../types/mode'
import { compareTarget, targetIssue, type CompareSource, type CompareTarget } from '../../utils/compareSources'
import { useExecution, type ExecutionSettings } from '../../hooks/useExecution'
import { useDiagnostics } from '../../hooks/useDiagnostics'
import SqlViewer from '../Editor/SqlViewer'
import ResultsPanel from '../Results/ResultsPanel'
import ModeToggle from '../ModeToggle/ModeToggle'
import { AnalyzeSplitButton } from '../Editor/AnalyzeSplitButton'
import './CompareView.css'
import { completenessText } from '../../utils/resultCompleteness'

export interface CompareHandle { run: () => void; cancel: () => void }
interface CompareProps {
  a: CompareTarget | null; b: CompareTarget | null; side: 'A' | 'B'; sources: CompareSource[]
  backendUrl: string | null; connections: ConnectionConfig[]; connectionId: string | null
  settings: ExecutionSettings; theme: 'dark' | 'light' | 'high-contrast'; locked: boolean
  onChoose: (side: 'A' | 'B', target: CompareTarget | null) => void
  onSide: (side: 'A' | 'B') => void; onSwap: () => void; onClose: () => void
}

interface PaneProps extends Omit<CompareProps, 'a' | 'b' | 'side' | 'onSwap' | 'onClose'> {
  side: 'A' | 'B'; target: CompareTarget | null; active: boolean; connection: ConnectionConfig | null
  onBusy: (side: 'A' | 'B', busy: boolean) => void
}
const ComparePane = forwardRef<CompareHandle, PaneProps>(function ComparePane(props, ref) {
  useLocale()
  const { side, target, sources, connection, backendUrl, settings, locked, theme, onBusy } = props
  const [mode, setMode] = useState<WorkspaceMode>('data')
  const issue = targetIssue(target, sources)
  const source = sources.find(s => s.sessionId === target?.sessionId)
  const scope = JSON.stringify([target?.sessionId, target?.node.id, target?.node.sqlFragment, target?.version, connection?.id])
  const execution = useExecution(backendUrl, target?.node.id ?? null, undefined, settings, {
    sessionId: target?.sessionId ?? null, dialect: target?.dialect ?? '', connectionId: connection?.id ?? null,
    sql: target?.node.sqlFragment,
  })
  const diagnostics = useDiagnostics(backendUrl, scope)
  const lastPlan = useRef<unknown>(null)
  const busy = execution.loading || execution.submitting || execution.cancelling || diagnostics.loading || diagnostics.verifyingIds.size > 0
  useEffect(() => { onBusy(side, busy); return () => onBusy(side, false) }, [side, busy, onBusy])
  const mismatch = !!(target && connection && target.dialect !== connection.dialect)
  const disabledReason = issue ?? (!connection ? tr("Select a connection") : mismatch ? tr("Connection dialect does not match this fragment") :
    !target?.node.executable ? tr("This fragment is not executable") : null)
  const run = (analyzeMode: 'estimated' | 'actual' = 'estimated') => {
    if (locked || disabledReason || busy || !target || !connection) return
    if (mode === 'data') void execution.execute(target.node.sqlFragment, connection.id)
    else { diagnostics.clear(); lastPlan.current = null; void execution.analyze(target.node.sqlFragment, connection.id, analyzeMode) }
  }
  useImperativeHandle(ref, () => ({ run: () => run(), cancel: () => { void execution.cancel() } }))
  useEffect(() => {
    if (!locked && !issue) return
    if (execution.loading || execution.submitting) void execution.cancel()
    diagnostics.clear()
  }, [locked, issue])
  useEffect(() => {
    const result = execution.planResult
    if (locked || issue || !target || !result?.plan || lastPlan.current === result) return
    lastPlan.current = result
    void diagnostics.analyze(target.node, result.plan, result.timing, target.sessionId, { executionId: result.executionId, connectionId: connection?.id ?? null })
  }, [execution.planResult, locked, issue, scope])
  const sourceValue = (sessionId: string, id: string, version: string) => JSON.stringify([sessionId, id, version])
  const value = target ? sourceValue(target.sessionId, target.node.id, target.version) : ''
  return <section className={`compare-pane ${props.active ? 'compare-pane-active' : ''}`} aria-label={tr("Fragment {0}", side)}
    onFocusCapture={() => props.onSide(side)} onPointerDown={() => props.onSide(side)}>
    <header className="compare-pane-header">
      <button className="toolbar-btn" onClick={() => props.onSide(side)} aria-pressed={props.active}>{tr("Select for")}{" "}{side}</button>
      <select aria-label={tr("Fragment {0} source", side)} value={value} disabled={locked}
        onKeyDown={event => { if (event.key === 'Escape') event.stopPropagation() }}
        onChange={event => {
          if (!event.target.value) { props.onChoose(side, null); return }
          const [sessionId, id] = JSON.parse(event.target.value) as [string, string]
          const source = sources.find(s => s.sessionId === sessionId)
          const node = source?.nodes.find(n => n.id === id)
          if (source && node) props.onChoose(side, compareTarget(source, node))
        }}>
        <option value="">{tr("Choose a fragment")}</option>
        {target && (issue || !sources.some(s => s.nodes.some(n => sourceValue(s.sessionId, n.id, s.version) === value))) &&
          <option value={value}>{target.tabName} / {target.node.label} {" "}{tr("(source unavailable)")}</option>}
        {sources.map(source => <optgroup key={source.sessionId} label={source.tabName}>
          {source.errorReason ? <option disabled>{source.errorReason}</option> : source.nodes.map(node =>
            <option key={node.id} value={sourceValue(source.sessionId, node.id, source.version)}>{node.label}</option>)}
        </optgroup>)}
      </select>
      <span className="compare-source-label">{target ? `${target.tabName} · ${target.node.label}` : tr("Fragment {0} is empty", side)}</span>
    </header>
    <div className="compare-sql"><SqlViewer value={target?.node.sqlFragment ?? ''} readOnly theme={theme} dialect={(target?.dialect ?? connection?.dialect ?? 'postgresql') as SqlDialect} placeholder={tr("Choose a fragment to compare")} /></div>
    <div className="compare-pane-context">
    {source?.degraded && <p className="compare-notice" data-error-reason="LEGACY_GRAPH">{tr("Only the saved legacy tree is available.")}</p>}
    {disabledReason && <p className="compare-notice" role="status">{disabledReason}</p>}
    </div>
    <div className="compare-actions">
      <ModeToggle value={mode} onChange={setMode} />
      {mode === 'data' ? <button className="toolbar-btn" disabled={locked || busy || !!disabledReason} onClick={() => run()}>{tr("Execute")}</button> :
        <AnalyzeSplitButton backendUrl={backendUrl} connectionId={connection?.id ?? null} onAnalyze={run} disabled={locked || busy || !!disabledReason} />}
      <button className="toolbar-btn" disabled={locked || (!execution.loading && !execution.submitting && execution.errorReason !== 'CANCEL_UNCONFIRMED' && execution.errorReason !== 'STATUS_UNAVAILABLE')}
        onClick={() => { void execution.cancel() }}>{tr("Stop")}</button>
      {execution.errorReason === 'STATUS_UNAVAILABLE' && <button className="toolbar-btn" disabled={locked} onClick={execution.retryStatus}>{tr("Retry status")}</button>}
      <span role="status" aria-label={tr("Fragment {0} execution status", side)}>
        {execution.submitting ? tr("Submitting…") : execution.cancelling ? tr("Cancelling…") : execution.status ?? tr("Not run")}
        {execution.dataResult?.rows ? ` · ${execution.dataResult.rows.length} returned rows` : ''}
        {execution.dataResult?.rows ? ` · ${completenessText(execution.dataResult.resultCompleteness)}` : ''}
        {(mode === 'data' ? execution.dataResult : execution.planResult)?.timing ?
          ` · ${(mode === 'data' ? execution.dataResult : execution.planResult)!.timing!.durationMs} ms` : ''}
      </span>
    </div>
    <ResultsPanel result={mode === 'data' ? execution.dataResult : execution.planResult} loading={execution.loading}
      provenance={execution.provenance ? { ...execution.provenance, connectionName: connection?.name ?? tr("Connection unavailable"), source: tr("Fragment {0}", side) } : undefined}
      error={execution.error} mode={mode} diagnostics={diagnostics} showRowTrace={false}
      onVerify={(id, sql) => { if (!locked && !issue && !busy && connection) void diagnostics.verify(id, sql, connection.id) }} />
  </section>
})

const CompareView = forwardRef<CompareHandle, CompareProps>(function CompareView(props, ref) {
  useLocale()
  const [connectionId, setConnectionId] = useState(props.connectionId)
  useEffect(() => { setConnectionId(props.connectionId) }, [props.connectionId])
  const connection = props.connections.find(c => c.id === connectionId) ?? null
  const aRef = useRef<CompareHandle>(null), bRef = useRef<CompareHandle>(null)
  const [busy, setBusy] = useState({ A: false, B: false })
  const onBusy = useCallback((side: 'A' | 'B', value: boolean) => setBusy(s => s[side] === value ? s : { ...s, [side]: value }), [])
  useImperativeHandle(ref, () => ({ run: () => (props.side === 'A' ? aRef : bRef).current?.run(),
    cancel: () => (props.side === 'A' ? aRef : bRef).current?.cancel() }), [props.side])
  const paneKey = (target: CompareTarget | null) => JSON.stringify([target?.sessionId, target?.node.id, target?.node.sqlFragment, target?.version, connectionId])
  return <div className="compare-view" role="region" aria-label={tr("Fragment comparison")}>
    <header className="compare-header">
      <strong>{tr("Compare fragments")}</strong>
      <label>{tr("Connection for A and B")}{" "}<select aria-label={tr("Compare connection")} value={connectionId ?? ''} disabled={props.locked}
        onChange={event => setConnectionId(event.target.value || null)}>
        <option value="">{tr("Select a connection")}</option>
        {props.connections.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select></label>
      <button className="toolbar-btn" onClick={props.onSwap} disabled={props.locked || busy.A || busy.B}>{tr("Swap A/B")}</button>
      <button className="toolbar-btn" onClick={props.onClose} disabled={props.locked}>{tr("Exit Compare")}</button>
    </header>
    <div className="compare-panels">
      <ComparePane {...props} key={`A:${paneKey(props.a)}`} ref={aRef} side="A" target={props.a} connection={connection} active={props.side === 'A'} onBusy={onBusy} />
      <ComparePane {...props} key={`B:${paneKey(props.b)}`} ref={bRef} side="B" target={props.b} connection={connection} active={props.side === 'B'} onBusy={onBusy} />
    </div>
  </div>
})
export default CompareView
