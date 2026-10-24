import { render, type RenderResult } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { type ReactElement } from 'react'
import { ConnectionProvider } from '../context/ConnectionContext'

interface RenderOptions {
  /** Override the backendUrl passed to ConnectionProvider. Defaults to 'http://test'. */
  backendUrl?: string
}

/**
 * Render a component wrapped in all required React context providers.
 * Returns the standard @testing-library RenderResult plus a pre-configured
 * userEvent instance for simulating user interactions.
 *
 * Currently wraps: ConnectionProvider (the only context in App.tsx).
 */
export function renderWithProviders(
  ui: ReactElement,
  options: RenderOptions = {}
): RenderResult & { user: ReturnType<typeof userEvent.setup> } {
  const backendUrl = options.backendUrl ?? 'http://test'
  const user = userEvent.setup()

  const result = render(
    <ConnectionProvider backendUrl={backendUrl}>
      {ui}
    </ConnectionProvider>
  )

  return { ...result, user }
}
