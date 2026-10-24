import { tr, useLocale, getLocale } from '../../i18n'
import { useState, useEffect, useMemo, useCallback } from 'react'
import { ExecutionStatus } from '../../types/execution'
import type { WorkspaceMode } from '../../types/mode'
import type { ExecutionResult } from '../../types/execution'
import './ExecutionStatusInline.css'
import { completenessText } from '../../utils/resultCompleteness'

interface Props {
  loading: boolean
  status: ExecutionStatus | null
  error: string | null
  dataResult: ExecutionResult | null
  planResult: ExecutionResult | null
  startedAt: number | null
  mode: WorkspaceMode
  queryTimeoutSeconds: number
  onCancel: () => void
}

/** Queries running longer than this (seconds) flip the elapsed text to warning color. */
const SLOW_QUERY_THRESHOLD_SECONDS = 30
/** How long the "✓ completed" line stays at full opacity after success before fading. */
const FADE_DELAY_MS = 5000

function fmtElapsed(ms: number): string {
  if (ms < 1000) return `${(ms / 1000).toFixed(1)}s`
  const s = Math.floor(ms / 1000)
  const rem = ms % 1000
  return rem === 0 ? `${s}s` : `${s}.${Math.floor(rem / 100)}s`
}

function truncate(str: string, len: number): string {
  return str.length > len ? str.slice(0, len - 1) + '...' : str
}

/**
 * Compact status indicator embedded in the toolbar's execute group.
 *
 * During execution it shows elapsed time and turns warning color past
 * {@code SLOW_QUERY_THRESHOLD_SECONDS}. After success it shows duration +
 * row count. The COMPLETED state mounts with the fade class already on
 * (CSS animates 1 → 0.5 over the transition); after {@code FADE_DELAY_MS}
 * the class is removed and opacity returns to 1. While loading, clicking
 * the indicator cancels the in-flight execution.
 *
 * Renders nothing when there's no status to show (idle / before first run).
 */
export default function ExecutionStatusInline({
  loading,
  status,
  error,
  dataResult,
  planResult,
  startedAt,
  mode,
  queryTimeoutSeconds,
  onCancel,
}: Props) {
  useLocale()
  const [elapsedMs, setElapsedMs] = useState(0)
  const [fade, setFade] = useState(false)

  // 1Hz ticker while loading. Cleared on unmount or when loading flips off.
  useEffect(() => {
    if (!loading || !startedAt) return
    const update = () => setElapsedMs(Date.now() - startedAt)
    const id = setInterval(update, 1000)
    update()
    return () => clearInterval(id)
  }, [loading, startedAt])

  // After successful completion, fade the indicator to 0.5 opacity after
  // FADE_DELAY_MS. Reverts when the next run starts (status leaves COMPLETED).
  useEffect(() => {
    if (!loading && status === 'COMPLETED') {
      setFade(true)
      const id = setTimeout(() => setFade(false), FADE_DELAY_MS)
      return () => clearTimeout(id)
    }
  }, [loading, status])

  // While loading show live elapsed; after completion show the server-reported
  // duration from whichever result matches the active mode.
  const displayMs = useMemo(() => {
    if (loading) return elapsedMs
    const result = mode === 'data' ? dataResult : planResult
    return result?.timing?.durationMs ?? null
  }, [loading, elapsedMs, mode, dataResult, planResult, getLocale()])

  const elapsedSec = Math.floor((displayMs ?? 0) / 1000)
  const durationText = displayMs === null ? tr("Elapsed unknown") : fmtElapsed(displayMs)
  const slowQuery = elapsedSec >= SLOW_QUERY_THRESHOLD_SECONDS

  const handleClick = useCallback(() => {
    if (loading) onCancel()
  }, [loading, onCancel])

  if (!status && !loading) return null

  let text: string
  let colorClass: string
  let clickable = false

  if (loading) {
    text = tr("▶ Executing... {0}", durationText)
    colorClass = slowQuery ? 'execution-status-warn' : 'execution-status-normal'
    clickable = true
  } else if (status === 'COMPLETED') {
    const rows = dataResult?.rowCount ?? 0
    if (mode === 'data' && dataResult) {
      text = tr("✓ {0} · {1} rows returned · {2}", durationText, rows.toLocaleString(getLocale()), completenessText(dataResult.resultCompleteness))
    } else {
      text = tr("✓ Application elapsed: {0}", durationText)
    }
    colorClass = 'execution-status-success'
  } else if (status === 'TIMED_OUT') {
    text = tr("⚠ Timed out after {0}s", queryTimeoutSeconds)
    colorClass = 'execution-status-warn'
  } else if (status === 'FAILED') {
    text = `✗ ${truncate(error || tr("Failed"), 60)}`
    colorClass = 'execution-status-error'
  } else if (status === 'CANCELLED') {
    // spec §5.3：后端 (B) 起在 CANCELLED 上发布原因，展示它（截断方式与 FAILED 一致）。
    // 取不到原因时（条目已回收 / 取消请求本身失败）回落到原来的固定文案。
    text = `— ${truncate(error || tr("Cancelled"), 60)}`
    colorClass = 'execution-status-muted'
  } else {
    return null
  }

  return (
    <span
      className={`execution-status-inline ${colorClass}${fade ? ' execution-status-fade' : ''}`}
      onClick={handleClick}
      role={clickable ? 'button' : undefined}
      tabIndex={clickable ? 0 : -1}
      aria-label={clickable ? tr("Cancel execution") : undefined}
      title={error || text}
    >
      {text}
    </span>
  )
}
