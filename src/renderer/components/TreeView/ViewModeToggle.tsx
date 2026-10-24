import { tr, useLocale } from '../../i18n'
import './ViewModeToggle.css'

type ViewMode = 'tree' | 'slider'

interface ViewModeToggleProps {
  mode: ViewMode
  onChange: (mode: ViewMode) => void
}

export default function ViewModeToggle({ mode, onChange }: ViewModeToggleProps) {
  useLocale()
  return (
    <div className="view-mode-toggle">
      <button
        className={`view-btn ${mode === 'tree' ? 'active' : ''}`}
        onClick={() => onChange('tree')}
        title={tr("Tree view")}
      >
        {tr("▮▮ Tree")}{" "}</button>
      <button
        className={`view-btn ${mode === 'slider' ? 'active' : ''}`}
        onClick={() => onChange('slider')}
        title={tr("Slider view")}
      >
        {tr("—▶ Slider")}{" "}</button>
    </div>
  )
}