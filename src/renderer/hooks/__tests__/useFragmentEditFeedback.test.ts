import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useFragmentEditFeedback } from '../useFragmentEditFeedback'
import { buildMockTree } from '../../test/fixtures'
import type { UseTabsApi } from '../useTabs'
import type { UserEditOutcome } from '../useFragmentTree'

type Options = Parameters<typeof useFragmentEditFeedback>[0]
const completed = { status: 'EDIT', nodeId: 'edited', sql: 'SELECT 2' } as const
function setupOptions(): Options {
  const tree = buildMockTree()
  const source = { sessionId: 'source', name: 'Original' }
  const tabs = {
    tabs: [source, { sessionId: 'other', name: 'Other' }], activeSessionId: 'source',
    openInNewTab: vi.fn().mockResolvedValue('target'),
  } as unknown as UseTabsApi
  return { tabs, tree, nodeMap: new Map([[tree.id, tree]]), dialect: 'postgresql',
    submit: vi.fn().mockResolvedValue(completed), record: vi.fn() }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
}
const save = (hook: { save: ReturnType<typeof useFragmentEditFeedback>['save'] }) =>
  hook.save('node-root', 'SELECT 1', 'SELECT 2', 'Edited')

describe('edit feedback stage ownership', () => {
  it('exposes a dismissible invalid-source error without submitting and closes the scrim', async () => {
    const options = setupOptions()
    const { result } = renderHook(() => useFragmentEditFeedback({ ...options, nodeMap: new Map() }))
    await act(async () => { expect(await save(result.current)).toBe(true) })
    expect(options.submit).not.toHaveBeenCalled()
    expect(result.current.feedback?.message).toMatch(/No valid source/)
    expect(result.current.feedback?.recovery).toBeUndefined()
  })
  it('retains completed SQL, dialect and source through repeated open failures and tab switches', async () => {
    const options = setupOptions()
    vi.mocked(options.tabs.openInNewTab).mockResolvedValueOnce(null).mockResolvedValueOnce(null).mockResolvedValueOnce('target')
    const { result, rerender } = renderHook(p => useFragmentEditFeedback(p), { initialProps: options })
    await act(async () => { await save(result.current) })
    expect(result.current.feedback?.recovery?.stage).toBe('open')
    rerender({ ...options, dialect: 'mysql', tabs: { ...options.tabs, activeSessionId: 'other' } })
    await act(async () => { await result.current.retry() })
    expect(result.current.feedback?.recovery?.stage).toBe('open')
    await act(async () => { await result.current.retry() })
    expect(options.submit).toHaveBeenCalledTimes(1)
    expect(options.tabs.openInNewTab).toHaveBeenCalledTimes(3)
    const calls = vi.mocked(options.tabs.openInNewTab).mock.calls
    expect(calls[1][1]?.intent).toBe(calls[0][1]?.intent)
    expect(calls[2][1]?.intent).toBe(calls[0][1]?.intent)
    expect(options.tabs.openInNewTab).toHaveBeenLastCalledWith('SELECT 2', expect.objectContaining({ dialect: 'postgresql', name: 'Edited' }))
    expect(options.record).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ sourceSessionId: 'source', sourceNodeId: 'node-root', targetSessionId: 'target' }))
    expect(result.current.feedback).toBeNull()
  })

  it.each(['CREATION_REQUEST_CONFLICT', 'CREATION_TARGET_GONE', 'CREATION_VALIDATION_ERROR'])('a terminal open failure %s is dismissible without retrying', async code => {
    const options = setupOptions()
    vi.mocked(options.tabs.openInNewTab).mockImplementation(async (_sql, opts) => { opts?.onError?.('Terminal creation failure', code); return null })
    const { result } = renderHook(() => useFragmentEditFeedback(options))
    await act(async () => { await save(result.current) })
    expect(result.current.feedback?.message).toBe('Terminal creation failure')
    expect(result.current.feedback?.recovery).toBeUndefined()
    await act(async () => { await result.current.retry() })
    expect(options.tabs.openInNewTab).toHaveBeenCalledTimes(1)
    act(() => result.current.dismiss())
    expect(result.current.feedback).toBeNull()
  })

  it('never retries submission against another tab, but can return to the unchanged source', async () => {
    const options = setupOptions()
    vi.mocked(options.submit).mockResolvedValueOnce({ status: 'FAILED', reason: 'network result unknown' }).mockResolvedValueOnce(completed)
    const { result, rerender } = renderHook(p => useFragmentEditFeedback(p), { initialProps: options })
    await act(async () => { await save(result.current) })
    expect(result.current.feedback?.label).toBe('Could not confirm the edit result')
    rerender({ ...options, tabs: { ...options.tabs, activeSessionId: 'other' } })
    expect(result.current.blocked).toMatch(/Return to the source tab/)
    await act(async () => { await result.current.retry() })
    expect(options.submit).toHaveBeenCalledTimes(1)
    rerender(options)
    await act(async () => { await result.current.retry() })
    expect(options.submit).toHaveBeenLastCalledWith('node-root', 'SELECT 2', 'Edited', 'postgresql')
    expect(options.submit).toHaveBeenCalledTimes(2)
  })

  it.each(['tree', 'node', 'dialect', 'closed'] as const)('blocks a submit retry after source %s invalidation', async invalidation => {
    const options = setupOptions()
    vi.mocked(options.submit).mockResolvedValue({ status: 'FAILED', reason: 'HTTP 503' })
    const { result, rerender } = renderHook(p => useFragmentEditFeedback(p), { initialProps: options })
    await act(async () => { await save(result.current) })
    rerender({ ...options,
      tree: invalidation === 'tree' ? buildMockTree() : options.tree,
      nodeMap: invalidation === 'node' ? new Map() : options.nodeMap,
      dialect: invalidation === 'dialect' ? 'mysql' : options.dialect,
      tabs: invalidation === 'closed' ? { ...options.tabs, tabs: [] } : options.tabs,
    })
    expect(result.current.blocked).not.toBeNull()
    await act(async () => { await result.current.retry() })
    expect(options.submit).toHaveBeenCalledTimes(1)
    act(() => { result.current.dismiss() })
    expect(result.current.feedback).toBeNull()
  })

  it('blocks opening a saved result when its source was closed', async () => {
    const options = setupOptions()
    vi.mocked(options.tabs.openInNewTab).mockResolvedValue(null)
    const { result, rerender } = renderHook(p => useFragmentEditFeedback(p), { initialProps: options })
    await act(async () => { await save(result.current) })
    rerender({ ...options, tabs: { ...options.tabs, tabs: [] } })
    await act(async () => { await result.current.retry() })
    expect(result.current.blocked).toMatch(/closed/)
    expect(options.tabs.openInNewTab).toHaveBeenCalledTimes(1)
  })

  it('prevents duplicate saves and retry clicks synchronously, keeps pending feedback visible', async () => {
    const options = setupOptions()
    const request = deferred<UserEditOutcome>()
    vi.mocked(options.submit).mockReturnValue(request.promise)
    const { result } = renderHook(() => useFragmentEditFeedback(options))
    let first!: Promise<boolean>
    act(() => { first = save(result.current); void save(result.current) })
    expect(result.current.pending).toBe(true)
    expect(options.submit).toHaveBeenCalledTimes(1)
    vi.mocked(options.tabs.openInNewTab).mockResolvedValueOnce(null)
    await act(async () => { request.resolve(completed); await first })
    const opened = deferred<string | null>()
    vi.mocked(options.tabs.openInNewTab).mockReturnValue(opened.promise)
    let retry!: Promise<void>
    act(() => { retry = result.current.retry(); void result.current.retry(); void save(result.current); result.current.dismiss() })
    expect(result.current.pending).toBe(true)
    expect(result.current.feedback?.recovery?.stage).toBe('open')
    expect(options.tabs.openInNewTab).toHaveBeenCalledTimes(2)
    await act(async () => { opened.resolve('target'); await retry })
    expect(options.record).toHaveBeenCalledTimes(1)
    expect(result.current.pending).toBe(false)
  })

  it('retains a late completed result with its captured source instead of opening against a new context', async () => {
    const options = setupOptions()
    const request = deferred<UserEditOutcome>()
    vi.mocked(options.submit).mockReturnValue(request.promise)
    const { result, rerender } = renderHook(p => useFragmentEditFeedback(p), { initialProps: options })
    let first!: Promise<boolean>
    act(() => { first = save(result.current) })
    rerender({ ...options, tabs: { ...options.tabs, activeSessionId: 'other' } })
    await act(async () => { request.resolve(completed); await first })
    expect(options.tabs.openInNewTab).not.toHaveBeenCalled()
    expect(result.current.feedback?.recovery?.stage).toBe('open')
    await act(async () => { await result.current.retry() })
    expect(options.submit).toHaveBeenCalledTimes(1)
    expect(options.record).toHaveBeenCalledWith(expect.objectContaining({ sourceSessionId: 'source' }))
  })

  it('ignores completion after unmount', async () => {
    const options = setupOptions()
    const request = deferred<UserEditOutcome>()
    vi.mocked(options.submit).mockReturnValue(request.promise)
    const { result, unmount } = renderHook(() => useFragmentEditFeedback(options))
    let first!: Promise<boolean>
    act(() => { first = save(result.current) })
    unmount()
    await act(async () => { request.resolve(completed); await first })
    expect(options.tabs.openInNewTab).not.toHaveBeenCalled()
    expect(options.record).not.toHaveBeenCalled()
  })

  it('states that the source link is unavailable if its tab closed while opening the result', async () => {
    const options = setupOptions()
    const opened = deferred<string | null>()
    vi.mocked(options.tabs.openInNewTab).mockReturnValue(opened.promise)
    const { result, rerender } = renderHook(p => useFragmentEditFeedback(p), { initialProps: options })
    let first!: Promise<boolean>
    await act(async () => { first = save(result.current) })
    rerender({ ...options, tabs: { ...options.tabs, tabs: [] } })
    await act(async () => { opened.resolve('target'); await first })
    expect(options.record).not.toHaveBeenCalled()
    expect(result.current.feedback?.label).toBe('Edited SQL opened in a new tab')
    expect(result.current.feedback?.message).toMatch(/saved edit origin is no longer available for navigation/)
  })

  it.each([null, '', 'unsupported source'])('states degraded success for reason %s', async reason => {
    const options = setupOptions()
    vi.mocked(options.submit).mockResolvedValue({ ...completed, sourceDegradedReason: reason })
    const { result } = renderHook(() => useFragmentEditFeedback(options))
    await act(async () => { await save(result.current) })
    expect(result.current.feedback?.label).toBe('Edited SQL opened in a new tab')
    expect(result.current.feedback?.recovery).toBeUndefined()
    expect(result.current.feedback?.message.includes('Reason:')).toBe(!!reason)
  })
})
