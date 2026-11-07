import * as Dialog from '@radix-ui/react-dialog'
import { useEffect, useRef, useState } from 'react'
import { tr, useLocale } from '../../i18n'
import { apiFetch } from '../../utils/api'
import type { InvestigationDraft } from '../../types/investigation'
import './Investigation.css'

export default function SaveInvestigationDialog({ backendUrl, draft, isCurrent, onClose, onSaved }: {
  backendUrl: string; draft: InvestigationDraft; isCurrent: () => boolean
  onClose: () => void; onSaved: (id: string) => void
}) {
  useLocale()
  const [title, setTitle] = useState(''), [hypothesis, setHypothesis] = useState(''), [observation, setObservation] = useState('')
  const [confirmed, setConfirmed] = useState(false), [pending, setPending] = useState(false), [error, setError] = useState<string | null>(null)
  const request = useRef<{ key: string; id: string } | null>(null), mounted = useRef(true), busy = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const submit = async () => {
    if (busy.current || !isCurrent()) return
    const body = { ...draft, annotation: { title, hypothesis, observation }, sensitiveDataConfirmed: confirmed }
    const key = JSON.stringify(body)
    if (request.current?.key !== key) request.current = { key, id: crypto.randomUUID() }
    busy.current = true; setPending(true); setError(null)
    try {
      const response = await apiFetch(`${backendUrl}/api/investigation-snapshots`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...body, requestId: request.current.id }) })
      const data = await response.json()
      if (!response.ok) throw new Error(data.errorReason ?? data.code ?? 'SNAPSHOT_SAVE_FAILED')
      if (data.status !== 'SAVED' || typeof data.snapshotId !== 'string') throw new Error('SNAPSHOT_INVALID_RESPONSE')
      if (mounted.current && isCurrent()) onSaved(data.snapshotId)
    } catch (cause) { if (mounted.current && isCurrent()) setError(cause instanceof Error ? cause.message : 'SNAPSHOT_SAVE_FAILED') }
    finally { busy.current = false; if (mounted.current) setPending(false) }
  }
  // Scope invalidation unmounts this component. Late replies never dismiss a newer dialog.
  return <Dialog.Root open onOpenChange={open => { if (!open && !pending) { mounted.current = false; onClose() } }}>
    <Dialog.Portal container={document.querySelector('.app') as HTMLElement ?? undefined}><Dialog.Overlay className="investigation-overlay" />
      <Dialog.Content className="investigation-dialog investigation-save" onPointerDownOutside={event => event.preventDefault()}
        onEscapeKeyDown={event => { if (pending) event.preventDefault() }}>
        <Dialog.Title>{tr('Save investigation')}</Dialog.Title>
        <Dialog.Description>{tr("An immutable observation saved in this device's local workspace database.")}</Dialog.Description>
        <form onSubmit={event => { event.preventDefault(); void submit() }}>
          <label>{tr('Investigation title')}<input autoFocus required value={title} disabled={pending} onChange={e => setTitle(e.target.value)} /></label>
          <label>{tr('Hypothesis')}<textarea value={hypothesis} disabled={pending} onChange={e => setHypothesis(e.target.value)} /></label>
          <label>{tr('Observation notes')}<textarea value={observation} disabled={pending} onChange={e => setObservation(e.target.value)} /></label>
          <p>{draft.workspace ? tr('Matching workspace will be saved.') : tr('Plan only: workspace, diagnostics and mapping are unavailable.')}</p>
          {draft.workspace && !draft.evaluation && <p>{tr('Diagnostics were not captured. Evidence mapping is unavailable.')}</p>}
          <label className="investigation-confirm"><input type="checkbox" checked={confirmed} disabled={pending} onChange={e => setConfirmed(e.target.checked)} />
            {tr('Save SQL, raw plan, database/user names, server identity and settings on this device.')}</label>
          {error && <p role="alert">{tr('Investigation could not be saved. Your notes are retained.')} {error}</p>}
          <div className="investigation-actions">
            <button type="button" disabled={pending} onClick={() => { mounted.current = false; onClose() }}>{tr('Cancel')}</button>
            <button type="submit" disabled={pending || !confirmed || !title.trim() || Array.from(title).length > 120 || Array.from(hypothesis).length > 4000 || Array.from(observation).length > 4000}>{pending ? tr('Saving…') : tr('Save investigation')}</button>
          </div>
        </form>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}
