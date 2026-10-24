import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { ExitCoordinator, validExitResult } from './workspace-exit'
describe('desktop quit coordinator', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())
  function setup() {
    const actions = { request: vi.fn(), cancel: vi.fn(), unavailable: vi.fn(), quit: vi.fn() }
    return { exit: new ExitCoordinator(actions, 35000), actions }
  }
  it('merges close and quit, ignores stale success after cancel, and stops once', () => {
    const { exit, actions } = setup()
    exit.request(); exit.request()
    expect(actions.request).toHaveBeenCalledTimes(1)
    const id = actions.request.mock.calls[0][0]
    exit.decision(id, 'cancel'); exit.request()
    const next = actions.request.mock.calls[1][0]
    exit.result({ attemptId: id, status: 'saved', savedSessionIds: [], issues: [] })
    expect(actions.quit).not.toHaveBeenCalled()
    exit.result({ attemptId: next, status: 'saved', savedSessionIds: ['a'], issues: [] })
    exit.request()
    expect(actions.quit).toHaveBeenCalledTimes(1)
    expect(exit.committed).toBe(true)
  })
  it('timeout yields one native choice; late saved reply cannot override the choice', () => {
    const { exit, actions } = setup()
    exit.request()
    const id = actions.request.mock.calls[0][0]
    vi.advanceTimersByTime(35000)
    exit.unavailable()
    expect(actions.unavailable).toHaveBeenCalledTimes(1)
    exit.result({ attemptId: id, status: 'saved', savedSessionIds: [], issues: [] })
    expect(actions.quit).not.toHaveBeenCalled()
    exit.decision(id, 'retry')
    expect(actions.request).toHaveBeenCalledTimes(2)
    exit.decision(actions.request.mock.calls[1][0], 'discard')
    expect(actions.quit).toHaveBeenCalledTimes(1)
  })
  it('failure waits for a decision and rejects invalid or oversized replies', () => {
    const { exit, actions } = setup()
    exit.request()
    const id = actions.request.mock.calls[0][0]
    const result = { attemptId: id, status: 'failed', savedSessionIds: ['a'], issues: [{ code: 'SAVE_FAILED', message: '503' }] }
    exit.result(result)
    vi.advanceTimersByTime(40000)
    expect(actions.unavailable).not.toHaveBeenCalled()
    expect(actions.quit).not.toHaveBeenCalled()
    expect(validExitResult({ ...result, status: 'saved' })).toBe(false)
    expect(validExitResult({ ...result, issues: [{ code: 'ERROR', message: 'x'.repeat(2001) }] })).toBe(false)
    expect(validExitResult(null)).toBe(false)
  })
})
