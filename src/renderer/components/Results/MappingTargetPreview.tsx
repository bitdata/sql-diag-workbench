import { useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { tr, useLocale } from '../../i18n'
import type { PlanSqlMapping } from '../../types/execution'
import { unavailable, type MappingAvailability, type MappingPreviewSource, type MappingTarget } from '../../utils/mappingTargets'

export default function MappingDetails({ mapping, path, source }: {
  mapping?: PlanSqlMapping | null; path?: string; source?: MappingPreviewSource
}) {
  useLocale()
  const [selection, setSelection] = useState<{ key: string; path: string | undefined; ordinal: number } | null>(null)
  const [error, setError] = useState<MappingAvailability | null>(null)
  const buttons = useRef(new Map<number, HTMLButtonElement>())
  const section = useRef<HTMLElement>(null)
  useLayoutEffect(() => { setSelection(null); setError(null) }, [source?.snapshot.key, path])
  const selected = selection?.key === source?.snapshot.key && selection?.path === path ? selection : null
  const resolution = selected && source ? source.resolve(selected.ordinal) : null
  const close = () => {
    setSelection(null); setError(null)
    buttons.current.get(selected?.ordinal ?? -1)?.focus({ preventScroll: true })
  }
  const edges = mapping?.edges.map((edge, ordinal) => ({ edge, ordinal })).filter(({ edge }) => edge.planNodePath === path) ?? []
  return <section ref={section} className="plan-mapping" aria-label={tr('SQL and fragment mapping')}>
    <h4>{tr('SQL and fragment mapping')}</h4>
    {mapping?.degraded && <p role="alert">{tr('Mapping degraded:')} {mapping.errorReason ?? tr('Unknown reason')}</p>}
    {mapping?.issues.map((issue, index) => <p key={index}>{issue.code} · {mappingText(issue.message)}</p>)}
    {!mapping && <p>{tr('Mapping unavailable')}</p>}
    {mapping && edges.length === 0 && <p>{tr('No mapping evidence for this plan node')}</p>}
    {edges.map(({ edge, ordinal }) => {
      const target = source?.resolve(ordinal) ?? unavailable('MAPPING_TARGET_MISSING', 'The mapping target is unavailable.')
      const label = edge.targetKind === 'SQL_SPAN' ? 'View submitted SQL' : edge.targetKind === 'FRAGMENT' ? 'View fragment' : 'View evidence'
      return <div className="plan-mapping-edge" data-edge-ordinal={ordinal} key={ordinal}>
        <p><strong>{edge.confidence}</strong> · {edge.targetKind} · {edge.span?.nodeId === 'submitted_root' ? tr('Submitted SQL') : edge.span?.label ?? edge.targetId ?? tr('Unknown target')} · {mappingText(edge.reason)}{edge.span ? ` · ${edge.span.startOffset}:${edge.span.endOffset}` : ''}</p>
        {target.status === 'READY' ? <button type="button" ref={button => { if (button) buttons.current.set(ordinal, button); else buttons.current.delete(ordinal) }}
          aria-expanded={selected?.ordinal === ordinal} onClick={() => {
            const checked = source!.resolve(ordinal)
            if (checked.status !== 'READY') { setError(checked); return }
            setError(null); setSelection({ key: source!.snapshot.key, path, ordinal })
          }}>{tr(label)}</button> : <Unavailable value={target} />}
      </div>
    })}
    {error && <Unavailable value={error} />}
    {selected && resolution?.status === 'READY' && source && <MappingTargetPreview key={`${selected.key}:${selected.ordinal}`}
      target={resolution.target} source={source} ordinal={selected.ordinal} onClose={close}
      onLostFocus={() => section.current?.closest<HTMLElement>('.plan-node-details')?.focus({ preventScroll: true })} />}
  </section>
}

function Unavailable({ value }: { value: MappingAvailability }) {
  return value.status === 'UNAVAILABLE' ? <p className="mapping-unavailable" role="status">{tr(value.message)} <code>{value.code}</code></p> : null
}

function MappingTargetPreview({ target, source, ordinal, onClose, onLostFocus }: {
  target: MappingTarget; source: MappingPreviewSource; ordinal: number; onClose: () => void; onLostFocus: () => void
}) {
  const container = useRef<HTMLElement>(null), sqlArea = useRef<HTMLPreElement>(null), mark = useRef<HTMLElement>(null)
  const [revealStatus, setRevealStatus] = useState<MappingAvailability | null>(null)
  useLayoutEffect(() => {
    const element = container.current
    const panel = element?.closest<HTMLElement>('.results-panel')
    const host = element?.closest<HTMLElement>('.plan-view')
    const originalScroll = host?.scrollTop
    // Reveal the new detail inside the plan pane, then restore the caller's
    // position on close. Never scroll the editor or the whole window.
    if (element && host) host.scrollTop += element.getBoundingClientRect().top - host.getBoundingClientRect().top
      - (host.querySelector('.plan-header')?.getBoundingClientRect().height ?? 0) - 8
    element?.focus({ preventScroll: true })
    if (sqlArea.current && mark.current) {
      const area = sqlArea.current, rect = area.getBoundingClientRect(), highlight = mark.current.getBoundingClientRect()
      area.scrollTop += highlight.top - rect.top - 8
      area.scrollLeft += highlight.left - rect.left - 8
    }
    return () => {
      if (host && originalScroll !== undefined) host.scrollTop = originalScroll
      if (element?.contains(document.activeElement)) {
        onLostFocus()
        // A new execution can remove the entire old evidence area in this
        // commit. Restore focus after that removal, without stealing it from
        // another control the user has already chosen.
        queueMicrotask(() => {
          if (document.activeElement === document.body)
            panel?.querySelector<HTMLElement>('[role="tab"][data-state="active"]')?.focus({ preventScroll: true })
        })
      }
    }
  }, [])
  const keys = (event: KeyboardEvent) => {
    const command = event.ctrlKey || event.metaKey
    if (event.key === 'Escape' || event.key === 'F5' || (command && (event.key === 'Enter'
        || event.key.toLowerCase() === 'n' || (event.shiftKey && event.key.toLowerCase() === 'f')))) {
      event.preventDefault(); event.stopPropagation()
      if (event.key === 'Escape') onClose()
    }
  }
  const check = target.kind === 'FRAGMENT' && target.reveal ? source.checkReveal?.(target.reveal)
    ?? unavailable('MAPPING_TREE_TARGET_NOT_VISIBLE', 'This fragment is not shown in the current tree.') : null
  return <section className="mapping-preview" tabIndex={-1} ref={container} onKeyDown={keys} aria-label={tr('Mapping target preview')}>
    <div className="mapping-preview-tools"><strong>{tr(source.mode === 'archive' ? 'Historical observation only' : 'Request-time snapshot')}</strong><button type="button" onClick={onClose}>{tr('Close preview')}</button></div>
    <p>{source.snapshot.executionId} · {source.snapshot.dialect}</p>
    {target.kind === 'SQL_SPAN' && <>
      <p>{tr('Submitted SQL')} · {target.start}:{target.end}</p>
      <pre tabIndex={0} ref={sqlArea} aria-label={tr('Submitted SQL preview')}>{target.sql.slice(0, target.start)}<mark ref={mark}>{target.sql.slice(target.start, target.end)}</mark>{target.sql.slice(target.end)}</pre>
    </>}
    {target.kind === 'FRAGMENT' && <>
      <p>{tr('Inferred candidate')} · {target.fragment.label} · {target.fragment.type}</p>
      <pre tabIndex={0} aria-label={tr('Fragment SQL preview')}>{target.fragment.sqlFragment}</pre>
      {source.mode !== 'archive' && <><p>{tr('Locate expands and scrolls to the first matching occurrence without selecting it.')}</p>
      <button type="button" disabled={check?.status !== 'READY'} onClick={() => {
        const resolved = source.resolve(ordinal)
        if (resolved.status !== 'READY') { setRevealStatus(resolved); return }
        if (resolved.target.kind !== 'FRAGMENT' || !resolved.target.reveal) { setRevealStatus(unavailable('MAPPING_PREVIEW_STALE', 'This mapping preview is no longer current.')); return }
        setRevealStatus(source.reveal?.(resolved.target.reveal)
          ?? unavailable('MAPPING_TREE_TARGET_NOT_VISIBLE', 'This fragment is not shown in the current tree.'))
      }}>{tr('Locate in tree')}</button>
      {check && <Unavailable value={check} />}
      {revealStatus?.status === 'READY' && check?.status === 'READY' ? <p role="status">{tr('Expanded and scrolled to the fragment.')}</p> : revealStatus && <Unavailable value={revealStatus} />}</>}
    </>}
    {target.kind === 'DIAGNOSTIC_EVIDENCE' && <>
      <p>{tr('Client-supplied evidence candidate')}</p>
      <h4>{tr(target.finding.title)}</h4><p>{target.finding.severity} · {tr({ SUSPECTED: 'Suspected', CONFIRMED: 'Confirmed', REFUTED: 'Refuted' }[target.finding.status])} · {target.finding.confidence}</p>
      <p>{tr(target.finding.message)}</p>
      <pre tabIndex={0} aria-label={tr('Diagnostic evidence preview')}>{target.finding.evidence.length ? target.finding.evidence.join('\n') : tr('Evidence unavailable')}</pre>
      <p>{tr('Verification result')}: {target.finding.verificationResult ?? tr('Not available')}</p>
      <p>{tr('Error reason')}: {target.finding.errorReason ?? tr('Not available')}</p>
    </>}
  </section>
}

/** Preserve SQL, identifiers and original database errors embedded in explanations. */
function mappingText(message: string): string {
  const prefixes = ['fragment identity or execution is degraded: ', 'fragment identity degraded: ',
    'AST relation candidate: ', 'fragment SQL AST could not be read: ', 'retained plan observation is ']
  for (const prefix of prefixes) if (message.startsWith(prefix)) return tr(`${prefix}{0}`, message.slice(prefix.length))
  return tr(message)
}
