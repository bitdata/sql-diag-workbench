import { renderHook, act } from '@testing-library/react'
import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { useWorkspacePersistence } from '../useWorkspacePersistence'
import type { Tab } from '../../types/tab'
const tab = (id: string, sql = 'SELECT 1'): Tab => ({ sessionId: id, name: id, isDirty: true, lastSavedAt: 0,
  workspace: { sessionId: id, tabName: id, sql, dialect: 'postgresql', connectionId: null,
    isDirty: true, lastSavedAt: null, fragmentTreeJson: null, selectedNodeId: null, viewerMode: 'input', workspaceMode: 'data', hydration: 'ready' } })
describe('exit drains', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })
  it('confirms an already-saved payload after transient navigation marks it dirty', async () => {
    const fetch=vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response('{}'))
    const clean=tab('a');clean.isDirty=false;clean.workspace.isDirty=false
    const status=vi.fn()
    const {rerender}=renderHook(({tabs})=>useWorkspacePersistence('http://test',tabs,status),{initialProps:{tabs:[clean]}})
    const navigated=tab('a');navigated.workspace.viewerMode='browse'
    rerender({tabs:[navigated]})
    await act(async()=>{await vi.advanceTimersByTimeAsync(500)})
    expect(status).toHaveBeenLastCalledWith('a',expect.any(String),'saved')
    expect(fetch).not.toHaveBeenCalled()
  })
  it('does not confirm a reverted payload while an older write can still commit', async () => {
    let release!:(r:Response)=>void
    const fetch=vi.spyOn(globalThis,'fetch').mockImplementationOnce(()=>new Promise(r=>{release=r})).mockResolvedValue(new Response('{}'))
    const clean=tab('a');clean.isDirty=false;clean.workspace.isDirty=false
    const status=vi.fn()
    const {rerender}=renderHook(({tabs})=>useWorkspacePersistence('http://test',tabs,status),{initialProps:{tabs:[clean]}})
    rerender({tabs:[tab('a','SELECT 2')]});await act(async()=>{await vi.advanceTimersByTimeAsync(400)})
    rerender({tabs:[tab('a')]});expect(status.mock.calls.some(c=>c[2]==='saved')).toBe(false)
    await act(async()=>{release(new Response('{}'));await vi.advanceTimersByTimeAsync(500)})
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(JSON.parse(fetch.mock.calls[1][1]!.body as string).sqlText).toBe('SELECT 1')
    expect(status).toHaveBeenLastCalledWith('a',expect.any(String),'saved')
  })
  it('retries maintenance with the latest payload instead of losing the edit', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'STORAGE_MAINTENANCE' }), { status: 503 }))
      .mockResolvedValue(new Response('{}'))
    const status = vi.fn()
    const { rerender } = renderHook(({ tabs }) => useWorkspacePersistence('http://test', tabs, status),
      { initialProps: { tabs: [tab('a')] } })
    await act(async () => { await vi.advanceTimersByTimeAsync(400) })
    expect(status).toHaveBeenLastCalledWith('a', expect.any(String), 'failed', expect.stringContaining('STORAGE_MAINTENANCE'))
    rerender({ tabs: [tab('a', 'SELECT 99')] })
    await act(async () => { await vi.advanceTimersByTimeAsync(1500) })
    expect(JSON.parse(fetch.mock.calls[1][1]!.body as string).sqlText).toBe('SELECT 99')
    expect(status).toHaveBeenLastCalledWith('a', expect.any(String), 'saved')
  })
  it('bypasses debounce and waits for the newer version behind a pending write', async () => {
    let release!: (r: Response) => void
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementationOnce(() => new Promise(r => { release = r }))
      .mockResolvedValue(new Response('{}'))
    const { result, rerender } = renderHook(({ tabs }) => useWorkspacePersistence('http://test', tabs, vi.fn()),
      { initialProps: { tabs: [tab('a')] } })
    await act(async () => { await vi.advanceTimersByTimeAsync(400) })
    rerender({ tabs: [tab('a', 'SELECT 2'), tab('b')] })
    let done = false
    let flush!: Promise<unknown>
    await act(async () => { flush = result.current.flushAll().then(r => { done = true; return r }); await Promise.resolve() })
    expect(done).toBe(false)
    expect(fetch).toHaveBeenCalledTimes(2)
    await act(async () => { release(new Response('{}')); await flush })
    expect(done).toBe(true)
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(JSON.parse(fetch.mock.calls[2][1]!.body as string).sqlText).toBe('SELECT 2')
  })
  it('partial failure preserves confirmed versions and only retries the failed tab', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async input => new Response('{}', { status: String(input).includes('/a/') ? 503 : 200 }))
    const { result } = renderHook(() => useWorkspacePersistence('http://test', [tab('a'), tab('b')], vi.fn()))
    let first!: Awaited<ReturnType<typeof result.current.flushAll>>
    await act(async () => { first = await result.current.flushAll() })
    expect(first.savedSessionIds).toEqual(['b'])
    expect(first.issues).toEqual([{ sessionId: 'a', tabName: 'a', code: 'SAVE_FAILED', message: 'HTTP 503' }])
    fetch.mockResolvedValue(new Response('{}'))
    await act(async () => { expect((await result.current.flushAll()).issues).toEqual([]) })
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it('a cancelled drain cannot confirm success or send a newer write; the next attempt joins the old write', async () => {
    let release!: (r: Response) => void
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementationOnce(() => new Promise(r => { release = r })).mockResolvedValue(new Response('{}'))
    const { result, rerender } = renderHook(({ tabs }) => useWorkspacePersistence('http://test', tabs, vi.fn()), { initialProps: { tabs: [tab('a')] } })
    const abort = new AbortController()
    let previous!: Promise<unknown>
    await act(async () => { previous = result.current.flushAll(abort.signal); await Promise.resolve() })
    abort.abort()
    rerender({ tabs: [tab('a', 'SELECT 9')] })
    let next!: Promise<unknown>
    await act(async () => { next = result.current.flushAll(); await Promise.resolve() })
    expect(fetch).toHaveBeenCalledTimes(1)
    await act(async () => { release(new Response('{}')); await previous; await next })
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('missing backend and damaged workspaces return issues without writing empty state', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}'))
    const a = tab('a'); a.workspace.recoveryBlocked = true
    const { result } = renderHook(() => useWorkspacePersistence(null, [a, tab('b')], vi.fn()))
    await act(async () => { expect((await result.current.flushAll()).issues.map(i => i.code)).toEqual(['WORKSPACE_UNAVAILABLE', 'BACKEND_UNAVAILABLE']) })
    expect(fetch).not.toHaveBeenCalled()
  })
})
