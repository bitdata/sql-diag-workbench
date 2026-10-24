import { tr, useLocale } from '../../i18n'
import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import type { ConnectionConfig } from '../../types/connection'

interface ConnectionManagerProps {
  connections: ConnectionConfig[]
  selectedConnectionId: string | null
  onSelect: (id: string | null) => void
  onManage: () => void
  onEdit?: (id: string) => void
}

export default function ConnectionManager({ connections, selectedConnectionId, onSelect, onManage, onEdit }: ConnectionManagerProps) {
  useLocale()
  const selected = connections.find(c => c.id === selectedConnectionId)
  return <DropdownMenu.Root>
    <DropdownMenu.Trigger className="toolbar-btn" aria-label={tr("Query connection")}
      title={selected ? `${selected.host}:${selected.port}/${selected.database} · selection only; use Test Connection to check connectivity` : tr("Choose a connection for this query")}>
      {selected?.name ?? (selectedConnectionId ? tr("Connection unavailable") : tr("Offline"))} ▾
    </DropdownMenu.Trigger>
    <DropdownMenu.Portal container={document.querySelector('.app') as HTMLElement ?? undefined}>
      <DropdownMenu.Content className="query-connection-menu" sideOffset={4} collisionPadding={8}>
        <DropdownMenu.Item onSelect={() => onSelect(null)}>{tr("Offline · parse only")}</DropdownMenu.Item>
        <DropdownMenu.Separator className="connection-dropdown-divider" />
        {connections.map(c => <DropdownMenu.Item key={c.id} onSelect={() => onSelect(c.id)}>
          <span>{c.id === selectedConnectionId ? '✓ ' : ''}{c.name}</span>
          <small>{c.dialect} · {c.host}:{c.port}/{c.database}</small>
        </DropdownMenu.Item>)}
        {!connections.length && <DropdownMenu.Item disabled>{tr("No connections saved")}</DropdownMenu.Item>}
        <DropdownMenu.Separator className="connection-dropdown-divider" />
        {selected && onEdit && <DropdownMenu.Item onSelect={() => onEdit(selected.id)}>{tr("Edit selected connection…")}</DropdownMenu.Item>}
        <DropdownMenu.Item onSelect={onManage}>{tr("Manage connections…")}</DropdownMenu.Item>
      </DropdownMenu.Content>
    </DropdownMenu.Portal>
  </DropdownMenu.Root>
}
