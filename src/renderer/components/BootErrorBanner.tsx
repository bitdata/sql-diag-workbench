import { tr, useLocale } from '../i18n'
import type { FC } from 'react'
import './BootErrorBanner.css'

interface Props {
  /** What failed, in user terms. Defaults to the original PR-2 boot copy. */
  label?: string
  /** The observable error detail (`errorReason`) behind the failure. */
  message: string
  /**
   * Re-run action. Omit when re-running cannot help — the banner then renders
   * `onDismiss` instead of a Retry that would loop (fix round 1, I4: re-running
   * an edit that resolves to the facet being edited produces the same no-op).
   */
  onRetry?: () => void
  retrying?: boolean
  /** Acknowledge-and-clear action for a notice with no retry path. */
  onDismiss?: () => void
  severity?: 'error' | 'info' | 'warning'
  retryLabel?: string
  retryDisabled?: boolean
}

const DEFAULT_LABEL = 'Could not create the first tab'

/**
 * PR-2 (rule #2 observable degradation): when a backend round-trip the app
 * depends on fails, surface the error here with a retry button instead of
 * swallowing it (was `console.warn` only). Template: `.backend-banner` from
 * App.tsx + App.css — same visual language, different copy. No new color
 * tokens; reuses `--error`, `--text-on-accent`, `--font-ui`, `--space-sm`
 * already defined in App.css.
 *
 * `label` is a prop rather than baked in because PR-2 review I-1 mounted a
 * second instance for the session-sync failure — hardcoding "first tab" there
 * would have mislabelled it.
 *
 * `onRetry` is optional since the I4 notice: a stated no-op has nothing to
 * retry, so it renders a Dismiss button on the same slot (same `.boot-error-
 * banner__retry` class — one button style, two verbs; no new CSS).
 */
export const BootErrorBanner: FC<Props> = ({
  label = DEFAULT_LABEL,
  message,
  onRetry,
  retrying = false,
  onDismiss,
  severity = 'error',
  retryLabel = tr("Retry"),
  retryDisabled = false,
}) => {
  useLocale()
  return (
  <div className={`boot-error-banner boot-error-banner--${severity}`} role={severity === 'error' ? 'alert' : 'status'} aria-live={severity === 'error' ? 'assertive' : 'polite'}>
    <span className="boot-error-banner-icon" aria-hidden="true">{severity === 'info' ? 'ⓘ' : '⚠'}</span>
    <span className="boot-error-banner__message">
      {tr(label)}: {message}
    </span>
    {onRetry ? (
      <button
        className="boot-error-banner__retry"
        onClick={onRetry}
        disabled={retrying || retryDisabled}
        type="button"
      >
        {retrying ? tr("Retrying…") : retryLabel}
      </button>
    ) : null}
    {onDismiss ? (
      <button className="boot-error-banner__retry" onClick={onDismiss} disabled={retrying} type="button">
        {tr("Dismiss")}{" "}</button>
    ) : null}
  </div>
)
}
