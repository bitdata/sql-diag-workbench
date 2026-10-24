import { tr, useLocale, getLocale } from '../../i18n'
import * as Dialog from '@radix-ui/react-dialog'
import { useState, useMemo, useRef, useEffect } from 'react'
import { useVirtualizer } from '@tanstack/react-virtual'
import { ExecutionResult, ColumnInfo } from '../../types/execution'
import './DataGrid.css'
import { completenessText, normalizeCompleteness } from '../../utils/resultCompleteness'

interface DataGridProps {
  result: ExecutionResult | null
  loading: boolean
  error: string | null
}

type SortDir = 'asc' | 'desc' | null

export default function DataGrid({ result, loading, error }: DataGridProps) {
  useLocale()
  const [sortCol, setSortCol] = useState<number | null>(null)
  const [sortDir, setSortDir] = useState<SortDir>(null)
  const [widths, setWidths] = useState<Record<number, number>>({})
  const [selected, setSelected] = useState<{ row: number; col: number } | null>(null)
  const [viewValue, setViewValue] = useState<string | null>(null)
  const [clipboardStatus, setClipboardStatus] = useState('')
  useEffect(() => { setSelected(null); setSortCol(null); setSortDir(null); setViewValue(null); setClipboardStatus('') }, [result?.executionId])
  const headerRef = useRef<HTMLDivElement>(null)
  const copy = async (value: string) => {
    try { await navigator.clipboard.writeText(value); setClipboardStatus(tr("Copied")) }
    catch (error) { setClipboardStatus(tr("Copy failed: {0}", error instanceof Error ? error.message : String(error))) }
  }
  const cellText = (value: unknown) => value == null ? 'NULL' : String(value)
  const tsvCell = (value: unknown) => { const text = cellText(value); return /[\t\r\n"]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text }
  const scrollRef = useRef<HTMLDivElement>(null)

  const sortedRows = useMemo(() => {
    if (!result?.rows || sortCol === null || !sortDir) return result?.rows ?? []
    const col = sortCol
    const dir = sortDir === 'asc' ? 1 : -1
    return [...result.rows].sort((a, b) => {
      const va = a[col], vb = b[col]
      if (va == null && vb == null) return 0
      if (va === null || va === undefined) return 1
      if (vb === null || vb === undefined) return -1
      if (typeof va === 'number' && typeof vb === 'number') return (va - vb) * dir
      return String(va).localeCompare(String(vb)) * dir
    })
  }, [result, sortCol, sortDir, getLocale()])

  const handleSort = (colIdx: number) => {
    if (sortCol === colIdx) {
      if (sortDir === 'asc') setSortDir('desc')
      else if (sortDir === 'desc') { setSortDir(null); setSortCol(null) }
      else { setSortDir('asc') }
    } else {
      setSortCol(colIdx)
      setSortDir('asc')
    }
  }

  // Virtualizer — only created when we have rows to render
  const virtualizer = useVirtualizer({
    count: sortedRows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 26,
    overscan: 10,
  })

  // CSS Grid template: row-num (40px) + N data columns (1fr each)
  const gridTemplate = useMemo(() => {
    const colCount = result?.columns?.length ?? 0
    return `40px ${Array.from({length: colCount}, (_, i) => `${widths[i] ?? 160}px`).join(' ')}`
  }, [result?.columns?.length, widths, getLocale()])

  // Loading state
  if (loading) {
    return (
      <div className="datagrid-loading" aria-busy="true">
        {[1, 2, 3, 4, 5].map(i => (
          <div key={i} className="datagrid-skeleton-row">
            {[1, 2, 3, 4].map(j => (
              <div key={j} className="datagrid-skeleton-cell" style={{ width: `${40 + j * 30}px` }} />
            ))}
          </div>
        ))}
      </div>
    )
  }

  // Error state
  if (error && !result) {
    return (
      <div className="datagrid-error" role="alert">
        <span className="error-icon">⚠</span>
        <span className="error-message">{error}</span>
      </div>
    )
  }

  // Empty state
  if (!result) {
    return (
      <div className="datagrid-empty">
        <p className="placeholder-text">{tr("Execute SQL or a fragment to see results")}</p>
      </div>
    )
  }

  // 0 rows returned
  if (result.rowCount === 0 && result.status === 'COMPLETED') {
    return (
      <div className="datagrid-zero-rows">
        <div className="zero-rows-badge">{tr("0 rows returned")}</div>
        <p data-error-reason={normalizeCompleteness(result.resultCompleteness).errorReason ?? undefined}>{completenessText(result.resultCompleteness)}</p>
        <details><summary>{tr("Execution details")}</summary><p title={result.completedAt ?? tr("Observation time unknown")}>{tr("Execution")}{" "}{result.executionId} · {result.completedAt ?? tr("Observation time unknown")}</p></details>
        <p className="zero-rows-hint">
          {tr("This may indicate data elimination at this step. Try executing parent fragments to trace where rows disappear.")}{" "}</p>
        {result.timing && (
          <p className="zero-rows-timing">{tr("Query completed in")}{" "}{result.timing.durationMs}ms</p>
        )}
      </div>
    )
  }

  // Error in result
  if (result.error) {
    return (
      <div className="datagrid-error" role="alert">
        <span className="error-icon">⚠</span>
        <span className="error-message">{result.error}</span>
      </div>
    )
  }

  if (!result.columns || !result.rows) {
    return (
      <div className="datagrid-empty">
        <p className="placeholder-text">{tr("No results to display")}</p>
      </div>
    )
  }

  return (
    <div className="datagrid" role="table" aria-label={tr("Query results")}>
      <div className="grid-actions">
        <span>{tr("Sort applies to loaded rows only")}</span>
        <button onClick={() => { const next: Record<number,number> = {}; result.columns!.forEach((col, i) => { next[i] = Math.min(480, Math.max(100, Math.max(col.name.length, ...sortedRows.slice(0,200).map(row => cellText(row[i]).length)) * 8 + 24)) }); setWidths(next) }}>{tr("Fit columns")}</button>
        <button disabled={!selected} onClick={() => selected && void copy(cellText(sortedRows[selected.row]?.[selected.col]))}>{tr("Copy cell")}</button>
        <button disabled={!selected} onClick={() => selected && void copy((sortedRows[selected.row] ?? []).map(tsvCell).join('\t'))}>{tr("Copy row")}</button>
        <button onClick={() => void copy([result.columns!.map(col => tsvCell(col.name)).join('\t'), ...sortedRows.map(row => row.map(tsvCell).join('\t'))].join('\n'))}>{tr("Copy TSV")}</button>
        <span role="status">{clipboardStatus}</span>
      </div>
      <div className="grid-header-clip">
      {/* Header — sticky, not virtualized */}
      <div
        className="datagrid-header" ref={headerRef}
        role="rowgroup"
        style={{ gridTemplateColumns: gridTemplate, width: 40 + result.columns!.reduce((sum, _, i) => sum + (widths[i] ?? 160), 0) }}
      >
        <div className="datagrid-header-cell header-row-num" role="columnheader">#</div>
        {result.columns.map((col: ColumnInfo, i: number) => (
          <div
            key={i}
            className={`datagrid-header-cell ${sortCol === i ? 'sorted' : ''}`}
            role="columnheader"
            tabIndex={0}
            aria-sort={sortCol === i ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}
            onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); handleSort(i) } }}
            onClick={() => handleSort(i)}
          >
            <div style={{ display: 'flex', alignItems: 'center' }}>
              <span className="datagrid-col-name">{col.name}</span>
              {sortCol === i && (
                <span className="sort-indicator">{sortDir === 'asc' ? ' ▲' : ' ▼'}</span>
              )}
            </div>
            <span className="datagrid-col-type">{col.type}</span>
            <span className="column-resize" role="separator" aria-label={tr("Resize {0}", col.name)} aria-orientation="vertical" tabIndex={0}
              onClick={event => event.stopPropagation()}
              onKeyDown={event => { if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.stopPropagation(); event.preventDefault(); setWidths(w => ({...w, [i]: Math.max(60, (w[i] ?? 160) + (event.key === 'ArrowRight' ? 10 : -10))})) } }}
              onMouseDown={event => {
                event.preventDefault(); event.stopPropagation(); const x = event.clientX, width = widths[i] ?? 160
                const move = (e: MouseEvent) => setWidths(w => ({...w, [i]: Math.max(60, width + e.clientX - x)}))
                const up = () => { document.removeEventListener('mousemove', move); document.removeEventListener('mouseup', up) }
                document.addEventListener('mousemove', move); document.addEventListener('mouseup', up)
              }} />
          </div>
        ))}
      </div>

      </div>
      {/* Body — scrollable, virtualized */}
      <div ref={scrollRef} className="datagrid-body" role="rowgroup" onScroll={event => { if (headerRef.current) headerRef.current.style.transform = `translateX(-${event.currentTarget.scrollLeft}px)` }}>
        <div style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
          {virtualizer.getVirtualItems().map((item) => {
            const row = sortedRows[item.index]
            return (
              <div
                key={item.index}
                className="datagrid-row"
                role="row"
                style={{
                  gridTemplateColumns: gridTemplate,
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  width: 40 + result.columns!.reduce((sum, _, i) => sum + (widths[i] ?? 160), 0),
                  height: item.size,
                  transform: `translateY(${item.start}px)`,
                }}
              >
                <div className="datagrid-cell row-num" role="rowheader">
                  {item.index + 1}
                </div>
                {row.map((cell: unknown, cellIdx: number) => (
                  <div key={cellIdx} className={`datagrid-cell ${selected?.row === item.index && selected.col === cellIdx ? 'selected-cell' : ''}`} role="cell"
                    tabIndex={0} title={cellText(cell)} onFocus={() => setSelected({row: item.index, col: cellIdx})}
                    onClick={() => setSelected({row: item.index, col: cellIdx})}
                    onDoubleClick={() => setViewValue(cellText(cell))}
                    onKeyDown={event => { if (event.key === 'Enter') setViewValue(cellText(cell)); if ((event.ctrlKey || event.metaKey) && event.key === 'c') { event.preventDefault(); void copy(cellText(cell)) } }}>
                    {cell === null ? <span className="null-value">NULL</span> : String(cell)}
                  </div>
                ))}
              </div>
            )
          })}
        </div>
      </div>

      <Dialog.Root open={viewValue !== null} onOpenChange={open => { if (!open) setViewValue(null) }}>
        <Dialog.Portal container={document.querySelector('.app')}><Dialog.Overlay className="settings-overlay" />
          <Dialog.Content className="settings-panel settings-dialog cell-value-dialog">
            <Dialog.Title>{tr("Cell value")}</Dialog.Title><Dialog.Description>{tr("Complete value from the loaded result")}</Dialog.Description>
            <pre>{viewValue}</pre><button onClick={() => void copy(viewValue ?? '')}>{tr("Copy value")}</button><Dialog.Close>{tr("Close")}</Dialog.Close>
          </Dialog.Content></Dialog.Portal>
      </Dialog.Root>
      {/* Footer */}
      <div className="datagrid-footer">
        <span>{tr(result.rowCount === 1 ? "{0} row returned" : "{0} rows returned", result.rowCount)}</span>
        <span data-error-reason={normalizeCompleteness(result.resultCompleteness).errorReason ?? undefined}>{completenessText(result.resultCompleteness)}</span>
        {result.timing && <span>{result.timing.durationMs}ms</span>}
        <details><summary>{tr("Execution details")}</summary><span title={result.completedAt ?? tr("Observation time unknown")}>{tr("Execution")}{" "}{result.executionId} · {result.completedAt ?? tr("Observation time unknown")}</span></details>
      </div>
    </div>
  )
}
