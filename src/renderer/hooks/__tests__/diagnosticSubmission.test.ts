import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useRowTrace } from '../useRowTrace'
const api = vi.hoisted(() => vi.fn())
vi.mock('../../utils/api', () => ({ apiFetch: api }))
const context = { sessionId: 's', identityPolicy: 'structural-p0p1-v2', workspaceRevision: 5, workspaceInstanceId: 'a'.repeat(32) }
const keys = [{ column: 'id', type: 'DECIMAL' as const, value: '9007199254740993' }]
afterEach(() => { vi.clearAllMocks(); vi.useRealTimers() })
beforeEach(() => { vi.clearAllMocks() })
it('waits for saving and submits the confirmed context, retaining decimal strings', async () => {
  let confirm!: (value: typeof context) => void
  const prepare = vi.fn(() => new Promise<typeof context>(r => { confirm = r }))
  api.mockResolvedValue({ ok: true, json: async () => ({ traceId: 't' }) })
  const hook = renderHook(() => useRowTrace('http://backend', 'scope', prepare))
  let work!: Promise<void>
  act(() => { work = hook.result.current.trace('c', '{}', keys) })
  expect(api).not.toHaveBeenCalled(); expect(hook.result.current.blocked).toBe(true)
  await act(async () => { confirm(context); await work })
  const body = JSON.parse(api.mock.calls[0][1].body)
  expect(body.diagnosticContext).toEqual(context); expect(body.keys).toEqual(keys)
})
it('never sends diagnostics after save failure or a scope change while saving', async () => {
  const failed = renderHook(() => useRowTrace('http://backend', 'a', async () => { throw new Error('WORKSPACE_SAVE_UNCONFIRMED') }))
  await act(() => failed.result.current.trace('c', '{}', keys))
  expect(failed.result.current.errorReason).toBe('WORKSPACE_SAVE_UNCONFIRMED'); expect(api).not.toHaveBeenCalled()
  failed.unmount()
  let confirm!: (value: typeof context) => void
  const prepare = () => new Promise<typeof context>(r => { confirm = r })
  const hook = renderHook(({ scope }) => useRowTrace('http://backend', scope, prepare), { initialProps: { scope: 'a' } })
  let work!: Promise<void>; act(() => { work = hook.result.current.trace('c', '{}', keys) })
  hook.rerender({ scope: 'new-instance' })
  await act(async () => { confirm(context); await work }); expect(api).not.toHaveBeenCalled()
})
it('source wire request contains only the certified receipt and context', async () => {
  api.mockResolvedValue({ ok: true, json: async () => ({ traceId: 'source' }) })
  const hook = renderHook(() => useRowTrace('http://backend', 'scope'))
  await act(() => hook.result.current.trace('c', '', keys, { diagnosticContext: context, sourceReceiptId: 'receipt', mode: 'CARDINALITY', queryTimeoutSeconds: 30 }))
  expect(JSON.parse(api.mock.calls[0][1].body)).toEqual({ connectionId: 'c', diagnosticContext: context, sourceReceiptId: 'receipt', mode: 'CARDINALITY', queryTimeoutSeconds: 30 })
  expect(hook.result.current.snapshot?.sqlById).toEqual({})
})
