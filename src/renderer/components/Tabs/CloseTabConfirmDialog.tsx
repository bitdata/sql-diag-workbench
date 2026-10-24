import { tr, useLocale } from '../../i18n'
import * as Dialog from '@radix-ui/react-dialog'

interface CloseTabConfirmDialogProps {
  tabName: string
  onCancel: () => void
  onDiscard: () => void
  onSave: () => void
}

/**
 * Spec §7 — confirm closing a dirty tab. Built on the Radix Dialog already
 * in `package.json` (no separate AlertDialog dependency).
 */
export default function CloseTabConfirmDialog({
  tabName,
  onCancel,
  onDiscard,
  onSave,
}: CloseTabConfirmDialogProps) {
  useLocale()
  return (
    <Dialog.Root open onOpenChange={(o) => { if (!o) onCancel() }}>
      <Dialog.Portal>
        <Dialog.Overlay
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0,0,0,0.6)',
            zIndex: 1200,
          }}
        />
        <Dialog.Content
          style={{
            position: 'fixed',
            top: '50%',
            left: '50%',
            transform: 'translate(-50%, -50%)',
            background: 'var(--bg-elevated, #181818)',
            border: '1px solid var(--border, #444)',
            borderRadius: 8,
            padding: 20,
            minWidth: 360,
            zIndex: 1201,
            color: 'var(--text-primary, #e0e0e0)',
            fontFamily: 'inherit',
          }}
        >
          <Dialog.Title style={{ margin: '0 0 8px', fontSize: 14 }}>
            {tr("Close \"")}{tabName}"?
          </Dialog.Title>
          <Dialog.Description
            style={{ fontSize: 12, color: 'var(--text-muted, #888)', margin: 0 }}
          >
            {tr("This tab has unsaved changes. Discard them, save first, or cancel.")}{" "}</Dialog.Description>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 16 }}>
            <Dialog.Close asChild>
              <button type="button" style={btn('secondary')}>
                {tr("Cancel")}{" "}</button>
            </Dialog.Close>
            <button type="button" style={btn('danger')} onClick={onDiscard}>
              {tr("Discard")}{" "}</button>
            <button type="button" style={btn('primary')} onClick={onSave}>
              {tr("Save first")}{" "}</button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function btn(variant: 'primary' | 'secondary' | 'danger'): React.CSSProperties {
  return {
    padding: '6px 14px',
    background:
      variant === 'primary' ? 'var(--accent, #00d4aa)' :
      variant === 'danger' ? '#dc2626' : 'transparent',
    color: variant === 'secondary' ? 'var(--text-primary, #e0e0e0)' : '#fff',
    border: variant === 'secondary' ? '1px solid var(--border, #444)' : 'none',
    borderRadius: 4,
    cursor: 'pointer',
    fontSize: 12,
    fontFamily: 'inherit',
  }
}