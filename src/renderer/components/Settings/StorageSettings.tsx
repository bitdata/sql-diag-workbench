import { tr, useLocale } from '../../i18n'
import { useEffect, useState } from 'react'
import type { StorageHook, StoragePolicy } from '../../hooks/useStorage'
const mib = (bytes: number) => `${(bytes / 1048576).toFixed(2)} MiB`

export default function StorageSettings({ storage, legacyDays }: { storage: StorageHook; legacyDays: number }) {
  useLocale()
  const { status, preview, error, pending, busy } = storage
  const [draft, setDraft] = useState<StoragePolicy | null>(null)
  const [confirmation, setConfirmation] = useState(false)
  const [selected, setSelected] = useState<string[]>([])
  const [applying, setApplying] = useState(false)
  useEffect(() => {
    if (status && !draft) setDraft({ ...status.policy, retentionDays: status.policy.revision === 1 ? legacyDays : status.policy.retentionDays })
  }, [status, draft, legacyDays])
  useEffect(() => { setSelected([]); setConfirmation(false) }, [preview])
  const valid = draft && Number.isInteger(draft.retentionDays) && draft.retentionDays >= 1 && draft.retentionDays <= 365
    && Number.isInteger(draft.maxStorageMiB) && draft.maxStorageMiB >= 16 && draft.maxStorageMiB <= 102400
  return <div className="settings-section storage-settings">
    <h3>{tr("Data Retention")}</h3>
    {error && <p role="alert" className="storage-warning">{error} <button onClick={() => { void storage.refresh() }}>{tr("Retry status")}</button></p>}
    {!status && <p role="status">{tr("Storage policy is not loaded. No deletion settings can be applied.")}</p>}
    {draft && <>
      <label className="settings-label">{tr("Automatically clean stored data")}{" "}<input type="checkbox" checked={draft.enabled} onChange={e => setDraft({ ...draft, enabled: e.target.checked })} /></label>
      <label className="settings-label">{tr("Auto-cleanup after (days)")}{" "}<input className="settings-input" type="number" min="1" max="365" value={Number.isFinite(draft.retentionDays) ? draft.retentionDays : ''}
          onChange={e => setDraft({ ...draft, retentionDays: e.target.valueAsNumber })} /></label>
      <label className="settings-label">{tr("Storage target (MiB)")}{" "}<input className="settings-input" type="number" min="16" max="102400" value={Number.isFinite(draft.maxStorageMiB) ? draft.maxStorageMiB : ''}
          onChange={e => setDraft({ ...draft, maxStorageMiB: e.target.valueAsNumber })} /></label>
      <p className="storage-description">{tr("This deletes old execution history. Capacity pressure may delete newer records. Open and recoverable workspaces and creation receipts stay protected. Apply confirms this policy; typing does not activate it.")}</p>
      <button disabled={!valid || pending || applying || !!error} onClick={async () => {
        setApplying(true)
        try { if (await storage.apply(draft)) setDraft(null) } finally { setApplying(false) }
      }}>{tr("Apply policy")}</button>
      <button disabled={pending || !!error} onClick={() => { if (status) setDraft({ ...status.policy }) }}>{tr("Reload saved policy")}</button>
    </>}
    {status && <>
      <p role="status">{tr("Saved policy:")}{" "}{status.policy.enabled ? tr("automatic cleanup enabled") : tr("automatic cleanup disabled")} · {status.policy.retentionDays} {" "}{tr("days ·")}{" "}{status.policy.maxStorageMiB} {" "}{tr("MiB target")}</p>
      <p>{status.size.degraded ? tr("Storage size unavailable") : tr("Stored {0} · reusable {1} · DB {2} / WAL {3} / SHM {4}", mib(status.size.totalBytes), mib(status.size.reusableBytes), mib(status.size.mainBytes), mib(status.size.walBytes), mib(status.size.shmBytes))}</p>
      {status.errorReason && <p className="storage-warning" role="status">{status.errorReason}</p>}
      <button disabled={pending || busy} onClick={() => { void storage.loadPreview() }}>{tr("Preview cleanup")}</button>
      {preview && <>
        <p>{preview.protectedSessions} {" "}{tr("protected workspaces ·")}{" "}{preview.unknownSessions} {" "}{tr("unconfirmed workspaces")}</p>
        <p>{tr("Expired candidates:")}{" "}{Object.entries(preview.expiredCounts).map(([table, count]) => `${table} ${count}`).join(' · ')}</p>
        {preview.degraded && <p role="alert" className="storage-warning">{preview.errorReason}</p>}
        {preview.unknown.length > 0 && <>
          <p className="storage-description">{tr("Unconfirmed workspaces are retained. Select only work you no longer need. Marking allows automatic deletion and cannot be undone here. Lost profile protections cannot be released here.")}</p>
          <div className="storage-candidates">{preview.unknown.map(candidate => <label key={candidate.id} className="storage-candidate">
            <input type="checkbox" checked={selected.includes(candidate.id)} onChange={e => setSelected(ids => e.target.checked ? [...ids, candidate.id] : ids.filter(id => id !== candidate.id))} />
            <span>{candidate.name} · {candidate.updatedAt} {" "}{tr("· approx.")}{" "}{mib(candidate.estimatedBytes)}</span>
          </label>)}</div>
          <label><input type="checkbox" checked={confirmation} onChange={e => setConfirmation(e.target.checked)} /> {" "}{tr("I understand selected workspaces may be permanently deleted.")}</label>
          <button disabled={!selected.length || !confirmation || pending || busy} onClick={() => { void storage.mark(selected) }}>{tr("Mark selected as reclaimable")}</button>
        </>}
        <button disabled={pending || busy || !status.protectionReady || status.size.degraded} onClick={() => { void storage.clean() }}>{tr("Clean now")}</button>
      </>}
      {status.job && <div role="status" aria-label={tr("Cleanup job result")}>
        <p>{tr("Cleanup")}{" "}{status.job.status} · {status.job.phase}</p>
        <p>{tr("Deleted:")}{" "}{Object.entries(status.job.deleted).map(([table, count]) => `${table} ${count}`).join(' · ') || 'none'}</p>
        {status.job.after && <p>{mib(status.job.before.totalBytes)} → {mib(status.job.after.totalBytes)} {" "}{tr("· target")}{" "}{status.job.targetMet ? 'met' : tr("not met")}</p>}
        {(status.job.errorReason || status.job.skippedReasons.length > 0) && <p className="storage-warning">{[status.job.errorReason, ...status.job.skippedReasons].filter(Boolean).join(' · ')}</p>}
        {busy && <button disabled={pending} onClick={() => { void storage.cancel() }}>{tr("Stop after current batch")}</button>}
      </div>}
    </>}
  </div>
}
