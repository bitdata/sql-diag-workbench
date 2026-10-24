import { tr, useLocale } from '../../i18n'
import type { Tab } from '../../types/tab'
import './TabBar.css'

interface TabBarProps {
  tabs: Tab[]
  activeSessionId: string | null
  onSwitch: (sessionId: string) => void
  onClose: (sessionId: string) => void
  onCreate: () => void
  onContextMenu: (tab: Tab, x: number, y: number) => void
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + '…' : s
}

/**
 * Horizontal tab list (40px high) rendered above main content. Spec §5.2.
 */
export default function TabBar({
  tabs,
  activeSessionId,
  onSwitch,
  onClose,
  onCreate,
  onContextMenu,
}: TabBarProps) {
  useLocale()
  return (
    <div className="tabbar" role="tablist" aria-label={tr("Open sessions")}>
      {tabs.map((tab) => {
        const isActive = tab.sessionId === activeSessionId
        return (
          <div
            key={tab.sessionId}
            className={`tabbar-tab ${isActive ? 'active' : ''}`}
            role="tab"
            aria-selected={isActive}
            tabIndex={isActive ? 0 : -1}
            onKeyDown={event => {
              if (event.target !== event.currentTarget) return
              const index = tabs.indexOf(tab)
              const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : event.key === 'ArrowRight' ? (index + 1) % tabs.length : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length : -1
              if (next >= 0) {
                event.preventDefault(); onSwitch(tabs[next].sessionId)
                event.currentTarget.parentElement?.querySelectorAll<HTMLElement>('[role=tab]')[next]?.focus()
              } else if (event.key === 'Delete') { event.preventDefault(); onClose(tab.sessionId) }
              else if (event.shiftKey && event.key === 'F10') { event.preventDefault(); const rect = event.currentTarget.getBoundingClientRect(); onContextMenu(tab, rect.left, rect.bottom) }
            }}
            onClick={() => onSwitch(tab.sessionId)}
            onContextMenu={(e) => {
              e.preventDefault()
              onContextMenu(tab, e.clientX, e.clientY)
            }}
            title={tab.name}
          >
            <span className="tabbar-tab-name">{truncate(tab.name, 28)}</span>
            {tab.isDirty && (
              <span className="tabbar-tab-dirty" aria-label={tr("Unsaved changes")}>
                •
              </span>
            )}
            <button
              type="button"
              className="tabbar-tab-close"
              aria-label={tr("Close {0}", tab.name)}
              onClick={(e) => {
                e.stopPropagation()
                onClose(tab.sessionId)
              }}
            >
              ×
            </button>
          </div>
        )
      })}
      <button
        type="button"
        className="tabbar-add"
        onClick={onCreate}
        aria-label={tr("New tab")}
        title={tr("New tab")}
      >
        +
      </button>
    </div>
  )
}