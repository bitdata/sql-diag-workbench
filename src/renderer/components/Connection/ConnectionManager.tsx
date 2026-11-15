import { useEffect, useRef, useState } from 'react'
import { tr, useLocale } from '../../i18n'
import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import * as Dialog from '@radix-ui/react-dialog'
import type { ConnectionConfig } from '../../types/connection'
import './ConnectionDialog.css'
import './ConnectionManager.css'

const RECENT_KEY = 'sql-workbench.recent-connection-ids'
const SHORTCUT_LIMIT = 5

interface ConnectionManagerProps {
  connections: ConnectionConfig[]
  selectedConnectionId: string | null
  onSelect: (id: string | null) => void
  onManage: () => void
  onEdit?: (id: string) => void
  onDelete?: (id: string) => Promise<boolean>
  loading?: boolean
  error?: string | null
  onRetry?: () => void
}

function loadRecent(): { ids: string[]; accessedAt: Record<string, number>; error: string | null } {
  try {
    const raw = localStorage.getItem(RECENT_KEY)
    const parsed: unknown = raw === null ? [] : JSON.parse(raw)
    const legacy = Array.isArray(parsed)
    const value = parsed as { ids?: unknown; accessedAt?: unknown }
    const ids = legacy ? parsed : value?.ids
    if (!Array.isArray(ids) || !ids.every(id => typeof id === 'string')) throw new Error('Invalid recent ids')
    const bounded = [...new Set(ids)].slice(0, SHORTCUT_LIMIT) as string[]
    const times = value?.accessedAt
    if (!legacy && (!times || typeof times !== 'object' || bounded.some(id => !Number.isFinite((times as Record<string, number>)[id])))) throw new Error('Invalid recent times')
    const now = Date.now()
    const accessedAt = Object.fromEntries(bounded.map((id, i) => [id, legacy ? now - i : (times as Record<string, number>)[id]]))
    return { ids: bounded, accessedAt, error: null }
  } catch {
    return { ids: [], accessedAt: {}, error: 'Recent connections could not be loaded. All saved connections remain available below.' }
  }
}

export default function ConnectionManager({ connections, selectedConnectionId, onSelect, onManage, onEdit, onDelete, loading = false, error = null, onRetry }: ConnectionManagerProps) {
  useLocale()
  const [history, setHistory] = useState(loadRecent)
  const [menuOpen, setMenuOpen] = useState(false)
  const [managing, setManaging] = useState(false)
  const [search, setSearch] = useState('')
  const [pendingDelete, setPendingDelete] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const deleteBusy = useRef(false)
  const deleteTrigger = useRef<HTMLButtonElement | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const focusSearchAfterDelete = useRef(false)
  useEffect(() => {
    if (!deleting && focusSearchAfterDelete.current) {
      focusSearchAfterDelete.current = false
      searchRef.current?.focus()
    }
  }, [deleting])
  const triggerRef = useRef<HTMLButtonElement>(null)
  const restoreFocus = useRef(true)
  const selected = connections.find(c => c.id === selectedConnectionId)

  const remember = (id: string) => {
    const ids = [id, ...history.ids.filter(recent => recent !== id)].slice(0, SHORTCUT_LIMIT)
    const accessedAt = Object.fromEntries(ids.map(recent => [recent, recent === id ? Date.now() : history.accessedAt[recent]]))
    let error = history.error
    try { localStorage.setItem(RECENT_KEY, JSON.stringify({ ids, accessedAt })) }
    catch { error = 'Recent connections could not be saved. This session still works; all saved connections remain available below.' }
    setHistory({ ids, accessedAt, error })
  }
  // Restored queries and tab switches count as use, but never record unavailable ids.
  useEffect(() => {
    if (selected && history.ids[0] !== selected.id) remember(selected.id)
  }, [selected?.id])

  const ordered = connections.map((connection, index) => ({ connection, index,
    time: Math.max(history.accessedAt[connection.id] ?? 0, Date.parse(connection.createdAt) || 0) }))
    .sort((a, b) => b.time - a.time || a.index - b.index).map(item => item.connection)
  const shortcuts = ordered.slice(0, SHORTCUT_LIMIT)
  if (selected && !shortcuts.some(c => c.id === selected.id)) shortcuts[SHORTCUT_LIMIT - 1] = selected
  const query = search.trim().toLocaleLowerCase()
  const matches = ordered.filter(c => [c.name, c.host, c.database, c.dialect, String(c.port)]
    .some(value => value.toLocaleLowerCase().includes(query)))
  const confirmDelete = async () => {
    if (!onDelete || !pendingDelete || deleteBusy.current) return
    deleteBusy.current = true
    setDeleting(true)
    setDeleteError(null)
    try {
      if (await onDelete(pendingDelete)) {
        setPendingDelete(null)
        focusSearchAfterDelete.current = true
      } else setDeleteError('Could not delete saved connection.')
    } catch (cause) {
      setDeleteError(cause instanceof Error ? cause.message : 'Could not delete saved connection.')
    } finally {
      deleteBusy.current = false
      setDeleting(false)
    }
  }
  const choose = (id: string) => { remember(id); onSelect(id); setManaging(false) }
  const openManagement = () => { restoreFocus.current = true; setSearch(''); setPendingDelete(null); setManaging(true) }
  const portalContainer = document.querySelector('.app') as HTMLElement ?? undefined

  return <>
    <DropdownMenu.Root open={menuOpen} onOpenChange={setMenuOpen}>
      <DropdownMenu.Trigger ref={triggerRef} className="toolbar-btn" aria-label={tr('Query connection')}
        title={selected ? `${selected.host}:${selected.port}/${selected.database} · selection only; use Test Connection to check connectivity` : tr('Choose a connection for this query')}>
        {selected?.name ?? (selectedConnectionId ? tr('Connection unavailable') : tr('Offline'))} ▾
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal container={portalContainer}>
        <DropdownMenu.Content className="query-connection-menu" sideOffset={4} collisionPadding={8}
          onKeyDown={event => {
            // The prior dialog's dismissable layer may still be finishing cleanup.
            // Close from the focused menu itself instead of relying on layer order.
            if (event.key === 'Escape') { event.preventDefault(); setMenuOpen(false) }
          }}>
          <DropdownMenu.Item onSelect={() => onSelect(null)}>{tr('Offline · parse only')}</DropdownMenu.Item>
          <DropdownMenu.Separator className="connection-dropdown-divider" />
          <DropdownMenu.Label className="connection-shortcut-label">{tr('Quick connections (up to 5)')}</DropdownMenu.Label>
          {shortcuts.map(c => <DropdownMenu.Item key={c.id} onSelect={() => choose(c.id)}>
            <span>{c.id === selectedConnectionId ? '✓ ' : ''}{c.name}</span>
            <small>{c.dialect} · {c.host}:{c.port}/{c.database}</small>
          </DropdownMenu.Item>)}
          {!connections.length && <DropdownMenu.Item disabled>{tr(loading ? 'Loading connections…' : error ? 'Connections unavailable' : 'No connections saved')}</DropdownMenu.Item>}
          <DropdownMenu.Separator className="connection-dropdown-divider" />
          {selected && onEdit && <DropdownMenu.Item onSelect={() => onEdit(selected.id)}>{tr('Edit selected connection…')}</DropdownMenu.Item>}
          <DropdownMenu.Item onSelect={openManagement}>{tr('Manage connections…')}</DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
    <Dialog.Root open={managing} onOpenChange={open => { if (!deleteBusy.current) setManaging(open) }}>
      <Dialog.Portal container={portalContainer}>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog connection-list-dialog" aria-describedby={undefined}
          onEscapeKeyDown={event => { if (deleteBusy.current) event.preventDefault() }}
          onCloseAutoFocus={event => {
            event.preventDefault()
            if (restoreFocus.current) triggerRef.current?.focus()
          }}>
          <Dialog.Title className="dialog-title">{tr('Manage connections')}</Dialog.Title>
          <input disabled={deleting} ref={searchRef} type="search" className="form-input" aria-label={tr('Search connections')}
            placeholder={tr('Search name, host, database or type')} value={search} onChange={e => setSearch(e.target.value)} />
          {history.error && <p role="status" data-degraded="true" className="connection-history-warning">{tr(history.error)}</p>}
          {loading && <p role="status">{tr('Loading connections…')}</p>}
          {error && !deleteError && !pendingDelete && <div role="alert" className="connection-list-error">
            <p>{tr('Could not load saved connections.')}</p><p>{error}</p>
            {onRetry && <button className="dialog-btn dialog-btn-secondary" disabled={loading} onClick={() => { setDeleteError(null); onRetry() }}>{tr('Retry loading')}</button>}
          </div>}
          {deleteError && <div role="alert" className="connection-list-error" data-degraded="true">
            <p>{tr('Could not delete saved connection.')}</p>
            {(error || deleteError !== 'Could not delete saved connection.') && <p>{error || deleteError}</p>}
          </div>}
          <div className="connection-list" aria-busy={loading} aria-label={tr('Saved connections')}>
            {matches.map(c => <div className="connection-list-row" key={c.id}>
              <button disabled={deleting} className="connection-list-select" onClick={() => choose(c.id)} aria-pressed={c.id === selectedConnectionId}>
                <span>{c.id === selectedConnectionId ? '✓ ' : ''}{c.name}</span>
                <small>{c.dialect} · {c.host}:{c.port}/{c.database}</small>
              </button>
              {onEdit && <button disabled={deleting} className="dialog-btn dialog-btn-secondary" onClick={() => { restoreFocus.current = false; setManaging(false); onEdit(c.id) }}>{tr('Edit…')}</button>}
              {onDelete && <button disabled={deleting} className="dialog-btn dialog-btn-danger" onClick={event => {
                deleteTrigger.current = event.currentTarget; setPendingDelete(c.id); setDeleteError(null)
              }}>{tr('Delete')}</button>}
              {pendingDelete === c.id && <div className="connection-delete-confirm" role="group" aria-label={tr('Delete this saved connection?')}>
                <p>{tr('Delete this saved connection?')} <strong>{c.name}</strong></p>
                <p>{tr('Only the saved connection is removed. The database is not deleted.')}</p>
                <div className="dialog-actions">
                  <button autoFocus disabled={deleting} className="dialog-btn dialog-btn-secondary" onClick={() => {
                    setPendingDelete(null); deleteTrigger.current?.focus()
                  }}>{tr('Cancel')}</button>
                  <button disabled={deleting} className="dialog-btn dialog-btn-danger" onClick={() => void confirmDelete()}>{tr(deleting ? 'Deleting…' : 'Confirm delete')}</button>
                </div>
              </div>}
            </div>)}
            {!matches.length && !loading && (!error || connections.length > 0) && <p>{tr(connections.length ? 'No matching connections' : 'No connections saved')}</p>}
          </div>
          <p className="connection-list-hint">{tr('Recent connections appear first. Select to use, or edit or delete directly.')}</p>
          <div className="dialog-actions">
            <Dialog.Close asChild><button disabled={deleting} className="dialog-btn dialog-btn-secondary">{tr('Close')}</button></Dialog.Close>
            <button disabled={deleting} className="dialog-btn dialog-btn-primary" onClick={() => { restoreFocus.current = false; setManaging(false); onManage() }}>{tr('New Connection')}</button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  </>
}
