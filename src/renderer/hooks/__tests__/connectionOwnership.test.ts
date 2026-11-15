import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useConnections } from '../useConnections'
import { apiFetch } from '../../utils/api'
vi.mock('../../utils/api', () => ({ apiFetch: vi.fn() }))
const item = { id: 'a', name: 'old' } as any
const response = (value: unknown, ok = true) => ({ ok, json: async () => value }) as Response
function deferred() { let resolve!: (v: Response) => void; const promise = new Promise<Response>(r => { resolve = r }); return { promise, resolve } }
async function initial() {
  vi.mocked(apiFetch).mockResolvedValueOnce(response([item]))
  const hook = renderHook(({ url }) => useConnections(url), { initialProps: { url: 'http://old' as string | null } })
  await waitFor(() => expect(hook.result.current.connections).toEqual([item]))
  return hook
}
describe('Connection response ownership', () => {
  beforeEach(() => vi.mocked(apiFetch).mockReset())
  for (const method of ['delete', 'create', 'update']) it(`late GET cannot overwrite ${method}`, async () => {
    const { result } = await initial(), old = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(old.promise)
    act(() => { void result.current.fetchConnections() })
    const changed = { ...item, id: method === 'create' ? 'b' : 'a', name: 'new' }
    const expected = method === 'delete' ? [] : method === 'create' ? [item, changed] : [changed]
    vi.mocked(apiFetch).mockResolvedValueOnce(response(method === 'delete' ? null : changed)).mockResolvedValueOnce(response(expected))
    await act(async () => {
      if (method === 'delete') expect(await result.current.deleteConnection('a')).toBe(true)
      else if (method === 'create') await result.current.createConnection(changed)
      else await result.current.updateConnection('a', changed)
    })
    await act(async () => { old.resolve(response([item])) })
    expect(result.current.connections).toEqual(expected)
  })
  it('coalesces refresh while writes pending and reads after settlement', async () => {
    const { result } = await initial(), write = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(write.promise).mockResolvedValueOnce(response([]))
    let promise!: Promise<boolean>
    act(() => { promise = result.current.deleteConnection('a'); void result.current.fetchConnections(); void result.current.fetchConnections() })
    expect(apiFetch).toHaveBeenCalledTimes(2)
    await act(async () => { write.resolve(response(null)); await promise })
    expect(apiFetch).toHaveBeenCalledTimes(3); expect(result.current.connections).toEqual([])
  })
  it('preserves failure message through successful reconciliation', async () => {
    const { result } = await initial(), write = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(write.promise).mockResolvedValueOnce(response([item]))
    let promise!: Promise<boolean>
    act(() => { promise = result.current.deleteConnection('a'); void result.current.fetchConnections() })
    await act(async () => { write.resolve(response({ message: 'STORAGE_ERROR' }, false)); expect(await promise).toBe(false) })
    expect(result.current.connections).toEqual([item]); expect(result.current.error).toBe('STORAGE_ERROR')
  })
  it('latest refresh owns list, loading and error', async () => {
    const { result } = await initial(), first = deferred(), second = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    act(() => { void result.current.fetchConnections(); void result.current.fetchConnections() })
    await act(async () => { first.resolve(response([], false)) })
    expect(result.current.loading).toBe(true); expect(result.current.error).toBe(null)
    await act(async () => { second.resolve(response([{ ...item, name: 'latest' }])) })
    expect(result.current.loading).toBe(false); expect(result.current.connections[0].name).toBe('latest')
  })
  for (const ok of [true, false]) it(`backend switch discards old mutation ${ok ? 'success' : 'failure'} and return`, async () => {
    const { result, rerender } = await initial(), pending = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(pending.promise)
    let promise!: Promise<boolean>
    act(() => { promise = result.current.deleteConnection('a') })
    vi.mocked(apiFetch).mockResolvedValueOnce(response([{ ...item, id: 'new-backend' }]))
    rerender({ url: 'http://new' })
    await waitFor(() => expect(result.current.connections[0]?.id).toBe('new-backend'))
    await act(async () => { pending.resolve(response({ message: 'old failure' }, ok)); expect(await promise).toBe(false) })
    expect(result.current.connections[0].id).toBe('new-backend'); expect(result.current.error).toBe(null)
  })
  it('old backend GET cannot overwrite new list', async () => {
    const { result, rerender } = await initial(), old = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(old.promise)
    act(() => { void result.current.fetchConnections() })
    vi.mocked(apiFetch).mockResolvedValueOnce(response([])); rerender({ url: 'http://new' })
    await waitFor(() => expect(result.current.connections).toEqual([]))
    await act(async () => { old.resolve(response([item])) }); expect(result.current.connections).toEqual([])
  })
  it('unmount invalidates returned success', async () => {
    const { result, unmount } = await initial(), pending = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(pending.promise)
    const promise = result.current.deleteConnection('a'); unmount()
    pending.resolve(response(null)); expect(await promise).toBe(false)
  })
  it('serializes same-connection writes before sending requests', async () => {
    const { result } = await initial(), pending = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(pending.promise).mockResolvedValueOnce(response(null))
    let update!: Promise<unknown>, remove!: Promise<boolean>
    act(() => { update = result.current.updateConnection('a', { ...item, name: 'new' }); remove = result.current.deleteConnection('a') })
    expect(apiFetch).toHaveBeenCalledTimes(2)
    await act(async () => { pending.resolve(response({ ...item, name: 'new' })); await update; expect(await remove).toBe(true) })
    expect(apiFetch).toHaveBeenCalledTimes(3); expect(result.current.connections).toEqual([])
  })
  it('independent connections settle without replacing each other', async () => {
    const { result } = await initial(), pending = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(pending.promise).mockResolvedValueOnce(response({ ...item, id: 'b' }))
    let remove!: Promise<boolean>
    act(() => { remove = result.current.deleteConnection('a') })
    await act(async () => { await result.current.createConnection({ ...item, id: 'b' }) })
    await act(async () => { pending.resolve(response(null)); await remove })
    expect(result.current.connections.map(c => c.id)).toEqual(['b'])
  })
  it('invalidated initial GET reconciles full list after write', async () => {
    const pending = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(pending.promise)
    const { result } = renderHook(() => useConnections('http://old'))
    vi.mocked(apiFetch).mockResolvedValueOnce(response({ ...item, id: 'b' })).mockResolvedValueOnce(response([item, { ...item, id: 'b' }]))
    await act(async () => { await result.current.createConnection({ ...item, id: 'b' }) })
    await act(async () => { pending.resolve(response([])) })
    expect(result.current.connections.map(c => c.id)).toEqual(['a', 'b'])
  })
  it('stale GET error and finally cannot touch active reconciliation', async () => {
    const { result } = await initial(), old = deferred(), fresh = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(old.promise)
    act(() => { void result.current.fetchConnections() })
    vi.mocked(apiFetch).mockResolvedValueOnce(response(null)).mockReturnValueOnce(fresh.promise)
    await act(async () => { await result.current.deleteConnection('a') })
    await act(async () => { old.resolve(response(null, false)) })
    expect(result.current.loading).toBe(true); expect(result.current.error).toBe(null)
    await act(async () => { fresh.resolve(response([])) }); expect(result.current.loading).toBe(false)
  })
  it('failed queued update still allows following delete', async () => {
    const { result } = await initial(), pending = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(pending.promise).mockResolvedValueOnce(response(null))
    let update!: Promise<unknown>, remove!: Promise<boolean>
    act(() => { update = result.current.updateConnection('a', item); remove = result.current.deleteConnection('a') })
    await act(async () => { pending.resolve(response({ message: 'first failed' }, false)); expect(await update).toBe(null); expect(await remove).toBe(true) })
    expect(result.current.connections).toEqual([]); expect(result.current.error).toBe(null)
  })
  it('backend switch prevents queued old-backend request from being sent', async () => {
    const { result, rerender } = await initial(), pending = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(pending.promise)
    let first!: Promise<unknown>, second!: Promise<boolean>
    act(() => { first = result.current.updateConnection('a', item); second = result.current.deleteConnection('a') })
    vi.mocked(apiFetch).mockResolvedValueOnce(response([])); rerender({ url: null })
    await act(async () => { pending.resolve(response(item)); expect(await first).toBe(null); expect(await second).toBe(false) })
    expect(apiFetch).toHaveBeenCalledTimes(2); expect(result.current.connections).toEqual([])
  })
  it('switching back to same URL still invalidates old epoch', async () => {
    const { result, rerender } = await initial(), old = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(old.promise)
    act(() => { void result.current.fetchConnections() })
    rerender({ url: null })
    vi.mocked(apiFetch).mockResolvedValueOnce(response([])); rerender({ url: 'http://old' })
    await waitFor(() => expect(result.current.connections).toEqual([]))
    await act(async () => { old.resolve(response([item])) }); expect(result.current.connections).toEqual([])
  })
  for (const ok of [true, false]) it(`testConnection rejects old backend ${ok ? 'success' : 'failure'}`, async () => {
    const { result, rerender } = await initial(), pending = deferred()
    vi.mocked(apiFetch).mockReturnValueOnce(pending.promise)
    const testing = result.current.testConnection(item)
    rerender({ url: null })
    pending.resolve(response({ success: true, serverVersion: 'old' }, ok))
    expect(await testing).toMatchObject({ success: false, errorReason: 'CONNECTION_CONTEXT_CHANGED', degraded: true })
  })
  it('empty 404 error body preserves HTTP classification and configuration', async () => {
    const { result } = await initial()
    vi.mocked(apiFetch).mockResolvedValueOnce({ ok: false, status: 404, json: async () => { throw new SyntaxError('No JSON') } } as unknown as Response)
    await act(async () => { expect(await result.current.deleteConnection('a')).toBe(false) })
    expect(result.current.error).toContain('HTTP 404'); expect(result.current.connections).toEqual([item])
  })
})
