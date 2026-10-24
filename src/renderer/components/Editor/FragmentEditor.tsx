import { tr, useLocale, getLocale } from '../../i18n'
import { useState, useCallback, useEffect, useMemo, useRef } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { formatSql } from '../../utils/formatSql'
import type { SqlDialect } from '../../types/session'
import './FragmentEditor.css'

/**
 * Lightweight line-based diff for editor preview only. The real change
 * attribution is done by the backend POST /api/diff (spec §3.5 / §6.5).
 * This local helper is intentionally trivial — it's a "what did I just type"
 * hint, not a structural AST diff.
 */
interface LocalLineDiff {
  added: string[]
  removed: string[]
  modified: string[]
  description: string
}

function localLineDiff(oldSql: string, newSql: string): LocalLineDiff {
  const norm = (s: string) => s.split('\n').map((l) => l.trim()).filter(Boolean)
  const oldLines = norm(oldSql)
  const newLines = norm(newSql)
  const oldSet = new Set(oldLines)
  const newSet = new Set(newLines)
  const removed = oldLines.filter((l) => !newSet.has(l))
  const added = newLines.filter((l) => !oldSet.has(l))
  const modified: string[] = []
  for (let i = 0; i < Math.min(oldLines.length, newLines.length); i++) {
    if (oldLines[i] !== newLines[i]) {
      modified.push(newLines[i])
    }
  }
  const parts: string[] = []
  if (removed.length > 0) parts.push(`-${removed.length} line${removed.length > 1 ? 's' : ''}`)
  if (added.length > 0) parts.push(`+${added.length} line${added.length > 1 ? 's' : ''}`)
  if (modified.length > 0) parts.push(`~${modified.length} modified`)
  return {
    added,
    removed,
    modified,
    description: parts.length > 0 ? parts.join(', ') : tr("no changes"),
  }
}

interface FragmentEditorProps {
  /** The SQL fragment to edit */
  sql: string
  /** Human-readable label for this fragment */
  label: string
  /** Dialect for formatting */
  dialect: SqlDialect
  /** Called when user saves the edited variant */
  onSave: (newSql: string, description: string) => void
  /** Called when user cancels */
  onCancel: () => void
}

export default function FragmentEditor({
  sql,
  label,
  dialect,
  onSave,
  onCancel,
}: FragmentEditorProps) {
  useLocale()
  const [editedSql, setEditedSql] = useState(sql)
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [showDiff, setShowDiff] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Live diff vs the original — recomputed whenever the editor content
  // changes. Free-edit Mode A: show the user what they're about to commit
  // before they click Save, so a wrong paste doesn't silently land.
  // The structural diff (canonicalized AST ops) is owned by the backend
  // ChangeAttribution; this local helper is a "what did I just type" hint.
  const diff = useMemo(
    () => localLineDiff(sql, editedSql),
    [sql, editedSql, getLocale()]
  )
  const hasChanges = diff.added.length > 0 || diff.removed.length > 0 || diff.modified.length > 0

  // Auto-focus the textarea after dialog opens
  useEffect(() => {
    // Small delay to let the dialog animation start
    const timer = setTimeout(() => textareaRef.current?.focus(), 50)
    return () => clearTimeout(timer)
  }, [])

  // Format the SQL in the editor
  const handleFormat = useCallback(() => {
    try {
      const formatted = formatSql(editedSql, { dialect, tabWidth: 2, keywordCase: 'upper' })
      setEditedSql(formatted)
      setError(null)
    } catch {
      setError(tr("Failed to format SQL"))
    }
  }, [editedSql, dialect])

  // Validate the SQL is not empty
  const handleSave = useCallback(() => {
    const trimmed = editedSql.trim()
    if (!trimmed) {
      setError(tr("SQL cannot be empty"))
      return
    }

    // Simple validation: must contain SELECT or WITH
    const upper = trimmed.toUpperCase()
    if (!upper.includes('SELECT') && !upper.includes('WITH')) {
      setError(tr("Invalid SQL: must contain SELECT or WITH"))
      return
    }

    onSave(trimmed, description || `User edit: ${trimmed.substring(0, 50)}...`)
  }, [editedSql, description, onSave])

  // Handle keyboard shortcuts on the textarea
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault()
        handleSave()
      } else if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key === 'f') {
        e.preventDefault()
        handleFormat()
      }
    },
    [handleSave, handleFormat]
  )

  const handleOpenChange = (open: boolean) => {
    if (!open) onCancel()
  }

  return (
    <Dialog.Root open onOpenChange={handleOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fragment-editor-overlay" />
        <Dialog.Content
          className="fragment-editor-modal"
          aria-labelledby="fragment-editor-title"
          aria-describedby={undefined}
        >
          {/* Header */}
          <div className="fragment-editor-header">
            <div className="fragment-editor-title-row">
              <span className="fragment-editor-icon">✎</span>
              <Dialog.Title id="fragment-editor-title" className="fragment-editor-title">
                {tr("Edit Fragment")}{" "}</Dialog.Title>
            </div>
            <span className="fragment-editor-label">{label}</span>
          </div>

          {/* SQL Editor */}
          <div className="fragment-editor-body">
            <div className="fragment-editor-toolbar">
              <span className="fragment-editor-toolbar-label">SQL</span>
              <button
                className="fragment-editor-btn"
                onClick={handleFormat}
                title={tr("Format SQL (Ctrl+Shift+F)")}
                type="button"
              >
                {tr("↻ Format")}{" "}</button>
              <button
                className="fragment-editor-btn"
                onClick={() => setShowDiff((s) => !s)}
                title={tr("Show diff vs original")}
                type="button"
              >
                {showDiff ? tr("⊞ Hide diff") : tr("⊟ Show diff")}
              </button>
              {hasChanges && (
                <span className="fragment-editor-diff-summary" aria-live="polite">
                  {diff.description}
                </span>
              )}
            </div>
            <textarea
              ref={textareaRef}
              className="fragment-editor-textarea"
              value={editedSql}
              onChange={(e) => {
                setEditedSql(e.target.value)
                setError(null)
              }}
              onKeyDown={handleKeyDown}
              spellCheck={false}
              aria-label={tr("SQL fragment editor")}
            />
            {showDiff && hasChanges && (
              <div className="fragment-editor-diff" aria-label={tr("Diff vs original")}>
                {diff.removed.length > 0 && (
                  <div className="fragment-editor-diff-section">
                    <div className="fragment-editor-diff-label">{tr("Removed (")}{diff.removed.length})</div>
                    {diff.removed.map((line, i) => (
                      <pre key={`r-${i}`} className="fragment-editor-diff-line removed">- {line}</pre>
                    ))}
                  </div>
                )}
                {diff.added.length > 0 && (
                  <div className="fragment-editor-diff-section">
                    <div className="fragment-editor-diff-label">{tr("Added (")}{diff.added.length})</div>
                    {diff.added.map((line, i) => (
                      <pre key={`a-${i}`} className="fragment-editor-diff-line added">+ {line}</pre>
                    ))}
                  </div>
                )}
                {diff.modified.length > 0 && (
                  <div className="fragment-editor-diff-section">
                    <div className="fragment-editor-diff-label">{tr("Modified (")}{diff.modified.length})</div>
                    {diff.modified.map((line, i) => (
                      <pre key={`m-${i}`} className="fragment-editor-diff-line modified">~ {line}</pre>
                    ))}
                  </div>
                )}
              </div>
            )}
            {error && (
              <div className="fragment-editor-error" role="alert">
                <span className="fragment-editor-error-icon">⚠</span>
                {error}
              </div>
            )}
          </div>

          {/* Description */}
          <div className="fragment-editor-description">
            <label htmlFor="fragment-editor-desc" className="fragment-editor-desc-label">
              {tr("Change description (optional)")}{" "}</label>
            <input
              id="fragment-editor-desc"
              type="text"
              className="fragment-editor-desc-input"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={tr("e.g., Changed condition from b<20 to b<=20")}
              onKeyDown={handleKeyDown}
            />
          </div>

          {/* Footer */}
          <div className="fragment-editor-footer">
            <Dialog.Close asChild>
              <button className="fragment-editor-btn fragment-editor-btn-secondary">
                {tr("Cancel")}{" "}</button>
            </Dialog.Close>
            <button
              className="fragment-editor-btn fragment-editor-btn-primary"
              onClick={handleSave}
              title={tr("The edited SQL opens in its own tab — it is another SQL, not a facet of this one")}
            >
              {tr("Save to a new tab")}{" "}</button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
