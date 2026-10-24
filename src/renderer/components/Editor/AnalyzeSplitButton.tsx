import { tr, useLocale } from '../../i18n'
import { useEffect, useState } from 'react'
import { apiFetch } from '../../utils/api'
import type { PlanCapabilities } from '../../types/execution'
import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import './AnalyzeSplitButton.css'

interface AnalyzeSplitButtonProps {
  backendUrl?: string | null
  connectionId?: string | null
  onAnalyze: (mode: 'estimated' | 'actual') => void
  disabled: boolean
  title?: string
}

/**
 * Dropdown for the Analyze action in Performance mode.
 * The trigger button opens the dropdown; selecting a menu item runs that
 * mode. Both items (Estimated Plan / Actual Plan) must be picked from the
 * menu — clicking the trigger alone does NOT start analysis.
 */
export function AnalyzeSplitButton({ onAnalyze, disabled, title, backendUrl, connectionId }: AnalyzeSplitButtonProps) {
  useLocale()
  const [snapshot, setSnapshot] = useState<{ key: string; data: PlanCapabilities | null; error: string | null } | null>(null)
  const [retry, setRetry] = useState(0)
  const key = JSON.stringify([backendUrl, connectionId, retry])
  useEffect(() => {
    let current = true
    if (!backendUrl || !connectionId) return
    void (async () => {
      try {
        const response = await apiFetch(`${backendUrl}/api/connections/${encodeURIComponent(connectionId)}/plan-capabilities`)
        if (!response.ok) throw new Error(`Capability probe failed (HTTP ${response.status})`)
        const data = await response.json() as PlanCapabilities
        if (data.connectionId !== connectionId || !['SUPPORTED', 'UNSUPPORTED', 'UNKNOWN'].includes(data.estimated?.state) || !['SUPPORTED', 'UNSUPPORTED', 'UNKNOWN'].includes(data.actual?.state)) throw new Error('Invalid capability response')
        if (current) setSnapshot({ key, data, error: null })
      } catch (e) { if (current) setSnapshot({ key, data: null, error: e instanceof Error ? e.message : 'Capability probe failed' }) }
    })()
    return () => { current = false }
  }, [key])
  const capabilities = snapshot?.key === key ? snapshot.data : null
  const probeError = snapshot?.key === key ? snapshot.error : null
  const reason = (mode: 'estimated' | 'actual') => capabilities?.[mode].state === 'SUPPORTED' ? null :
    capabilities?.[mode].errorReason ?? probeError ?? tr("Plan capability unknown; retry detection")
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <button
          className="toolbar-btn toolbar-btn-execute analyze-split-trigger"
          disabled={disabled}
          title={title ?? tr("Analyze fragment (open menu to choose estimated or actual)")}
        >
          {tr("⚡ Analyze")}{" "}<span className="chevron">&#9662;</span>
        </button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          className="analyze-dropdown-content"
          sideOffset={4}
          align="start"
        >
          <DropdownMenu.Item
            className="analyze-dropdown-item"
            disabled={!!reason('estimated')}
            onSelect={() => onAnalyze('estimated')}
          >
            <span className="item-icon">&#9889;</span>
            {tr("Estimated Plan")}{" "}</DropdownMenu.Item>
          <DropdownMenu.Item
            className="analyze-dropdown-item"
            disabled={!!reason('actual')}
            onSelect={() => onAnalyze('actual')}
          >
            <span className="item-icon">&#9889;</span>
            {tr("Actual Plan (ANALYZE) — runs query")}{" "}</DropdownMenu.Item>
          <div className="analyze-capability-note" role="status">
            <p>{tr("Actual runs the query. Timeout and Stop request cancellation.")}</p>
            {reason('estimated') && <p>{tr("Estimated:")}{" "}{reason('estimated')}</p>}
            {reason('actual') && <p>{tr("Actual:")}{" "}{reason('actual')}{capabilities?.dialect === 'mysql' ? tr(" — MySQL runtime output is not adapted by this application.") : ''}</p>}
          </div>
          <DropdownMenu.Item className="analyze-dropdown-item" onSelect={event => { event.preventDefault(); setRetry(n => n + 1) }}>{tr("Retry capability detection")}</DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  )
}
