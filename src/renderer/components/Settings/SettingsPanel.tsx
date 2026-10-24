import { tr, useLocale } from '../../i18n'
import { useState } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import type { SqlDialect } from '../../types/session'
import './SettingsPanel.css'
import StorageSettings from './StorageSettings'
import { getLocale, getLocalePreferenceError, setLocale, type Locale } from '../../i18n'
import type { StorageHook } from '../../hooks/useStorage'

interface Settings {
  dialect: SqlDialect
  rowLimit: number
  queryTimeout: number
  autoCleanupDays: number
  theme: 'dark' | 'light' | 'high-contrast'
}

interface SettingsPanelProps {
  storage?: StorageHook
  settings: Settings
  onChange: (settings: Settings) => void
  onClose: () => void
}

const DEFAULT_SETTINGS: Settings = {
  dialect: 'postgresql',
  rowLimit: 1000,
  queryTimeout: 30,
  autoCleanupDays: 30,
  theme: 'dark'
}

/**
 * Min/max bounds for each numeric setting. Mirrors the HTML min/max attrs on the
 * <input type="number"> elements below; consumers can rely on `clampNumeric`
 * to enforce them so the UI never reports a value outside the supported range.
 */
const NUMERIC_BOUNDS: Record<'rowLimit' | 'queryTimeout' | 'autoCleanupDays', { min: number; max: number }> = {
  rowLimit: { min: 1, max: 100_000 },
  queryTimeout: { min: 1, max: Number.MAX_SAFE_INTEGER },
  autoCleanupDays: { min: 1, max: 365 },
}

function clampNumeric(key: keyof typeof NUMERIC_BOUNDS, value: number): number {
  const { min, max } = NUMERIC_BOUNDS[key]
  if (!Number.isFinite(value)) return min
  return Math.min(max, Math.max(min, value))
}

export default function SettingsPanel({ settings, onChange, onClose, storage }: SettingsPanelProps) {
  useLocale()
  const [local, setLocal] = useState<Settings>({ ...DEFAULT_SETTINGS, ...settings })

  const handleChange = (key: keyof Settings, value: string | number) => {
    // Local state mirrors the parsed input value verbatim (so transient ""→0
    // after clear() doesn't get re-rendered as the clamped value, which would
    // otherwise bleed into the next typed digit and produce strings like
    // "142" from "42"). The onChange EMIT clamps every numeric field in the
    // snapshot — not just the one being edited — so a sibling edit can't leak
    // a stale out-of-range value into the parent state (CLAUDE.md rule #2 /
    // Task 13 Minor finding).
    const nextLocal = { ...local, [key]: value }
    setLocal(nextLocal)
    const emitted: Settings = { ...nextLocal }
    for (const k of Object.keys(NUMERIC_BOUNDS) as Array<keyof typeof NUMERIC_BOUNDS>) {
      const raw = emitted[k]
      if (typeof raw === 'number') emitted[k] = clampNumeric(k, raw)
    }
    onChange(emitted)
  }

  return (
    <Dialog.Root open onOpenChange={open => { if (!open) onClose() }}>
      <Dialog.Portal container={document.querySelector('.app') as HTMLElement ?? undefined}>
      <Dialog.Overlay className="settings-overlay" />
      <Dialog.Content className="settings-panel settings-dialog" onKeyDown={event => {
        if (event.key === 'Escape' && !event.defaultPrevented) { event.preventDefault(); event.stopPropagation(); onClose() }
      }}>
        <div className="settings-header">
          <Dialog.Title>{tr("Settings")}</Dialog.Title>
          <button className="settings-close" onClick={onClose} title={tr("Close")}>&#10005;</button>
        </div>

        <div className="settings-body">
          <div className="settings-section">
            <label className="settings-label">
              {tr('Language')}
              <select className="settings-select" aria-label={tr('Language')} value={getLocale()}
                onChange={event => setLocale(event.target.value as Locale)}>
                <option value="en">English</option>
                <option value="zh-CN">简体中文</option>
              </select>
            </label>
            {getLocalePreferenceError() && <p role="status">{tr(getLocalePreferenceError()!)}</p>}
          </div>
          <Dialog.Description className="storage-description">{tr("Preferences apply immediately. Storage cleanup uses its own Apply button. Connection and dialect for the current query are shown above the editor.")}</Dialog.Description>
          <div className="settings-section">
            <h3>{tr("Default offline dialect for new queries")}</h3>
            <select
              value={local.dialect}
              onChange={(e) => handleChange('dialect', e.target.value)}
              className="settings-select"
              aria-label={tr("Default offline dialect")}
            >
              <option value="postgresql">PostgreSQL</option>
              <option value="mysql">MySQL</option>
              <option value="sqlserver">SQL Server</option>
              <option value="oracle">Oracle</option>
            </select>
          </div>

          <div className="settings-section">
            <h3>{tr("Execution limits · all queries")}</h3>
            <label className="settings-label">
              {tr("Row limit")}{" "}<input
                type="number"
                value={local.rowLimit}
                onChange={(e) => handleChange('rowLimit', parseInt(e.target.value) || 0)}
                min={NUMERIC_BOUNDS.rowLimit.min}
                max={NUMERIC_BOUNDS.rowLimit.max}
                className="settings-input"
              />
            </label>
            <label className="settings-label">
              {tr("Timeout (seconds)")}{" "}<input
                type="number"
                value={local.queryTimeout}
                onChange={(e) => handleChange('queryTimeout', parseInt(e.target.value) || 0)}
                min={NUMERIC_BOUNDS.queryTimeout.min}
                className="settings-input"
              />
            </label>
          </div>

          {storage ? <StorageSettings storage={storage} legacyDays={settings.autoCleanupDays} /> : <div className="settings-section">
            <h3>{tr("Data Retention")}</h3>
            <label className="settings-label">
              {tr("Auto-cleanup after (days)")}{" "}<input
                type="number"
                value={local.autoCleanupDays}
                onChange={(e) => handleChange('autoCleanupDays', parseInt(e.target.value) || 0)}
                min={NUMERIC_BOUNDS.autoCleanupDays.min}
                max={NUMERIC_BOUNDS.autoCleanupDays.max}
                className="settings-input"
              />
            </label>
          </div>

          }
          <div className="settings-section">
            <h3>{tr("Appearance")}</h3>
            <label className="settings-label">
              {tr("Theme")}{" "}<select
                value={local.theme}
                onChange={(e) => handleChange('theme', e.target.value as 'dark' | 'light' | 'high-contrast')}
                className="settings-select"
              >
                <option value="dark">{tr("Dark")}</option>
                <option value="light">{tr("Light")}</option>
                <option value="high-contrast">{tr("High Contrast")}</option>
              </select>
            </label>
          </div>
        </div>
      </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
