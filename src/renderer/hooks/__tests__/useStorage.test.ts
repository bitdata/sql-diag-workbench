import { renderHook, act } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useStorage } from '../useStorage'
import { confirmProtection, workspaceProfile } from '../../utils/workspaceProtection'

const status = { policy: { enabled: false, retentionDays: 30, maxStorageMiB: 500, revision: 1 },
  size: { totalBytes: 1, degraded: false }, protectionReady: true, job: null, degraded: false }
describe('storage policy and protection', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); vi.unstubAllGlobals() })
  it('keeps action failure visible across successful background status polling', async () => {
    vi.useFakeTimers()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_url, init) => new Response(JSON.stringify(
      init?.method === 'PUT' ? { message: 'Policy changed: reload' } : status), { status: init?.method === 'PUT' ? 409 : 200 }))
    const { result } = renderHook(() => useStorage('http://test'))
    await act(async () => { await Promise.resolve() })
    await act(async () => { expect(await result.current.apply(status.policy)).toBe(false) })
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(result.current.error).toBe('Policy changed: reload')
    await act(async () => { await result.current.refresh() })
    expect(result.current.error).toBeNull()
  })
  it('ignores status replies from a previous backend', async () => {
    let release!: (r: Response) => void
    vi.spyOn(globalThis, 'fetch').mockImplementationOnce(() => new Promise(r => { release = r }))
      .mockResolvedValue(new Response(JSON.stringify(status)))
    const { result, rerender } = renderHook(({ url }) => useStorage(url), { initialProps: { url: 'http://old' } })
    await act(async () => { rerender({ url: 'http://new' }) })
    await act(async () => { release(new Response(JSON.stringify({ ...status, protectionReady: false }))) })
    expect(result.current.status?.protectionReady).toBe(true)
  })
  it('rejects damaged raw inventory before any protection request', async () => {
    const values = new Map<string, string>([['tabs.v1', '{broken']])
    vi.stubGlobal('localStorage', { getItem: (k: string) => values.get(k) ?? null, setItem: (k: string, v: string) => values.set(k, v) })
    const fetch = vi.spyOn(globalThis, 'fetch')
    await expect(confirmProtection('http://test', [])).rejects.toThrow()
    expect(fetch).not.toHaveBeenCalled()
  })
  it('requires durable profile readback and includes all raw pending tabs', async () => {
    const values = new Map<string, string>([['tabs.v1', JSON.stringify({ tabSessionIds: ['failed', 'loading'] })]])
    vi.stubGlobal('localStorage', { getItem: (k: string) => values.get(k) ?? null, setItem: (k: string, v: string) => values.set(k, v) })
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{"confirmed":true}'))
    await confirmProtection('http://test', ['active'])
    expect(JSON.parse(fetch.mock.calls[0][1]!.body as string).sessionIds).toEqual(['active', 'failed', 'loading'])
    vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => undefined })
    expect(workspaceProfile().errorReason).toContain('persisted')
  })
})
