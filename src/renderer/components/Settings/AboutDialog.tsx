import * as Dialog from '@radix-ui/react-dialog'
import { useRef } from 'react'
import { version } from '../../../../package.json'
import { tr, useLocale } from '../../i18n'
import './SettingsPanel.css'
import './AboutDialog.css'

export default function AboutDialog({ onClose }: { onClose: () => void }) {
  useLocale()
  const opener = useRef(document.activeElement as HTMLElement | null)
  return (
    <Dialog.Root open onOpenChange={open => { if (!open) onClose() }}>
      <Dialog.Portal container={document.querySelector('.app') as HTMLElement ?? undefined}>
        <Dialog.Overlay className="settings-overlay" />
        <Dialog.Content className="settings-panel settings-dialog about-dialog"
          onCloseAutoFocus={event => { event.preventDefault(); opener.current?.focus() }}>
          <div className="settings-header">
            <Dialog.Title>{tr('About')}</Dialog.Title>
            <Dialog.Close className="settings-close" aria-label={tr('Close')}>&#10005;</Dialog.Close>
          </div>
          <div className="settings-body">
            <div>
              <h3 className="about-product">SQL Diagnostic Workbench</h3>
              <p className="about-version">{tr('Version')} {version}</p>
            </div>
            <Dialog.Description className="about-description">
              {tr('Explore SQL fragments, trace data, and investigate execution plans.')}
            </Dialog.Description>
            <div>
              <p className="about-repository-label">{tr('GitHub repository')}</p>
              <a className="about-repository" href="https://github.com/bitdata/sql-diag-workbench"
                target="_blank" rel="noopener noreferrer">
                https://github.com/bitdata/sql-diag-workbench
              </a>
            </div>
            <p className="about-copyright">Copyright © 2026 Bitdata · AGPL-3.0-only</p>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
