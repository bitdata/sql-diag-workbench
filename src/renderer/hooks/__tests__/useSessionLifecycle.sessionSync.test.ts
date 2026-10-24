import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useWorkspacePersistence, workspacePayload } from '../useWorkspacePersistence'
import type { Tab } from '../../types/tab'
import type { SessionWorkspaceState } from '../../types/session'
const ws = (id: string, sql = 'SELECT 1'): SessionWorkspaceState => ({ sessionId:id, tabName:id, sql,
  isDirty:true, lastSavedAt:null, connectionId:null, dialect:'postgresql', fragmentTreeJson:null,
  selectedNodeId:null, viewerMode:'input', workspaceMode:'data', hydration:'ready' })
const tab = (workspace: SessionWorkspaceState): Tab => ({ sessionId:workspace.sessionId, name:workspace.tabName,
  isDirty:workspace.isDirty, lastSavedAt:0, workspace })
describe('workspace save queue', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())
  it('serializes changes per session and does not lose a background tab write', async () => {
    let release!: (res: Response) => void
    const fetch = vi.spyOn(globalThis,'fetch').mockImplementationOnce(() => new Promise(r => { release = r }))
      .mockResolvedValue(new Response('{}'))
    const status = vi.fn()
    const a = ws('a')
    const { rerender } = renderHook(({ tabs }) => useWorkspacePersistence('http://localhost:8080',tabs,status),
      { initialProps:{ tabs:[tab(a)] } })
    await act(async () => { await vi.advanceTimersByTimeAsync(450) })
    rerender({ tabs:[tab({ ...a,sql:'SELECT 2' }),tab(ws('b',"SELECT 'F'"))] })
    await act(async () => { await vi.advanceTimersByTimeAsync(450) })
    // B writes while A remains in flight; another A cannot start yet.
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch.mock.calls[1][0]).toContain('/b/workspace')
    await act(async () => { release(new Response('{}')); await Promise.resolve() })
    expect(fetch).toHaveBeenCalledTimes(3)
    expect(JSON.parse(fetch.mock.calls[2][1]!.body as string).sqlText).toBe('SELECT 2')
  })
  it('blocks failed hydration and corrupt recovery, and never writes clean loaded state', async () => {
    const fetch = vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response('{}'))
    renderHook(() => useWorkspacePersistence('http://localhost:8080',
      [tab({ ...ws('a'), hydration:'failed' }),tab({ ...ws('b'),recoveryBlocked:true }),tab({ ...ws('c'),isDirty:false })],vi.fn()))
    await act(async () => { await vi.advanceTimersByTimeAsync(900) })
    expect(fetch).not.toHaveBeenCalled()
  })
  it('keeps failed data until manual retry and uses the latest snapshot', async () => {
    const fetch = vi.spyOn(globalThis,'fetch').mockResolvedValueOnce(new Response('',{status:503})).mockResolvedValue(new Response('{}'))
    const status = vi.fn(), workspace = ws('a')
    const { result } = renderHook(() => useWorkspacePersistence('http://localhost:8080',[tab(workspace)],status))
    await act(async () => { await vi.advanceTimersByTimeAsync(450) })
    expect(status).toHaveBeenLastCalledWith('a',workspacePayload(workspace),'failed','HTTP 503')
    await act(async () => { await result.current('a') })
    expect(status).toHaveBeenLastCalledWith('a',workspacePayload(workspace),'saved')
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('does not retry a deleted session after a late write response', async () => {
    let release!: (res: Response) => void
    const fetch = vi.spyOn(globalThis,'fetch').mockImplementation(() => new Promise(r => { release=r }))
    const status=vi.fn()
    const { rerender }=renderHook(({tabs})=>useWorkspacePersistence('http://localhost:8080',tabs,status),
      {initialProps:{tabs:[tab(ws('a'))]}})
    await act(async()=>{await vi.advanceTimersByTimeAsync(450)})
    rerender({tabs:[]})
    await act(async()=>{release(new Response('',{status:404}));await Promise.resolve()})
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(status).not.toHaveBeenCalledWith('a',expect.anything(),'failed',expect.anything())
  })
})
