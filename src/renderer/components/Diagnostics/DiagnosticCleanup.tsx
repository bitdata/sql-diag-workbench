import { tr } from '../../i18n'
import type { DiagnosticLifecycle } from '../../types/diagnostics'

export default function DiagnosticCleanup({ lifecycle, pending, reason, retry }: {
  lifecycle?: DiagnosticLifecycle | null; pending?: boolean; reason?: string | null; retry?: () => void
}) {
  if (!lifecycle && !['RESOURCE_CLEANUP_UNCONFIRMED', 'STATUS_UNAVAILABLE'].includes(reason ?? '')) return null
  return <div className="trace-evidence" role="status" aria-label={tr('Diagnostic resource cleanup')}>
    <p>{tr('Resource cleanup: {0}', lifecycle?.cleanup ?? 'UNCONFIRMED')}</p>
    {lifecycle && <p>{tr('Database cancellation signal:')}{' '}{lifecycle.cancelSignal}</p>}
    {pending && <p>{tr('Observing cleanup for up to five seconds. Database work may still be active.')}</p>}
    {lifecycle?.degraded && <p>{tr('Cleanup has a recorded limitation: {0}', lifecycle.errorReason ?? 'UNKNOWN')}</p>}
    {reason === 'RESOURCE_CLEANUP_UNCONFIRMED' && <p>{tr('Cleanup is not confirmed. Automatic observation stopped; check status explicitly.')}</p>}
    {(reason === 'RESOURCE_CLEANUP_UNCONFIRMED' || reason === 'STATUS_UNAVAILABLE') && retry
      && <button onClick={retry}>{tr('Check cleanup status')}</button>}
    <p>{tr('A completed task or returned cancel signal does not prove that server execution stopped.')}</p>
  </div>
}
