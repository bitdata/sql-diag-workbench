import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useWorkspaceExit } from '../useWorkspaceExit'
import type { UseTabsApi } from '../useTabs'
import type { ExitResult } from '../../types/workspaceExit'
import { isWorkspaceExitLocked, setWorkspaceExitLocked } from '../../utils/workspaceExitGate'
describe('renderer exit attempts', () => {
  let request: (value: { attemptId: string; cancelled?: boolean }) => void
  const report = vi.fn(), decide = vi.fn()
  beforeEach(() => {
    vi.useFakeTimers(); report.mockReset(); decide.mockReset()
    window.electronAPI = { onWorkspaceExit: (callback: typeof request) => { request = callback; return () => {} },
      reportWorkspaceExit: report, decideWorkspaceExit: decide } as unknown as typeof window.electronAPI
  })
  afterEach(() => { vi.useRealTimers(); setWorkspaceExitLocked(false) })
  function setup(flush = vi.fn().mockResolvedValue({ savedSessionIds: ['a'], issues: [] })) {
    const checkpoint = vi.fn()
    const tabs = { getExitState: () => ({ pending: false, error: null }), flushWorkspaces: flush } as unknown as UseTabsApi
    const hook = renderHook(({ pending, blockers }) => useWorkspaceExit({ tabs, checkpoint, pending, blockers, timeoutMs: 100 }),
      { initialProps: { pending: false, blockers: () => [] as string[] } })
    return { ...hook, flush, checkpoint }
  }
  it('waits for operations, checkpoints before flushing, and freezes synchronously', async () => {
    const hook = setup()
    hook.rerender({ pending: true, blockers: () => [] })
    act(() => request({ attemptId: 'one' }))
    expect(isWorkspaceExitLocked()).toBe(true)
    await act(async () => { await vi.advanceTimersByTimeAsync(40) })
    expect(hook.flush).not.toHaveBeenCalled()
    hook.rerender({ pending: false, blockers: () => [] })
    await act(async () => { await vi.advanceTimersByTimeAsync(30) })
    expect(hook.checkpoint.mock.invocationCallOrder[0]).toBeLessThan(hook.flush.mock.invocationCallOrder[0])
    expect(report).toHaveBeenCalledWith(expect.objectContaining({ attemptId: 'one', status: 'saved', savedSessionIds: ['a'] }))
  })
  it('cancel ignores a late successful drain and unlocks the original editor', async () => {
    let finish!: (r: { savedSessionIds: string[]; issues: [] }) => void
    const hook = setup(vi.fn(() => new Promise(r => { finish = r })))
    act(() => request({ attemptId: 'one' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(30) })
    act(() => hook.result.current.cancel())
    expect(decide).toHaveBeenCalledWith('one', 'cancel')
    expect(isWorkspaceExitLocked()).toBe(false)
    await act(async () => { finish({ savedSessionIds: ['a'], issues: [] }); await Promise.resolve() })
    expect(report).not.toHaveBeenCalled()
  })
  it('timeout reports once and a late success cannot claim all changes saved', async () => {
    let finish!: (r: { savedSessionIds: string[]; issues: [] }) => void
    setup(vi.fn(() => new Promise(r => { finish = r })))
    act(() => request({ attemptId: 'one' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(101) })
    expect((report.mock.calls[0][0] as ExitResult).status).toBe('timed-out')
    await act(async () => { finish({ savedSessionIds: ['a'], issues: [] }); await Promise.resolve() })
    expect(report).toHaveBeenCalledTimes(1)
  })
  it('unsubmitted drafts block exit without executing a recovery or overwriting a workspace', async () => {
    const hook = setup()
    hook.rerender({ pending: false, blockers: () => ['unsubmitted fragment'] })
    act(() => request({ attemptId: 'one' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(30) })
    expect(report).toHaveBeenCalledWith(expect.objectContaining({ status: 'blocked', issues: [{ code: 'UNFINISHED_OPERATION', message: 'unsubmitted fragment' }] }))
    expect(hook.flush).not.toHaveBeenCalled()
  })
})
