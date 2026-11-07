import { useEffect, useRef, useState } from 'react'
import { tr, useLocale } from '../../i18n'
import type { ExecutionResult } from '../../types/execution'
import type { InvestigationDraft } from '../../types/investigation'
import type { FragmentNode } from '../../types/fragment'
import type { DiagnosticsHook } from '../../hooks/useDiagnostics'
import { apiFetch } from '../../utils/api'
import SaveInvestigationDialog from './SaveInvestigationDialog'
import type { ObservationReceipt } from '../../types/investigation'
import { decodeObservationReceipt } from '../../utils/investigationProtocol'

export default function InvestigationActions({ backendUrl, result, provenance, sessionId, scope, fragmentTreeJson, diagnostics, loading }: {
  backendUrl: string | null; result: ExecutionResult | null; provenance?: { sql: string; connectionId: string; dialect: string }
  sessionId: string | null; scope: string; fragmentTreeJson: string; diagnostics?: DiagnosticsHook; loading: boolean
}) {
  useLocale()
  const owner = JSON.stringify([backendUrl, result?.executionId, provenance, sessionId, scope, fragmentTreeJson])
  const latest = useRef(owner); latest.current = owner
  const [override, setOverride] = useState<{ owner: string; receipt: ExecutionResult['observationReceipt'] } | null>(null)
  const [draft, setDraft] = useState<{ owner: string; value: InvestigationDraft } | null>(null)
  const [message, setMessage] = useState<string | null>(null), [busy, setBusy] = useState(false)
  const mounted = useRef(true), inFlight = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => { setDraft(null); setMessage(null); setBusy(false); setOverride(null) }, [owner])
  const receipt = override?.owner === owner ? override.receipt : result?.observationReceipt
  const [availability, setAvailability] = useState<{ owner: string; payload?: ObservationReceipt; error?: string } | null>(null)
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    let alive = true
    setAvailability(null)
    if (backendUrl && receipt?.status === 'SAVED' && receipt.receiptId) {
      const id = receipt.receiptId
      void (async () => {
        try {
          const response = await apiFetch(`${backendUrl}/api/investigation-observation-receipts/${encodeURIComponent(id)}`)
          const raw = await response.json()
          if (!response.ok) throw new Error(raw?.errorReason ?? 'SNAPSHOT_REQUEST_FAILED')
          const payload = decodeObservationReceipt(raw, id)
          if (payload.executionId !== result?.executionId || payload.submittedSql !== result.planCapture?.submittedSql) throw new Error('SNAPSHOT_SCOPE_MISMATCH')
          if (alive) setAvailability({ owner, payload })
        } catch (cause) { if (alive) setAvailability({ owner, error: cause instanceof Error ? cause.message : 'SNAPSHOT_REQUEST_FAILED' }) }
      })()
    }
    return () => { alive = false }
  }, [backendUrl, owner, receipt?.status, receipt?.receiptId, refresh])
  const available = availability?.owner === owner ? availability : null
  const capture = result?.planCapture
  const matches = !!capture && !!provenance && capture.submittedSql === provenance.sql && capture.connectionId === provenance.connectionId
    && capture.dialect === provenance.dialect && capture.sessionId === sessionId
  const current = () => mounted.current && latest.current === owner
  const act = async (action: 'retry' | 'discard') => {
    if (!backendUrl || !result || inFlight.current || !matches) return
    inFlight.current = true; setBusy(true); setMessage(null)
    try {
      const response = await apiFetch(`${backendUrl}/api/investigation-observation-receipts/${encodeURIComponent(action === 'retry' ? result.executionId : receipt?.receiptId ?? '')}${action === 'retry' ? '/retry' : ''}`,
        action === 'retry' ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sql: provenance!.sql, connectionId: provenance!.connectionId, sessionId }) } : { method: 'DELETE' })
      const data = response.status === 204 ? null : await response.json()
      if (!response.ok) throw new Error(data?.errorReason ?? 'SNAPSHOT_REQUEST_FAILED')
      if (action === 'retry' && data?.observationReceipt?.status !== 'SAVED') throw new Error('SNAPSHOT_INVALID_RESPONSE')
      if (current()) { setOverride({ owner, receipt: action === 'retry' ? data.observationReceipt : null }); if (action === 'discard') setMessage(tr('Observation discarded.')) }
    } catch (cause) { if (current()) setMessage(cause instanceof Error ? cause.message : 'SNAPSHOT_REQUEST_FAILED') }
    finally { inFlight.current = false; if (current()) setBusy(false) }
  }
  if (!result?.plan || !capture) return null
  return <div className="investigation-toolbar">
    <span>{tr('Temporary SQL and plans remain locally for at most 24 hours. Capacity cleanup may remove them earlier.')}</span>
    {receipt?.status === 'SAVED' && <><span>{tr('Captured')} · {capture.capturedAt}</span>
      {available?.payload && <span>{tr('Latest expiry')} · {available.payload.expiresAt}</span>}
      {available?.error && <span role="alert">{available.error} <button onClick={() => setRefresh(n => n + 1)}>{tr('Retry')}</button></span>}
      <button disabled={busy || loading || !matches || !backendUrl || !available?.payload} onClick={() => {
        let workspace: FragmentNode | null = null
        try {
          const parsed = fragmentTreeJson ? JSON.parse(fragmentTreeJson) as FragmentNode : null
          if (parsed?.sqlFragment === capture.submittedSql) workspace = parsed
        } catch { setMessage('FRAGMENT_TREE_PARSE_FAILED'); return }
        const evaluation = workspace && diagnostics?.lifecycle.status === 'ready' && diagnostics.evaluation?.executionId === result.executionId && diagnostics.evaluation.sourceState === 'VERIFIED' ? diagnostics.evaluation : null
        setDraft({ owner, value: JSON.parse(JSON.stringify({ observationId: receipt.receiptId, sessionId, workspace, evaluation, findings: evaluation ? diagnostics?.findings : null })) })
      }}>{tr('Save investigation')}</button>
      <button disabled={busy || loading || !matches || !backendUrl} onClick={() => { void act('discard') }}>{tr('Discard observation')}</button>
    </>}
    {receipt?.status === 'FAILED' && <><span role="alert">{tr('Plan available, but its temporary observation could not be saved.')} {receipt.errorReason}</span>
      <button disabled={busy || loading || !matches || !backendUrl} onClick={() => { void act('retry') }}>{tr('Retry observation save')}</button></>}
    {message && <span role="status">{message}</span>}
    {draft?.owner === owner && backendUrl && <SaveInvestigationDialog backendUrl={backendUrl} draft={draft.value} isCurrent={current}
      onClose={() => setDraft(null)} onSaved={() => { setDraft(null); setOverride({ owner, receipt: null }); setMessage(tr('Investigation saved. Open Investigation history to review it.')) }} />}
  </div>
}
