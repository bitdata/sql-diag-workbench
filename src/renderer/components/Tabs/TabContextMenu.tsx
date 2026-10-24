import { tr, useLocale } from '../../i18n'
import { useEffect, useRef } from 'react'

interface TabContextMenuProps {
  x: number
  y: number
  onRename: () => void
  onClose: () => void
  onCloseOthers: () => void
  onCloseAll: () => void
  onDismiss: () => void
}

/**
 * Floating context menu for tab right-click. Positioned absolutely,
 * dismisses on outside click or Escape. Spec §5.2.
 */
export default function TabContextMenu({
  x,
  y,
  onRename,
  onClose,
  onCloseOthers,
  onCloseAll,
  onDismiss,
}: TabContextMenuProps) {
  useLocale()
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handleMouseDown(e: MouseEvent) {
      if (!ref.current?.contains(e.target as Node)) onDismiss()
    }
    function handleKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onDismiss()
    }
    // Defer so the same mousedown that opened the menu doesn't immediately
    // dismiss it (event bubbles to document before our listener attaches).
    const id = window.setTimeout(() => {
      document.addEventListener('mousedown', handleMouseDown)
    }, 0)
    document.addEventListener('keydown', handleKey)
    return () => {
      window.clearTimeout(id)
      document.removeEventListener('mousedown', handleMouseDown)
      document.removeEventListener('keydown', handleKey)
    }
  }, [onDismiss])

  return (
    <div
      ref={ref}
      role="menu"
      style={{
        position: 'fixed',
        top: y,
        left: x,
        background: 'var(--bg-elevated, #181818)',
        border: '1px solid var(--border, #444)',
        borderRadius: 4,
        padding: '4px 0',
        zIndex: 1100,
        minWidth: 160,
        boxShadow: '0 4px 12px rgba(0,0,0,0.4)',
      }}
    >
      <MenuItem onClick={onRename}>{tr("Rename…")}</MenuItem>
      <MenuItem onClick={onClose}>{tr("Close")}</MenuItem>
      <MenuItem onClick={onCloseOthers}>{tr("Close others")}</MenuItem>
      <MenuItem onClick={onCloseAll}>{tr("Close all")}</MenuItem>
    </div>
  )
}

function MenuItem({
  onClick,
  children,
}: {
  onClick: () => void
  children: React.ReactNode
}) {
  useLocale()
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        display: 'block',
        width: '100%',
        padding: '6px 12px',
        background: 'transparent',
        border: 'none',
        textAlign: 'left',
        color: 'var(--text-primary, #e0e0e0)',
        cursor: 'pointer',
        fontFamily: 'inherit',
        fontSize: 12,
      }}
      onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--bg-surface, #1a1a1a)')}
      onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
    >
      {children}
    </button>
  )
}
