import { tr, useLocale } from '../i18n'
import * as Dialog from '@radix-ui/react-dialog'
import { useRef } from 'react'
import type { ExitResult } from '../types/workspaceExit'
import './WorkspaceExitDialog.css'

export default function WorkspaceExitDialog({ result, onRetry, onCancel, onDiscard }: {
  result?: ExitResult; onRetry: () => void; onCancel: () => void; onDiscard: () => void
}) {
  useLocale()
  const cancel = useRef<HTMLButtonElement>(null)
  const failed = result && result.status !== 'saved'
  return <Dialog.Root open onOpenChange={open => { if (!open) onCancel() }}>
    <Dialog.Portal>
      <Dialog.Overlay className="workspace-exit-overlay" data-workspace-exit />
      <Dialog.Content className="workspace-exit-dialog" data-workspace-exit
        onOpenAutoFocus={event => { event.preventDefault(); cancel.current?.focus() }}
        onEscapeKeyDown={event => { event.preventDefault(); onCancel() }}
        onPointerDownOutside={event => event.preventDefault()}>
        <Dialog.Title>{failed ? tr("Could not save all workspaces") : result ? tr("Workspaces saved") : tr("Saving workspaces before closing…")}</Dialog.Title>
        <Dialog.Description>{failed
          ? tr("Keep this window open to retry or finish pending work. Exiting without confirmation may lose changes.")
          : result ? tr("Closing the application.") : tr("Waiting for pending operations and saving all open tabs.")}</Dialog.Description>
        {failed && <ul>{result.issues.map((issue, index) => <li key={index}>
          {issue.tabName && <strong>{issue.tabName}: </strong>}{issue.message}
        </li>)}</ul>}
        {(!result || failed) && <div className="workspace-exit-actions">
          <button ref={cancel} onClick={onCancel}>{tr("Cancel exit")}</button>
          {failed && <button onClick={onRetry}>{tr("Retry")}</button>}
          {failed && <button className="workspace-exit-discard" onClick={onDiscard}>{tr("Exit without confirming saved changes")}</button>}
        </div>}
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}
