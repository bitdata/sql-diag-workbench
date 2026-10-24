import { tr, useLocale } from '../../i18n'
import { useState } from 'react'
import type { HistoryEntry } from '../../types/session'
import './HistoryDock.css'
import { completenessText, normalizeCompleteness } from '../../utils/resultCompleteness'

interface HistoryDockProps {
  entries: HistoryEntry[]
  loading: boolean
  error?: string | null
  onRefresh: () => void
  /** Open this SQL in a new tab (spec §6.5 / §5.2 History entry integration). */
  onOpenInNewTab?: (sql: string) => void
}

export default function HistoryDock({
  entries,
  loading,
  error,
  onRefresh,
  onOpenInNewTab,
}: HistoryDockProps) {
  useLocale()
  const [expanded, setExpanded] = useState(false)
  return (
    <footer className={`history-dock ${expanded ? 'expanded' : 'collapsed'}`}>
      <div className="history-header">
        <button className="toolbar-btn" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>{tr("History")}{" "}{expanded ? '▾' : '▸'} ({entries.length})</button>
        <button
          className="history-refresh-btn"
          onClick={onRefresh}
          disabled={loading}
          title={tr("Refresh history")}
        >
          &#8635;
        </button>
      </div>
      <div className="history-list" hidden={!expanded}>
        {error && <span role="alert" className="storage-warning">{error} {" "}{tr("Use Refresh to retry.")}{" "}{entries.length > 0 && tr("Showing previously loaded history.")}</span>}
        {entries.length === 0 && !loading && !error && (
          <span className="placeholder-text">{tr("No SQL executed yet")}</span>
        )}
        {loading && entries.length === 0 && (
          <span className="placeholder-text">{tr("Loading...")}</span>
        )}
        {entries.slice(0, 20).map((entry) => (
          <div key={entry.id} className="history-entry">
            <time title={entry.executedAt}>{new Date(entry.executedAt).toLocaleTimeString()}</time><span title={tr("Connection provenance unavailable")}>{tr("Offline restore")}</span>
            <span className="history-sql-text" title={entry.sqlText}>
              {entry.sqlText.length > 60 ? entry.sqlText.slice(0, 60) + '...' : entry.sqlText}
            </span>
            {/* `non_null` strips these keys when the DB column is NULL, so the
                absent key is `undefined`, not `null`; `!= null` tolerates both
                (plain truthiness would wrongly hide a 0ms / 0-row entry). */}
            {entry.durationMs != null && (
              <span className="history-duration">{entry.durationMs}ms</span>
            )}
            {entry.rowCount != null && (
              <span className="history-rows" data-error-reason={normalizeCompleteness(entry.resultCompleteness).errorReason ?? undefined}
                title={tr("Execution {0} · {1}", entry.id, entry.executedAt)}>
                {entry.rowCount} {" "}{tr("rows returned ·")}{" "}{completenessText(entry.resultCompleteness)}
              </span>
            )}
            {onOpenInNewTab && (
              <button
                type="button"
                className="history-open-tab-btn"
                onClick={() => onOpenInNewTab(entry.sqlText)}
                title={tr("Open SQL offline in a new tab; historical connection was not recorded")} aria-label={tr("Open historical SQL offline")}
              >
                ↗
              </button>
            )}
          </div>
        ))}
      </div>
    </footer>
  )
}
