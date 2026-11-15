import { renderHook, act } from '@testing-library/react'
import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest'
import { useWorkspacePersistence } from '../useWorkspacePersistence'
import { graphRequest, IDENTITY_POLICY } from '../../utils/workspaceIdentity'
import type { Tab } from '../../types/tab'

function tab(sql = 'SELECT 1', revision = 0): Tab {
  return { sessionId: 'source', name: 'source', isDirty: true, lastSavedAt: 0,
    workspace: { sessionId: 'source', tabName: 'source', identityPolicy: IDENTITY_POLICY,
      workspaceRevision: revision, readOnly: false, sql, dialect: 'postgresql', connectionId: null,
      isDirty: true, lastSavedAt: null, fragmentTreeJson: null, selectedNodeId: null,
      viewerMode: 'input', workspaceMode: 'data', hydration: 'ready' } }
}
const ack = (revision: number) => new Response(JSON.stringify({ id: 'source', identityPolicy: IDENTITY_POLICY, workspaceRevision: revision }))

describe('workspace identity and conflict ownership', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })
  it('captures an explicit source context and refuses historical/missing contexts', () => {
    const source=tab().workspace
    const captured=graphRequest(source)
    source.workspaceRevision=7
    expect(captured.identityContext.workspaceRevision).toBe(0)
    expect(() => graphRequest({ ...source, readOnly: true })).toThrow('read-only')
    expect(() => graphRequest()).toThrow('read-only')
  })
  it('rebases a newer queued draft only after the older write is acknowledged', async () => {
    let release!: (response: Response) => void
    const fetch=vi.spyOn(globalThis,'fetch').mockImplementationOnce(() => new Promise(r => { release=r }))
      .mockResolvedValueOnce(ack(2))
    const status=vi.fn()
    const { result,rerender }=renderHook(({ tabs }) => useWorkspacePersistence('http://test',tabs,status), { initialProps: { tabs: [tab()] } })
    await act(async () => { await vi.advanceTimersByTimeAsync(400) })
    rerender({ tabs: [tab('SELECT 2')] })
    await act(async () => { release(ack(1)); await vi.advanceTimersByTimeAsync(500) })
    expect(fetch).toHaveBeenCalledTimes(2)
    const first=JSON.parse(fetch.mock.calls[0][1]!.body as string)
    const second=JSON.parse(fetch.mock.calls[1][1]!.body as string)
    expect(first.identityContext).toEqual({ sessionId:'source',identityPolicy:IDENTITY_POLICY,workspaceRevision:0 })
    expect(second.identityContext.workspaceRevision).toBe(1)
    expect(second.sqlText).toBe('SELECT 2')
    await act(async () => { expect((await result.current.flushAll()).workspaceRevisions?.source).toBe(2) })
  })
  it('preserves the latest local draft after conflict without automatic, explicit, or exit retry', async () => {
    const fetch=vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response(JSON.stringify({ code:'WORKSPACE_REVISION_CONFLICT',errorReason:'WORKSPACE_REVISION_CONFLICT' }), {status:409}))
    const { result,rerender }=renderHook(({ tabs }) => useWorkspacePersistence('http://test',tabs,vi.fn()), { initialProps: { tabs:[tab()] } })
    await act(async () => { await vi.advanceTimersByTimeAsync(400) })
    const draft=tab('SELECT 99'); rerender({ tabs:[draft] })
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); await result.current.retrySave('source') })
    await act(async () => { const report=await result.current.flushAll(); expect(report.issues[0].message).toContain('WORKSPACE_REVISION_CONFLICT') })
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(draft.workspace.sql).toBe('SELECT 99')
  })
  it('never autosaves historical bytes, and exit treats them as preserved', async () => {
    const fetch=vi.spyOn(globalThis,'fetch')
    const old=tab(); old.workspace.readOnly=true; old.workspace.identityPolicy='unversioned-legacy'; old.workspace.workspaceSnapshotJson='original bytes'
    const {result}=renderHook(() => useWorkspacePersistence('http://test',[old],vi.fn()))
    await act(async () => { await vi.advanceTimersByTimeAsync(500); expect((await result.current.flushAll()).savedSessionIds).toEqual(['source']) })
    expect(fetch).not.toHaveBeenCalled()
    expect(old.workspace.workspaceSnapshotJson).toBe('original bytes')
  })
})
