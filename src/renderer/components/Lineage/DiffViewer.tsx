import { tr, useLocale } from '../../i18n'
import type { ChangeOp } from '../../hooks/useChangeAttribution'
import type { DiffMethod } from '../../utils/diff'
import './DiffViewer.css'

interface DiffViewerProps {
  parentSql: string
  currentSql: string
  ops: ChangeOp[]
  loading: boolean
  error: string | null
  /** True when the backend fell back from LCS to path-set diff. */
  degraded?: boolean
  /** Backend-reported diff method (spec §3.3 L2 union). */
  diffMethod?: DiffMethod | ''
}

/**
 * Surfaces a warning when the backend's DiffResponse.degraded is true — i.e.
 * LCS skipped → path-set fallback was used. Spec §3.3 L2 / P2-13 close-out.
 * The badge is rendered above the SQL blocks so the user sees the precision
 * drop before reading the (possibly approximate) attribution.
 */
function DegradedBadge({
  degraded,
  method,
}: {
  degraded: boolean
  method: string
}) {
  useLocale()
  if (!degraded) return null
  return (
    <div className="degraded-badge" role="status" aria-label={tr("Diff precision degraded")}>
      {tr("⚠ Approximate diff (method:")}{" "}{method}{tr(") — verification recommended")}{" "}</div>
  )
}

/**
 * Renders parent SQL / current SQL with ops highlighted.
 * Spec §5.2.2 — backend ChangeOp set drives the highlights.
 * Token-level highlight is approximated: for MOD ops, both old/new text are
 * shown inline below; for ADD/DEL, the text fragment is highlighted in the
 * corresponding SQL block.
 *
 * When the backend's DiffResponse.degraded is true (LCS skipped → path-set
 * fallback), a DegradedBadge surfaces the warning above the SQL blocks so the
 * user knows the attributed ops are approximate.
 */
export default function DiffViewer({
  parentSql,
  currentSql,
  ops,
  loading,
  error,
  degraded = false,
  diffMethod = '',
}: DiffViewerProps) {
  useLocale()
  if (loading) {
    return <div className="diff-viewer-empty">{tr("Computing diff…")}</div>
  }
  if (error) {
    return <div className="diff-viewer-empty">{tr("Diff error:")}{" "}{error}</div>
  }

  const adds = ops.filter((o) => o.kind === 'ADD' && o.newText)
  const dels = ops.filter((o) => o.kind === 'DEL' && o.oldText)
  const mods = ops.filter((o) => o.kind === 'MOD')

  const renderHighlighted = (text: string, kind: 'add' | 'del') => {
    const fragments = kind === 'add' ? adds : dels
    if (fragments.length === 0) return <>{text}</>
    // Naive: wrap each fragment's text in a span if it appears literally.
    const parts: React.ReactNode[] = []
    let remaining = text
    fragments.forEach((op, i) => {
      const needle = kind === 'add' ? op.newText! : op.oldText!
      const idx = remaining.indexOf(needle)
      if (idx === -1) return
      parts.push(remaining.slice(0, idx))
      parts.push(
        <span key={`${kind}-${i}`} className={`diff-viewer-op-${kind}`}>
          {needle}
        </span>
      )
      remaining = remaining.slice(idx + needle.length)
    })
    parts.push(remaining)
    return <>{parts}</>
  }

  return (
    <div className="diff-viewer">
      <DegradedBadge degraded={degraded} method={diffMethod} />
      <div>
        <div className="diff-viewer-section-label">{tr("parent SQL")}</div>
        <pre className="diff-viewer-section">{renderHighlighted(parentSql, 'del')}</pre>
      </div>
      <div>
        <div className="diff-viewer-section-label">{tr("current SQL")}</div>
        <pre className="diff-viewer-section">{renderHighlighted(currentSql, 'add')}</pre>
      </div>
      {mods.length > 0 && (
        <div className="diff-viewer-summary">
          {mods.length} {" "}{tr("modification")}{mods.length > 1 ? 's' : ''}:
          {mods.map((m, i) => (
            <div key={i} className="diff-viewer-op-mod diff-viewer-mod-row">
              {m.path}: <code>{m.oldText}</code> → <code>{m.newText}</code>
            </div>
          ))}
        </div>
      )}
      {ops.length === 0 && (
        <div className="diff-viewer-empty">{tr("No changes detected")}</div>
      )}
    </div>
  )
}
