import { tr, useLocale } from '../../i18n'
import { describeReverseIndexReason, type ReverseIndexDegradedReason } from '../../utils/dagReverseIndex'
import './BuildPanel.css'

export interface BuildCandidateRow {
  parentId: string
  label: string
  revealed: boolean
}

interface BuildPanelProps {
  seedLabel: string
  candidates: BuildCandidateRow[]
  /** Parent ids the index knows but the DAG cannot resolve (spec §5.3). */
  skipped: string[]
  /** Index-level degradation; the panel renders its copy, not its own. */
  degraded: boolean
  reason: ReverseIndexDegradedReason | null
  onToggleCandidate: (parentId: string) => void
  onClose: () => void
}

/**
 * Build Upward (DESIGN.md, 2026-04-26) re-cut as the add-back picker: the
 * candidates are the REAL parent facets of the seed, read from the DAG reverse
 * index, one hop at a time. Toggling one reveals that real node in the add-back
 * exploration state — this panel never creates nodes and never writes to the
 * top-down tree (spec §4.4 / §4.3.7).
 *
 * Every empty/degraded case is stated, never silent (CLAUDE.md rule #2):
 * unresolvable parents render as disabled rows, an incomplete index renders its
 * reason, and a facet with no in-edges is named for what it is.
 *
 * Non-modal (spec §5.1): the panel docks bottom-right and the container takes no
 * pointer events, so clicks outside the panel reach the real controls underneath
 * (the banner button, the tree's context menu) instead of being intercepted.
 */
export default function BuildPanel({
  seedLabel,
  candidates,
  skipped,
  degraded,
  reason,
  onToggleCandidate,
  onClose,
}: BuildPanelProps) {
  useLocale()
  const notice = describeReverseIndexReason(reason)
  const skippedNotice = describeReverseIndexReason('PARENT_NOT_IN_DAG')
  const nothingToAdd = candidates.length === 0 && skipped.length === 0 && !degraded

  return (
    <div className="build-panel-overlay">
      <div className="build-panel" role="region" aria-label={tr("Build Upward")}>
        <div className="build-panel-header">
          <h2>{tr("Build Upward")}</h2>
          <button className="build-panel-close" onClick={onClose} aria-label={tr("Close")} title={tr("Close")}>&#10005;</button>
        </div>

        <div className="build-panel-body">
          <div className="build-section">
            <div className="build-label">
              <span>{tr("Seed:")}{" "}{seedLabel}</span>
            </div>
            <p className="build-hint">
              {tr("Pick a parent facet to add back. Each pick reveals one hop; candidates come from every DAG in-edge, not just the tree parent.")}{" "}</p>
          </div>

          <div className="build-section">
            <h3>{tr("Available parent facets")}</h3>
            {degraded && notice && (
              <p className="build-degraded" role="status">{notice}</p>
            )}
            {nothingToAdd ? (
              <p className="build-empty">{tr("This is the original SQL; no parent facets to add back")}</p>
            ) : (
              <div className="build-toggles">
                {candidates.map((c) => (
                  <label className="build-toggle" key={c.parentId}>
                    <input
                      type="checkbox"
                      aria-label={c.label}
                      checked={c.revealed}
                      onChange={() => onToggleCandidate(c.parentId)}
                    />
                    <span>{c.label}</span>
                  </label>
                ))}
                {skipped.map((parentId) => (
                  <label className="build-toggle build-toggle-disabled" key={parentId}>
                    <input
                      type="checkbox"
                      aria-label={`${parentId} — ${skippedNotice}`}
                      checked={false}
                      disabled
                      readOnly
                    />
                    <span>{parentId} — {skippedNotice}</span>
                  </label>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
