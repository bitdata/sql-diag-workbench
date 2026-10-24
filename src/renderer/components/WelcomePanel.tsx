import { tr, useLocale } from '../i18n'
import './WelcomePanel.css'

interface WelcomePanelProps {
  onSqlChange: (sql: string) => void
  onParse: () => void
  sql: string
  parseDisabled: boolean
}

export default function WelcomePanel({
  onSqlChange,
  onParse,
  sql,
  parseDisabled
}: WelcomePanelProps) {
  useLocale()
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !parseDisabled) {
      e.preventDefault()
      onParse()
    }
  }

  return (
    <main className="welcome-panel">
      <div className="welcome-content">
        <div className="welcome-editor">
          <textarea
            className="welcome-textarea"
            placeholder={tr("Paste or type a SQL query to get started...")}
            value={sql}
            onChange={(e) => onSqlChange(e.target.value)}
            onKeyDown={handleKeyDown}
            spellCheck={false}
            rows={12}
          />
        </div>
      </div>
    </main>
  )
}
