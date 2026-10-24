/**
 * S1b — renderer-side auth. initApiAuth() runs once at startup and fetches
 * the per-launch token from the Electron main process via preload IPC.
 * apiFetch() is the ONLY way renderer code talks to the backend: it attaches
 * the X-Auth-Token header that AuthTokenInterceptor requires on /api/**, and
 * it owns the client-side deadline (spec 2026-10-08 §4.2).
 *
 * Browser mode (no electronAPI, e.g. plain vite dev server) gets no token —
 * requests against a token-enforcing backend are rejected with 401, which is
 * the intended behavior: the shipped product is always Electron.
 */
const AUTH_HEADER = 'X-Auth-Token'

/** Deadline for the request families that must never be allowed to hang (spec §4.2). */
export const DEFAULT_API_TIMEOUT_MS = 30_000

export interface ApiFetchOptions {
  /**
   * Client-side deadline. Omitted ⇒ {@link DEFAULT_API_TIMEOUT_MS} — deliberately
   * fail-closed, so a call site that forgets to declare itself gets a bound rather than a
   * hang. `null` ⇒ no deadline of our own, for the long calls that legitimately outlive 30s
   * (query execution, row tracing, diagnostics verification).
   */
  timeoutMs?: number | null
}

/**
 * Raised when {@link apiFetch}'s own deadline fires. Deliberately distinct from the
 * caller's AbortError: the hooks distinguish "the user's input changed, stop" (AbortError)
 * from "the backend never answered" (this).
 */
export class ApiTimeoutError extends Error {
  readonly timeoutMs: number

  constructor(timeoutMs: number) {
    super(`Request timed out after ${Math.round(timeoutMs / 1000)}s`)
    this.name = 'ApiTimeoutError'
    this.timeoutMs = timeoutMs
  }
}

let authToken: string | null = null

export async function initApiAuth(): Promise<void> {
  try {
    const token = await window.electronAPI?.getAuthToken?.()
    authToken = token && token.length > 0 ? token : null
  } catch {
    authToken = null
  }
}

export function apiFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
  opts?: ApiFetchOptions
): Promise<Response> {
  // Call shape is preserved as far as the feature allows: the caller's `headers` stay whatever
  // they passed — they are only wrapped into a Headers instance when there is a token to add.
  // The ONE unavoidable delta is the `signal`, because tearing the socket down at the deadline
  // requires it to reach fetch.
  let headers: HeadersInit | undefined = init?.headers
  if (authToken) {
    const withToken = new Headers(init?.headers)
    withToken.set(AUTH_HEADER, authToken)
    headers = withToken
  }

  const timeoutMs = opts?.timeoutMs === undefined ? DEFAULT_API_TIMEOUT_MS : opts.timeoutMs
  const callerSignal = init?.signal ?? null

  if (timeoutMs === null) {
    // Explicit opt-out: the caller's signal is the only abort source — byte-for-byte the
    // pre-deadline call.
    if (!authToken) {
      return init === undefined ? fetch(input) : fetch(input, init)
    }
    return fetch(input, { ...init, headers })
  }

  // Still needed after the deadline was added, for the "input changed, stop" case the
  // hooks already rely on. AbortSignal.any is deliberately NOT used — this repo has no
  // anchor for it on the Electron/Chromium build it ships (spec §4.2).
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | null = null
  const onCallerAbort = () => {
    controller.abort()
  }

  return new Promise<Response>((resolve, reject) => {
    const cleanup = () => {
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
      callerSignal?.removeEventListener('abort', onCallerAbort)
    }

    timer = setTimeout(() => {
      cleanup()
      // Tear the socket down, then surface our own error — the browser's AbortError carries
      // no deadline information for the UI.
      controller.abort()
      reject(new ApiTimeoutError(timeoutMs))
    }, timeoutMs)

    if (callerSignal) {
      if (callerSignal.aborted) {
        // Aborted before we started: do not arm the deadline.
        cleanup()
        controller.abort()
      } else {
        callerSignal.addEventListener('abort', onCallerAbort, { once: true })
      }
    }

    const bounded: RequestInit = { ...init, signal: controller.signal }
    if (headers !== undefined) {
      bounded.headers = headers
    }

    fetch(input, bounded).then(
      (response) => {
        cleanup()
        resolve(response)
      },
      (error) => {
        cleanup()
        reject(error)
      }
    )
  })
}

/** Test-only: clear module state between tests. */
export function __resetApiAuthForTest(): void {
  authToken = null
}