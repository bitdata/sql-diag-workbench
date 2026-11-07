import * as Dialog from '@radix-ui/react-dialog'
import { useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { tr, useLocale } from '../../i18n'
import { apiFetch } from '../../utils/api'
import type { InvestigationDraft, InvestigationSnapshot, ObservationReceipt, ReceiptSummary, SnapshotSummary } from '../../types/investigation'
import PlanView from '../Results/PlanView'
import SaveInvestigationDialog from './SaveInvestigationDialog'
import './Investigation.css'
import { archivedMappingSource, decodeInvestigationPage, decodeInvestigationSnapshot, decodeObservationReceipt } from '../../utils/investigationProtocol'

export default function InvestigationHistory({ backendUrl }: { backendUrl: string | null }) {
  useLocale()
  const [open, setOpen] = useState(false)
  const opener = useRef<HTMLButtonElement>(null)
  return <><button ref={opener} className="toolbar-btn" onClick={() => setOpen(true)}>{tr('Investigation history')}</button>
    {open && <HistoryDialog key={backendUrl} backendUrl={backendUrl} returnFocus={opener} onClose={() => setOpen(false)} />}
  </>
}

function HistoryDialog({ backendUrl, onClose, returnFocus }: { backendUrl: string | null; onClose: () => void; returnFocus: RefObject<HTMLButtonElement> }) {
  useLocale()
  const [kind, setKind] = useState<'snapshots' | 'receipts'>('snapshots')
  const [items, setItems] = useState<(ReceiptSummary | SnapshotSummary)[]>([]), [cursor, setCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(false), [error, setError] = useState<string | null>(null)
  const [detail, setDetail] = useState<InvestigationSnapshot | ObservationReceipt | null>(null)
  const [save, setSave] = useState<InvestigationDraft | null>(null), [confirmDelete, setConfirmDelete] = useState<string | null>(null)
  const generation = useRef(0), mounted = useRef(true), mutation = useRef(false)
  const retry = useRef<() => Promise<void>>(async () => {})
  const endpoint = kind === 'receipts' ? 'investigation-observation-receipts' : 'investigation-snapshots'
  const read = async (path: string, init?: RequestInit) => {
    if (!backendUrl) throw new Error('SNAPSHOT_BACKEND_UNAVAILABLE')
    const response = await apiFetch(`${backendUrl}/api/${path}`, init)
    if (response.status === 204) return null
    const data = await response.json()
    if (!response.ok) throw new Error(data.errorReason ?? data.code ?? 'SNAPSHOT_REQUEST_FAILED')
    return data
  }
  const load = async (after?: string) => {
    const run = ++generation.current; setLoading(true); setError(null)
    try {
      const data = decodeInvestigationPage(await read(`${endpoint}${after ? `?cursor=${encodeURIComponent(after)}` : ''}`), kind)
      if (!mounted.current || run !== generation.current) return
      setItems(previous => after ? [...previous, ...data.items] : data.items); setCursor(data.nextCursor ?? null)
    } catch (cause) { if (mounted.current && run === generation.current) { retry.current = () => load(after); setError(cause instanceof Error ? cause.message : 'SNAPSHOT_REQUEST_FAILED') } }
    finally { if (mounted.current && run === generation.current) setLoading(false) }
  }
  useEffect(() => {
    mounted.current = true; setItems([]); setCursor(null); setDetail(null); setSave(null); setConfirmDelete(null); void load()
    return () => { mounted.current = false; generation.current++ }
  }, [kind, backendUrl])
  const openDetail = async (id: string) => {
    const run = ++generation.current; setLoading(true); setDetail(null); setError(null); setConfirmDelete(null)
    try {
      const raw = await read(`${endpoint}/${encodeURIComponent(id)}`)
      const data = kind === 'receipts' ? decodeObservationReceipt(raw, id) : decodeInvestigationSnapshot(raw, id)
      if (mounted.current && run === generation.current) setDetail(data)
    } catch (cause) { if (mounted.current && run === generation.current) { retry.current = () => openDetail(id); setError(cause instanceof Error ? cause.message : 'SNAPSHOT_REQUEST_FAILED') } }
    finally { if (mounted.current && run === generation.current) setLoading(false) }
  }
  const change = async (id: string, action: 'delete' | 'pin', pin = false) => {
    if (mutation.current) return
    mutation.current = true
    const run = ++generation.current; setLoading(true); setError(null)
    try {
      await read(`${endpoint}/${encodeURIComponent(id)}${action === 'pin' ? '/protection' : ''}`, action === 'pin'
        ? { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ protectedValue: pin }) } : { method: 'DELETE' })
      if (!mounted.current || run !== generation.current) return
      setDetail(null); setConfirmDelete(null); await load()
    } catch (cause) { if (mounted.current && run === generation.current) { retry.current = () => change(id, action, pin); setError(cause instanceof Error ? cause.message : 'SNAPSHOT_REQUEST_FAILED') } }
    finally { mutation.current = false; if (mounted.current && run === generation.current) setLoading(false) }
  }
  const snapshot = detail && 'content' in detail ? detail : null
  const receipt = snapshot?.content.observation ?? (detail && 'submittedSql' in detail ? detail : null)
  const mappingSource = useMemo(() => snapshot ? archivedMappingSource(snapshot) : undefined, [snapshot])
  return <Dialog.Root open onOpenChange={value => { if (!value && !mutation.current && !save) onClose() }}>
    <Dialog.Portal container={document.querySelector('.app') as HTMLElement ?? undefined}><Dialog.Overlay className="investigation-overlay" /><Dialog.Content className="investigation-dialog"
      onCloseAutoFocus={event => { event.preventDefault(); if (returnFocus.current?.isConnected) returnFocus.current.focus() }}
      onPointerDownOutside={event => event.preventDefault()}>
      <Dialog.Title>{tr('Investigation history')}</Dialog.Title>
      <Dialog.Description>{tr('Offline, read-only observations. Opening history does not execute SQL or change your workspace.')}</Dialog.Description>
      <div className="investigation-toolbar">
        <button aria-pressed={kind === 'snapshots'} disabled={loading || !!save} onClick={() => setKind('snapshots')}>{tr('Saved snapshots')}</button>
        <button aria-pressed={kind === 'receipts'} disabled={loading || !!save} onClick={() => setKind('receipts')}>{tr('Pending observations')}</button>
        <button disabled={loading || !!save} onClick={() => { setDetail(null); void load() }}>{tr('Refresh')}</button>
        <button disabled={mutation.current || !!save} onClick={onClose}>{tr('Close')}</button>
      </div>
      {kind === 'receipts' && <p>{tr('Temporary SQL and plans remain locally for at most 24 hours. Capacity cleanup may remove them earlier.')}</p>}
      {error && <p role="alert">{tr('Investigation unavailable')} · {error} <button disabled={loading} onClick={() => { void retry.current() }}>{tr('Retry')}</button></p>}
      {loading && <p role="status">{tr('Loading...')}</p>}
      {!detail && <><ul className="investigation-history-list">{items.map(item => <li key={item.id}>
        <button disabled={loading || item.availability === 'UNAVAILABLE'} onClick={() => { void openDetail(item.id) }}>{item.availability === 'UNAVAILABLE' ? tr('Investigation unavailable') : 'title' in item ? item.title : tr('Plan observation')}</button>
        {item.availability === 'UNAVAILABLE' && <p role="status">{item.errorReason} · {item.id}</p>}
        <small>{item.dialect} · {item.sessionName ?? tr('Source session unavailable')} · {item.createdAt}</small>
        {'expiresAt' in item ? <small>{tr('Latest expiry')} · {item.expiresAt}</small> : <small>
          {item.partial && `${tr('Partial')} · `}{item.degraded && `${tr('Degraded')} · `}{item.pinned && `${tr('Pinned')} · `}{item.serverProduct} {item.serverVersion}
        </small>}
        {'pinned' in item && <button disabled={loading} onClick={() => { void change(item.id, 'pin', !item.pinned) }}>{item.pinned ? tr('Unpin snapshot') : tr('Pin snapshot')}</button>}
        <button disabled={loading} onClick={() => setConfirmDelete(item.id)}>{kind === 'receipts' ? tr('Discard observation') : tr('Delete snapshot')}</button>
        {confirmDelete === item.id && <div role="group" aria-label={tr('Confirm deletion')}>
          <p>{tr('Permanently delete this local record?')}</p><button disabled={loading} onClick={() => { void change(item.id, 'delete') }}>{tr('Confirm deletion')}</button>
          <button disabled={loading} onClick={() => setConfirmDelete(null)}>{tr('Cancel')}</button>
        </div>}
      </li>)}</ul>
      {!loading && !error && items.length === 0 && <p>{tr('No investigations saved.')}</p>}
      {cursor && <button disabled={loading} onClick={() => { void load(cursor) }}>{tr('Load more')}</button>}</>}
      {receipt && <section aria-label={tr('Read-only investigation')}>
        <button onClick={() => { generation.current++; setDetail(null); setError(null) }}>{tr('Back to history')}</button>
        <p>{tr('Historical observation only')} · {receipt.capturedAt} · {receipt.dialect} · {receipt.sessionName ?? tr('Source session unavailable')}</p>
        <p>{receipt.planCapture.serverProduct} {receipt.planCapture.serverVersion} · {receipt.executionId}</p>
        <p>SHA-256 · {receipt.submittedSqlSha256}</p>
        {snapshot ? <>
          <h3>{snapshot.content.annotation.title}</h3>
          <p className="investigation-note">{tr('Hypothesis')} · {snapshot.content.annotation.hypothesis}</p>
          <p className="investigation-note">{tr('Observation notes')} · {snapshot.content.annotation.observation}</p>
          <p>{tr('Integrity verified')} · v{snapshot.integrity.version} · {snapshot.integrity.payloadSha256}</p>
          {(['workspace', 'diagnostics', 'mapping'] as const).map(key => <p key={key}>{tr(key === 'workspace' ? 'Workspace' : key === 'diagnostics' ? 'Diagnostics' : 'Mapping')} · {snapshot.content[key].state} · {snapshot.content[key].reason}</p>)}
        </> : <><p>{tr('Latest expiry')} · {receipt.expiresAt}</p>
          <button disabled={loading} onClick={() => setSave({ observationId: receipt.id, sessionId: null, workspace: null, evaluation: null, findings: null })}>{tr('Save plan-only investigation')}</button></>}
        <details open><summary>{tr('Submitted SQL')}</summary><pre>{receipt.submittedSql}</pre></details>
        <PlanView key={receipt.id} plan={receipt.plan} capture={receipt.planCapture} loading={false} mapping={snapshot?.content.mapping.value ?? null} mappingPreview={mappingSource} />
        {snapshot?.content.diagnostics.value && <section aria-label={tr('Captured diagnostics · client response snapshot')}>
          <h3>{tr('Diagnostics')}</h3>
          {snapshot.content.diagnostics.value.findings.map(finding => <article key={finding.id} className="investigation-finding">
            <h4>{tr(finding.title)}</h4><p>{finding.severity} · {finding.status} · {finding.confidence}</p>
            <p>{tr(finding.message)}</p><pre>{finding.evidence.join('\n')}</pre>
            <p>{tr('Verification result')} · {finding.verificationResult ?? tr('Not available')}</p>
            {finding.errorReason && <p role="status">{finding.errorReason}</p>}
          </article>)}
        </section>}
        {snapshot?.content.mapping.value && <details><summary>{tr('Captured mapping evidence')}</summary><pre>{JSON.stringify(snapshot.content.mapping.value, null, 2)}</pre></details>}
        {snapshot?.content.workspace.value && <details><summary>{tr('Captured workspace')}</summary><pre>{JSON.stringify(snapshot.content.workspace.value, null, 2)}</pre></details>}
        {snapshot?.content.diagnostics.value && <details><summary>{tr('Captured diagnostics · client response snapshot')}</summary><pre>{JSON.stringify(snapshot.content.diagnostics.value, null, 2)}</pre></details>}
      </section>}
      {save && backendUrl && <SaveInvestigationDialog backendUrl={backendUrl} draft={save} isCurrent={() => mounted.current}
        onClose={() => setSave(null)} onSaved={() => { setSave(null); setDetail(null); if (kind === 'snapshots') void load(); else setKind('snapshots') }} />}
    </Dialog.Content></Dialog.Portal>
  </Dialog.Root>
}
