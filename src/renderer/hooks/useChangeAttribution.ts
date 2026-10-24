import { apiFetch } from '../utils/api'
import { useEffect, useState, useRef } from 'react'
import type { SqlDialect } from '../types/session'
import type { DiffMethod } from '../utils/diff'
import { normalizeDiffMethod } from '../utils/diff'

export interface ChangeOp {
  kind: 'ADD' | 'DEL' | 'MOD'
  nodeType: string
  path: string
  oldText: string | null
  newText: string | null
}

/**
 * Re-export for backwards-compat with consumers that import `DiffMethod` from
 * this hook module. The canonical definition lives in
 * {@link ../utils/diff} (see `DiffMethod` + `normalizeDiffMethod` there).
 *
 * @deprecated Import from `../utils/diff` directly. This re-export will be
 * removed in a future iteration once all call sites migrate.
 */
export type { DiffMethod } from '../utils/diff'

interface State {
  ops: ChangeOp[]
  loading: boolean
  error: string | null
  /** True when the backend's DiffResponse.degraded flag is set (path-set fallback). */
  degraded: boolean
  /** Backend-reported diff method; useful for showing why precision dropped. */
  diffMethod: DiffMethod | ''
}

/**
 * Calls backend POST /api/diff to attribute changes between two SQL strings.
 * Cancellable via AbortController on input change (spec §3.5).
 */
export function useChangeAttribution(
  backendUrl: string | null,
  before: string,
  after: string,
  dialect: SqlDialect
): State {
  const [state, setState] = useState<State>({
    ops: [],
    loading: false,
    error: null,
    degraded: false,
    diffMethod: '',
  })
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    if (!backendUrl) {
      setState({
        ops: [],
        loading: false,
        error: 'Backend not connected',
        degraded: false,
        diffMethod: '',
      })
      return
    }
    if (!before || !after) {
      setState({
        ops: [],
        loading: false,
        error: null,
        degraded: false,
        diffMethod: '',
      })
      return
    }
    if (before === after) {
      setState({
        ops: [],
        loading: false,
        error: null,
        degraded: false,
        diffMethod: '',
      })
      return
    }

    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    setState((s) => ({ ...s, loading: true, error: null }))

    apiFetch(`${backendUrl}/api/diff`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ before, after, dialect }),
      signal: controller.signal,
    })
      .then((res) => {
        if (!res.ok) {
          return res.json().then((e) => {
            throw new Error(e?.message ?? `HTTP ${res.status}`)
          })
        }
        return res.json()
      })
      .then(
        (data: { ops: ChangeOp[]; degraded?: boolean; diffMethod?: string }) => {
          if (controller.signal.aborted) return
          setState({
            ops: data.ops ?? [],
            loading: false,
            error: null,
            degraded: data.degraded ?? false,
            diffMethod: normalizeDiffMethod(data.diffMethod),
          })
        }
      )
      .catch((e: unknown) => {
        if (e instanceof DOMException && e.name === 'AbortError') return
        setState({
          ops: [],
          loading: false,
          error: e instanceof Error ? e.message : 'Diff failed',
          degraded: false,
          diffMethod: '',
        })
      })

    return () => controller.abort()
  }, [backendUrl, before, after, dialect])

  return state
}
