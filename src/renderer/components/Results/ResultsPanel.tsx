import { tr, useLocale, getLocale } from '../../i18n'
import { useEffect, useMemo, useState } from 'react'
import * as Tabs from '@radix-ui/react-tabs'
import DataGrid from './DataGrid'
import PlanView from './PlanView'
import DiagnosticsPanel from '../Diagnostics/DiagnosticsPanel'
import RowTracePanel from '../Diagnostics/RowTracePanel'
import SourceVerificationPanel from '../Diagnostics/SourceVerificationPanel'
import PhysicalSourcePanel from '../Diagnostics/PhysicalSourcePanel'
import ComplexSourcePanel from '../Diagnostics/ComplexSourcePanel'
import type { ExecutionResult } from '../../types/execution'
import type { WorkspaceMode } from '../../types/mode'
import type { DiagnosticsHook } from '../../hooks/useDiagnostics'
import type { UseRowTraceReturn } from '../../hooks/useRowTrace'
import './ResultsPanel.css'

interface ResultsPanelProps {
  provenance?: { sql: string; connectionId: string; dialect: string; connectionName: string; source: string }
  draftScope?: string
  result: ExecutionResult | null
  loading: boolean
  error: string | null
  backendUrl?: string | null
  fragmentTreeJson?: string
  connectionId?: string | null
  rowTrace?: UseRowTraceReturn
  showRowTrace?: boolean
  onLocateEvidence?: (id: string, sql: string) => void
  canLocateEvidence?: (id: string, sql: string) => boolean
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
}: ResultsPanelProps) {
  useLocale()
  const [tab, setTab] = useState<Tab>(
    mode === 'performance' ? DEFAULT_PERFORMANCE_TAB : DEFAULT_DATA_TAB
  )

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
      <Tabs.Content forceMount className="tab-content" value="plan" hidden={mode !== 'performance' || tab !== 'plan'}>
        <PlanView
          capture={result?.planCapture}
          durationMs={result?.timing?.durationMs}
          plan={result?.plan ?? null}
          loading={loading}
          planError={error ?? result?.error ?? result?.planError ?? null}
        />
      </Tabs.Content>
      <Tabs.Content forceMount className="tab-content" value="diagnostics" hidden={mode !== 'performance' || tab !== 'diagnostics'}>
        <DiagnosticsPanel
          evaluation={diagnostics?.evaluation}
          findings={diagnostics?.findings ?? []}
          loading={diagnostics?.loading ?? false}
          error={diagnosticsError}
          onVerify={onVerify}
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
