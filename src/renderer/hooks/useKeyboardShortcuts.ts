import { useEffect, useCallback, useRef } from 'react'

interface KeyboardShortcutHandlers {
  onParse: () => void
  onExecute: () => void
  onExecuteCurrent?: () => void
  onFormat?: () => void
  onCancel: () => void
  onNewSession: () => void
}

export function useKeyboardShortcuts(handlers: KeyboardShortcutHandlers, enabled = true) {
  const handlersRef = useRef(handlers)
  handlersRef.current = handlers

  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (!enabled || e.defaultPrevented) return

    const target = e.target as HTMLElement
    if (target.closest('[role="dialog"], [role="alertdialog"], [role="menu"]') || document.querySelector('[role="dialog"][data-state="open"], [role="alertdialog"][data-state="open"]')) return
    const isCodeMirror = target.closest('.cm-editor')
    const isInput = target.tagName === 'INPUT' || target.tagName === 'TEXTAREA'

    if (isInput && !isCodeMirror) return

    if (e.key === 'F5' && !e.ctrlKey && !e.metaKey) {
      e.preventDefault()
      handlersRef.current.onExecute()
      return
    }

    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      if (e.shiftKey) handlersRef.current.onParse()
      else (handlersRef.current.onExecuteCurrent ?? handlersRef.current.onExecute)()
      return
    }

    if (e.key.toLowerCase() === 'f' && e.shiftKey && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      handlersRef.current.onFormat?.()
      return
    }

    if (e.key === 'Escape') {
      e.preventDefault()
      handlersRef.current.onCancel()
      return
    }

    if (e.key.toLowerCase() === 'n' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      handlersRef.current.onNewSession()
      return
    }
  }, [enabled])

  useEffect(() => {
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [handleKeyDown])
}
