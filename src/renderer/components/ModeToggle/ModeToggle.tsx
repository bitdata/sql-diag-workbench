import { tr, useLocale } from '../../i18n'
import type { WorkspaceMode } from '../../types/mode'
import './ModeToggle.css'

interface ModeToggleProps {
  value: WorkspaceMode
  onChange: (next: WorkspaceMode) => void
}

/**
 * Segmented control that switches the right-area between Data mode (rows
 * + row trace) and Performance mode (plan + diagnostics). Each tab
 * remembers its own mode independently via {@code useTabs.setMode}.
 *
 * <p>Visually follows DESIGN.md: 28px target height, 4px radius, accent
 * (#0078d4) for the active segment, surface (#252526) container, 1px
 * border (#3c3c3c). Transition 150ms ease-out matches the "Short" motion
 * tier.
 */
export default function ModeToggle({ value, onChange }: ModeToggleProps) {
  useLocale()
  return (
    <div
      className="mode-toggle"
      role="radiogroup"
      aria-label={tr("Workspace mode")}
    >
      <button
        type="button"
        role="radio"
        aria-checked={value === 'data'}
        className={`mode-toggle-segment ${value === 'data' ? 'active' : ''}`}
        onClick={() => onChange('data')}
        title={tr("Show query results (rows) and row tracing")}
      >
        {tr("Data")}{" "}</button>
      <button
        type="button"
        role="radio"
        aria-checked={value === 'performance'}
        className={`mode-toggle-segment ${value === 'performance' ? 'active' : ''}`}
        onClick={() => onChange('performance')}
        title={tr("Show execution plan and performance diagnostics")}
      >
        {tr("Performance")}{" "}</button>
    </div>
  )
}