import { useState, useEffect } from 'react'

export interface BackendUrlState {
  /** Resolved backend base URL, or null while unresolved / after failure. */
  url: string | null
  /**
   * True once resolution has SETTLED — whether it produced a URL or failed.
   *
   * Callers that gate behaviour on `url` being non-null need this to tell
   * "not yet" apart from "never": a bare `string | null` return collapses
   * those two into the same value, which is what stranded `booting` at true
   * forever (PR-2 review I-2).
   */
  settled: boolean
  /** Non-null when resolution failed — `url` will stay null. */
  error: string | null
}

/**
 * Backend base URL plus its resolution lifecycle.
 *
 * Rule #2 (observable degradation): a rejected `getBackendUrl()` is reported
 * as `{settled: true, error}`, not silently collapsed into the same `null`
 * that "still loading" produces.
 */
export function useBackendUrlState(): BackendUrlState {
  const [state, setState] = useState<BackendUrlState>({
    url: null,
    settled: false,
    error: null,
  })

  useEffect(() => {
    if (window.electronAPI) {
      window.electronAPI
        .getBackendUrl()
        .then((url) => setState({ url, settled: true, error: null }))
        .catch((e: unknown) => {
          setState({
            url: null,
            settled: true,
            error: e instanceof Error ? e.message : 'Backend URL unavailable',
          })
        })
    } else {
      // In browser mode (non-Electron), backend runs on a fixed port.
      // Vite dev proxy handles /api forwarding, but for static file serving
      // (e.g. python http.server) we need the explicit backend URL.
      setState({ url: 'http://localhost:8080', settled: true, error: null })
    }
  }, [])

  return state
}

/**
 * Convenience wrapper for callers that only need the URL. Prefer
 * {@link useBackendUrlState} when the caller must distinguish "not resolved
 * yet" from "resolution failed".
 */
export function useBackendUrl(): string | null {
  return useBackendUrlState().url
}
