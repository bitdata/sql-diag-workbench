import { tr, useLocale, getLocale } from '../../i18n'
import { useEffect, useMemo, useRef, useState } from 'react'
import * as Tabs from '@radix-ui/react-tabs'
import DataGrid from './DataGrid'
import PlanView, { flattenPlan } from './PlanView'
import DiagnosticsPanel from '../Diagnostics/DiagnosticsPanel'
import RowTracePanel from '../Diagnostics/RowTracePanel'
import SourceVerificationPanel from '../Diagnostics/SourceVerificationPanel'
import PhysicalSourcePanel from '../Diagnostics/PhysicalSourcePanel'
import ComplexSourcePanel from '../Diagnostics/ComplexSourcePanel'
import type { ExecutionResult, PlanSqlMapping } from '../../types/execution'
import { apiFetch } from '../../utils/api'
import { decodePlanSqlMapping, sqlSha256 } from '../../utils/planSqlMapping'
import { resolveMappingTarget, unavailable, type MappingAvailability, type MappingPreviewSnapshot, type MappingRevealRequest } from '../../utils/mappingTargets'
import type { WorkspaceMode } from '../../types/mode'
import type { DiagnosticsHook } from '../../hooks/useDiagnostics'
import type { UseRowTraceReturn } from '../../hooks/useRowTrace'
import './ResultsPanel.css'
import InvestigationActions from '../Investigation/InvestigationActions'

interface ResultsPanelProps {
  provenance?: { sql: string; connectionId: string; dialect: string; connectionName: string; source: string }
  draftScope?: string
  result: ExecutionResult | null
  loading: boolean
  error: string | null
  backendUrl?: string | null
  fragmentTreeJson?: string
  connectionId?: string | null
  sessionId?: string | null
  rowTrace?: UseRowTraceReturn
  showRowTrace?: boolean
  onLocateEvidence?: (id: string, sql: string) => void
  canLocateEvidence?: (id: string, sql: string) => boolean
  checkMappingReveal?: (request: MappingRevealRequest) => MappingAvailability
  onMappingReveal?: (request: MappingRevealRequest) => MappingAvailability
  /**
   * Which set of tabs to expose. Data mode shows results-oriented tabs
   * (Results, Row Trace); Performance mode shows plan-oriented tabs
   * (Plan, Diagnostics). The parent owns this so the choice is stable
   * across re-renders even when the underlying result object is fresh.
   *
   * <p>Defaults to {@code 'data'} for backward compat with callers that
   * haven't been migrated yet.
   */
  mode?: WorkspaceMode
  /**
   * Diagnostics hook state + actions, owned by the parent (App) so it
   * can auto-trigger {@code analyze} when a plan lands in Performance
   * mode. Optional for backward compat — when omitted, the Diagnostics
   * tab renders an empty state.
   */
  diagnostics?: DiagnosticsHook
  /** Verify callback for diagnostic findings, created by the parent. */
  onVerify?: (findingId: string, verificationSql: string) => void
}

type DataTab = 'results' | 'rowTrace'
type PerformanceTab = 'plan' | 'diagnostics'
type Tab = DataTab | PerformanceTab

const DATA_TABS: DataTab[] = ['results', 'rowTrace']
const PERFORMANCE_TABS: PerformanceTab[] = ['plan', 'diagnostics']
const DEFAULT_DATA_TAB: DataTab = 'results'
const DEFAULT_PERFORMANCE_TAB: PerformanceTab = 'plan'

export default function ResultsPanel({
  result,
  loading,
  error,
  backendUrl,
  fragmentTreeJson = '',
  mode = 'data',
  diagnostics,
  connectionId,
  rowTrace,
  showRowTrace = true,
  onVerify,
  onLocateEvidence,
  canLocateEvidence,
  provenance,
  draftScope,
  sessionId,
  checkMappingReveal,
  onMappingReveal,
}: ResultsPanelProps) {
  useLocale()
  // Even a caller with an obsolete receipt must not render it against a replacement plan.
  if (diagnostics?.evaluation && diagnostics.evaluation.executionId !== result?.executionId) diagnostics = {
    ...diagnostics, findings: [], evaluation: null, reportPersistence: null, loading: false, error: null,
    lifecycle: { ...diagnostics.lifecycle, status: 'idle', errorReason: 'DIAGNOSTICS_EXECUTION_MISMATCH' },
    verifyingIds: new Set(), verifyErrors: {},
  }
  const [tab, setTab] = useState<Tab>(
    mode === 'performance' ? DEFAULT_PERFORMANCE_TAB : DEFAULT_DATA_TAB
  )
  const [selection, setSelection] = useState<{ executionId: string; path: string } | null>(null)
  const [mappingState, setMappingState] = useState<{ scope: string; value: PlanSqlMapping | null; snapshot: MappingPreviewSnapshot | null; error: string | null } | null>(null)
  const mappingGeneration = useRef(0)
  const planArea = useRef<HTMLDivElement>(null)
  const selectedPath = selection?.executionId === result?.executionId ? selection?.path : undefined
  // Diagnostics state is owned by the parent hook. Its array identity may change
  // during unrelated parent renders, so use a content key for the mapping effect
  // and avoid issuing the same execution-scoped request twice.
  const diagnosticsReady = diagnostics?.lifecycle.status === 'ready' && diagnostics.evaluation?.executionId === result?.executionId
  const findingsKey = JSON.stringify(diagnosticsReady ? diagnostics?.findings ?? [] : [])
  const diagnosticsReceipt = JSON.stringify([diagnostics?.lifecycle.ownerKey, diagnostics?.lifecycle.generation, diagnostics?.lifecycle.status])
  const pathsKey = JSON.stringify(flattenPlan(result?.plan ?? null).map(({ node }) => node.metrics?.sourcePath ?? null))
  const mappingScope = JSON.stringify([backendUrl, fragmentTreeJson, mode, provenance?.connectionId, provenance?.dialect, provenance?.sql, result?.executionId, !!result?.plan, sessionId, draftScope, findingsKey, pathsKey, diagnosticsReceipt])
  const latestMapping = useRef({ scope: mappingScope, state: mappingState, checkMappingReveal, onMappingReveal })
  latestMapping.current = { scope: mappingScope, state: mappingState, checkMappingReveal, onMappingReveal }
  const mapping = mappingState?.scope === mappingScope ? mappingState.value : null
  const mappingError = mappingState?.scope === mappingScope ? mappingState.error : null
  useEffect(() => {
    const generation = ++mappingGeneration.current
    setMappingState(null)
    if (mode !== 'performance' || !backendUrl || !result?.executionId || !result.plan || !provenance?.sql || !provenance.connectionId) return
    let active = true
    void (async () => {
      let fragment: unknown = null
      try { fragment = fragmentTreeJson ? JSON.parse(fragmentTreeJson) : null }
      catch { throw new Error('FRAGMENT_TREE_PARSE_FAILED') }
      const findings = JSON.parse(findingsKey)
      const hash = await sqlSha256(provenance.sql)
      if (!active) return
      const snapshot: MappingPreviewSnapshot = {
        key: `${generation}:${mappingScope}`, generation, scopeKey: mappingScope, draftScope, backendUrl,
        sessionId: sessionId ?? null, connectionId: provenance.connectionId, dialect: provenance.dialect,
        executionId: result.executionId, submittedSql: provenance.sql, submittedSqlSha256: hash,
        fragment, findings, evaluationExecutionId: diagnosticsReady ? result.executionId : null,
        planPaths: JSON.parse(pathsKey),
      }
      const response = await apiFetch(`${backendUrl}/api/diagnostics/plan-mapping`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
        executionId: result.executionId, sql: provenance.sql, dialect: provenance.dialect, connectionId: provenance.connectionId,
        sessionId: snapshot.sessionId, fragment, findings,
      }) })
      const value = await response.json().catch(() => null)
      if (!active) return
      if (!response.ok) throw new Error(value?.errorReason ?? 'MAPPING_REQUEST_FAILED')
      const decoded = decodePlanSqlMapping(value, { executionId: result.executionId, dialect: provenance.dialect, hash })
      setMappingState({ scope: mappingScope, value: decoded, snapshot, error: null })
    })().catch(error => {
      if (active)
        setMappingState({ scope: mappingScope, value: null, snapshot: null, error: error instanceof Error ? error.message : 'MAPPING_REQUEST_FAILED' })
    })
    return () => { active = false }
  }, [mappingScope])
  const planPaths = useMemo(() => new Set(flattenPlan(result?.plan ?? null).filter(row => row.node.metrics?.version === 1).map(row => row.node.metrics?.sourcePath)), [result?.plan])
  const selectPlan = (path: string) => {
    if (result && planPaths.has(path)) { setSelection({ executionId: result.executionId, path }); setTab('plan') }
  }
  useEffect(() => {
    if (tab === 'plan' && selection?.executionId === result?.executionId)
      planArea.current?.querySelector<HTMLElement>('.plan-node-details')?.focus()
  }, [tab, selection, result?.executionId])

  // Reset to the default tab of the new mode whenever the user flips the
  // mode toggle. The previous tab in the *other* mode is no longer
  // visible, so re-applying it on a flip-back would feel arbitrary.
  useEffect(() => {
    setTab(mode === 'performance' ? DEFAULT_PERFORMANCE_TAB : DEFAULT_DATA_TAB)
  }, [mode])

  // Memoize so the Radix Tabs.List doesn't churn on every render — Radix
  // uses children identity to manage focus and keyboard nav.
  const availableTabs = useMemo<Tab[]>(
    () => (mode === 'performance' ? PERFORMANCE_TABS : DATA_TABS),
    [mode, getLocale()]
  )

  // Pass the hook's error through AS-IS when the hook is wired: useDiagnostics
  // keeps `error: null` in the healthy no-error state, and a `??`-style fallback
  // would turn that null into a fake "Analysis failed" screen for every real
  // user (2026-09-09 credential-tier E2E landmine C, introduced by 4f268bc —
  // hidden by a vitest mock using error: '' instead of the contract's null).
  // The fallback string is only for the genuinely UNWIRED case: the optional
  // `diagnostics` prop being undefined.
  const diagnosticsError = diagnostics
    ? diagnostics.error
    : tr("Diagnostics unavailable (hook not provided)")

  return (
    <Tabs.Root
      className="results-panel"
      value={tab}
      onValueChange={(v) => setTab(v as Tab)}
    >
      <Tabs.List className="tab-bar">
        {mode === 'data' ? (
          <>
            <Tabs.Trigger className="tab" value="results">
              {tr("Results")}{" "}</Tabs.Trigger>
            {showRowTrace && <Tabs.Trigger className="tab" value="rowTrace">
              {tr("Row Trace")}{" "}</Tabs.Trigger>}
          </>
        ) : (
          <>
            <Tabs.Trigger className="tab" value="plan">
              {tr("Plan")}{" "}</Tabs.Trigger>
            <Tabs.Trigger className="tab" value="diagnostics">
              {tr("Diagnostics")}{" "}</Tabs.Trigger>
          </>
        )}
      </Tabs.List>
      {provenance && <details className="execution-details"><summary>{provenance.source} · {provenance.connectionName} · {provenance.dialect} {" "}{tr("· submitted SQL")}</summary>
        <pre>{provenance.sql}</pre><small>{tr("Connection ID:")}{" "}{provenance.connectionId}</small>
      </details>}
      {result?.historyPersistence?.status === 'failed' && <p role="alert" className="storage-warning">{tr("Query completed, but execution history could not be saved.")}{" "}{result.historyPersistence.errorReason}</p>}
      {diagnostics?.reportPersistence?.status === 'failed' && <p role="alert" className="storage-warning">{tr("Diagnostics are available, but the report could not be saved.")}{" "}{diagnostics.reportPersistence.errorReason}</p>}

      {/* Render all four Tabs.Content nodes but let Radix hide the ones
          not in the current mode. This way switching modes preserves
          each tab's internal state (e.g. DiagnosticsPanel's findings,
          RowTracePanel's column/value inputs) — no remount, no lost
          scroll position. */}
      <Tabs.Content forceMount className="tab-content" value="results" hidden={mode !== 'data' || tab !== 'results'}>
        <DataGrid result={result} loading={loading} error={error} />
      </Tabs.Content>
      <Tabs.Content ref={planArea} forceMount className="tab-content" value="plan" hidden={mode !== 'performance' || tab !== 'plan'}>
        {mode === 'performance' && <InvestigationActions backendUrl={backendUrl ?? null} result={result}
          provenance={provenance} sessionId={sessionId ?? null} scope={draftScope ?? ''}
          fragmentTreeJson={fragmentTreeJson} diagnostics={diagnostics} loading={loading} />}
        <PlanView
          key={result?.executionId ?? 'empty-plan'}
          selectedPath={selectedPath}
          onSelect={selectPlan}
          capture={result?.planCapture}
          durationMs={result?.timing?.durationMs}
          plan={result?.plan ?? null}
          loading={loading}
          planError={error ?? result?.error ?? result?.planError ?? null}
          mapping={mappingError ? { version: 1, executionId: result?.executionId ?? '', captureKey: '', dialect: provenance?.dialect ?? '', submittedSqlSha256: '', edges: [], degraded: true, errorReason: mappingError, issues: [] } : mapping}
          mappingPreview={mapping && mappingState?.snapshot ? {
            snapshot: mappingState.snapshot,
            resolve: ordinal => {
              const now = latestMapping.current
              if (!now.state?.value || now.state.snapshot?.key !== mappingState.snapshot?.key)
                return unavailable('MAPPING_PREVIEW_STALE', 'This mapping preview is no longer current.')
              return resolveMappingTarget(now.state.value, now.state.snapshot!, ordinal, now.scope)
            },
            checkReveal: request => latestMapping.current.checkMappingReveal?.(request)
              ?? unavailable('MAPPING_TREE_TARGET_NOT_VISIBLE', 'This fragment is not shown in the current tree.'),
            reveal: request => latestMapping.current.onMappingReveal?.(request)
              ?? unavailable('MAPPING_TREE_TARGET_NOT_VISIBLE', 'This fragment is not shown in the current tree.'),
          } : undefined}
        />
      </Tabs.Content>
      <Tabs.Content forceMount className="tab-content" value="diagnostics" hidden={mode !== 'performance' || tab !== 'diagnostics'}>
        <DiagnosticsPanel
          lifecycle={diagnostics?.lifecycle}
          onRetry={diagnostics?.retry}
          canPlanEvidence={path => !!diagnosticsReady && planPaths.has(path)}
          onPlanEvidence={selectPlan}
          evaluation={diagnostics?.evaluation}
          findings={diagnostics?.findings ?? []}
          loading={diagnostics?.loading ?? false}
          error={diagnosticsError}
          onVerify={diagnosticsReady ? onVerify : undefined}
          verifyingIds={diagnostics?.verifyingIds}
          verifyErrors={diagnostics?.verifyErrors}
          onClearVerifyError={diagnostics?.clearVerifyError}
        />
      </Tabs.Content>
      <Tabs.Content forceMount className="tab-content row-trace-workspace" value="rowTrace" hidden={mode !== 'data' || tab !== 'rowTrace'}>
        {rowTrace ? (
          <RowTracePanel
            key={draftScope}
            fragmentTreeJson={fragmentTreeJson}
            connectionId={connectionId ?? null}
            rowTrace={rowTrace}
            onLocateEvidence={onLocateEvidence}
            canLocateEvidence={canLocateEvidence}
          />
        ) : (
          <p className="placeholder-text">{tr("Row Trace not available")}</p>
        )}
        {rowTrace?.snapshot && rowTrace.submittedKeys?.length && backendUrl && connectionId && rowTrace.snapshot.connectionId === connectionId ?
          <div key={JSON.stringify([connectionId, fragmentTreeJson, rowTrace.traceId])}>
            <PhysicalSourcePanel backendUrl={backendUrl} connectionId={connectionId}
              fragmentTreeJson={rowTrace.snapshot.fragmentTreeJson} keys={rowTrace.submittedKeys} />
            <ComplexSourcePanel backendUrl={backendUrl} connectionId={connectionId}
              fragmentTreeJson={rowTrace.snapshot.fragmentTreeJson} keys={rowTrace.submittedKeys} />
            <SourceVerificationPanel backendUrl={backendUrl} connectionId={connectionId} keys={rowTrace.submittedKeys} />
          </div> : null}
      </Tabs.Content>
    </Tabs.Root>
  )
}

// Re-exported for App.tsx so it can build the mode-aware result prop
// without re-deriving the tab list.
export { DATA_TABS, PERFORMANCE_TABS }
