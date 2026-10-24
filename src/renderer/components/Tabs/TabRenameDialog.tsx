import { tr, useLocale } from '../../i18n'
import { useState, useEffect } from 'react'
import * as Dialog from '@radix-ui/react-dialog'

interface TabRenameDialogProps {
  currentName: string
  onConfirm: (newName: string) => void
  onCancel: () => void
}

export default function TabRenameDialog({
  currentName,
  onConfirm,
  onCancel,
}: TabRenameDialogProps) {
  useLocale()
  const [name, setName] = useState(currentName)

  useEffect(() => setName(currentName), [currentName])

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
          aria-describedby={undefined}
        >
          <Dialog.Title style={{ margin: '0 0 12px', fontSize: 14 }}>
            {tr("Rename tab")}{" "}</Dialog.Title>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && name.trim()) onConfirm(name.trim())
              else if (e.key === 'Escape') onCancel()
            }}
            autoFocus
            style={{
              width: '100%',
              padding: '6px 8px',
              background: 'var(--bg-surface, #1a1a1a)',
              border: '1px solid var(--border, #444)',
              borderRadius: 4,
              color: 'var(--text-primary, #e0e0e0)',
              fontSize: 13,
              fontFamily: 'inherit',
              boxSizing: 'border-box',
            }}
          />
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 16 }}>
            <Dialog.Close asChild>
              <button
                type="button"
                style={btn('secondary')}
                onClick={onCancel}
              >
                {tr("Cancel")}{" "}</button>
            </Dialog.Close>
            <button
              type="button"
              style={btn('primary')}
              onClick={() => name.trim() && onConfirm(name.trim())}
              disabled={!name.trim()}
            >
              {tr("Rename")}{" "}</button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function btn(variant: 'primary' | 'secondary'): React.CSSProperties {
  const isPrimary = variant === 'primary'
  return {
    padding: '6px 14px',
    background: isPrimary ? 'var(--accent, #00d4aa)' : 'transparent',
    color: isPrimary ? '#000' : 'var(--text-primary, #e0e0e0)',
    border: isPrimary ? 'none' : '1px solid var(--border, #444)',
    borderRadius: 4,
    cursor: 'pointer',
    fontSize: 12,
    fontFamily: 'inherit',
  }
}