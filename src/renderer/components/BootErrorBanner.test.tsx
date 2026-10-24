// src/renderer/components/BootErrorBanner.test.tsx
//
// PR-2 (rule #3 build acceptance — real DOM, not snapshot). Pin:
//  - role="alert" surfaces the failure to assistive tech
//  - aria-live="assertive" so the announcement interrupts
//  - Retry button is enabled by default and disabled while retrying
//  - Click triggers onRetry handler
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BootErrorBanner } from './BootErrorBanner'

describe('BootErrorBanner (PR-2 rule #3 smoke — real DOM)', () => {
  it.each(['info', 'warning'] as const)('announces %s notices politely with a dismiss action', severity => {
    render(<BootErrorBanner label="No changes to open" message="No new tab" severity={severity} onDismiss={vi.fn()} />)
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull()
  })

  it('supports a stage-specific retry and a disabled recovery with Dismiss', () => {
    render(<BootErrorBanner message="Return to source" onRetry={vi.fn()} retryLabel="Retry opening tab" retryDisabled onDismiss={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Retry opening tab' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeEnabled()
  })
  it('renders message + Retry button (role=alert, real DOM assertions)', async () => {
    const onRetry = vi.fn()
    render(<BootErrorBanner message="boom" onRetry={onRetry} retrying={false} />)

    // role=alert surfaces the failure (spec §6.3 G3 real-DOM smoke)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent(/Could not create the first tab: boom/)

    // aria-live=assertive so the announcement interrupts the user
    expect(alert).toHaveAttribute('aria-live', 'assertive')

    const button = screen.getByRole('button', { name: 'Retry' })
    expect(button).toBeEnabled()

    const user = userEvent.setup()
    await user.click(button)
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('disables Retry button and shows Retrying… label while retrying=true', () => {
    render(<BootErrorBanner message="still boom" onRetry={vi.fn()} retrying={true} />)

    const button = screen.getByRole('button', { name: 'Retrying…' })
    expect(button).toBeDisabled()
  })

  // PR-2 review I-1 mounted a second instance for the session-sync failure.
  // Hardcoding "first tab" made that banner mislabel its own failure.
  it('uses a caller-supplied label instead of the boot copy', () => {
    render(
      <BootErrorBanner
        label="Could not save this workspace to the backend"
        message="HTTP 503. Your work is still kept locally."
        onRetry={vi.fn()}
        retrying={false}
      />
    )

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent(
      'Could not save this workspace to the backend: HTTP 503. Your work is still kept locally.'
    )
    expect(alert).not.toHaveTextContent('first tab')
  })
})
