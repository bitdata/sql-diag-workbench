// src/renderer/test/testUtils.tsx
import { vi } from 'vitest'

/** Helper to create a mock fetch that returns JSON responses in sequence. */
export function mockFetchResponses(...responses: Array<{ status?: number; body: unknown }>) {
  const calls = responses.map(r =>
    vi.fn().mockResolvedValue({
      ok: (r.status ?? 200) < 400,
      status: r.status ?? 200,
      json: () => Promise.resolve(r.body),
      text: () => Promise.resolve(JSON.stringify(r.body))
    })
  )
  let i = 0
  globalThis.fetch = vi.fn().mockImplementation(() => {
    const handler = calls[Math.min(i, calls.length - 1)]
    i++
    return handler()
  })
  return globalThis.fetch
}

/** Helper to read what was sent to fetch. */
export function lastFetchBody(): unknown {
  const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
  if (calls.length === 0) return null
  const lastCall = calls[calls.length - 1]
  const init = lastCall[1] as RequestInit | undefined
  return init?.body ? JSON.parse(init.body as string) : null
}

/** Reset all mocks between tests. */
export function resetMocks() {
  vi.restoreAllMocks()
  globalThis.fetch = vi.fn().mockRejectedValue(
    new Error('fetch not mocked — override in test')
  )
}

// Re-export renderWithProviders for convenient test imports
export { renderWithProviders } from './renderWithProviders'
