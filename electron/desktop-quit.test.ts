import { EventEmitter } from 'node:events'
import { afterEach, expect, it, vi } from 'vitest'
import { ExitCoordinator } from './workspace-exit'
import { DesktopQuitGate } from './DesktopQuitGate'
import { BackendStopState } from './BackendStopState'
const clean = { stopping: true, drained: true, degraded: false, errorReason: 'NONE', tasks: [{ status: 'CANCELLED', cleanupConfirmed: true }] }
function fixture(options: { hungBegin?: boolean; hungRead?: boolean; readFails?: boolean; snapshot?: unknown; failedKill?: boolean; thrownKill?: boolean; noChild?: boolean } = {}) {
  vi.useFakeTimers()
  const owner = new BackendStopState()
  const child = Object.assign(new EventEmitter(), { kill: vi.fn(() => { if (options.thrownKill) throw new Error('synthetic kill failure'); return !options.failedKill }) })
  if (!options.noChild) owner.attach(child, owner.beginStart())
  const prepareStop = vi.fn(() => owner.prepareStop())
  const begin = vi.fn(async () => { if (options.hungBegin) return new Promise<void>(() => {}) })
  const read = vi.fn(async () => { if (options.hungRead) return new Promise(() => {}); if (options.readFails) throw new Error('synthetic transport failure'); return options.snapshot ?? clean })
  const releaseQuit = vi.fn()
  const gate = new DesktopQuitGate({ prepareStop, begin, read, requestStop: () => owner.requestStop(), processExitConfirmed: () => owner.processExitConfirmed(), releaseQuit })
  return { owner, child, gate, prepareStop, begin, read, releaseQuit }
}
afterEach(() => { vi.useRealTimers() })
it('retains child ownership until exit, once-only kill and no false stopped', () => {
  const f = fixture(); f.owner.prepareStop(); expect(f.owner.requestStop()).toBe(true); expect(f.owner.requestStop()).toBe(true)
  expect(f.owner.snapshot()).toMatchObject({ status: 'stopping', processExitConfirmed: false }); expect(f.child.kill).toHaveBeenCalledTimes(1)
  f.child.emit('exit'); expect(f.owner.snapshot()).toMatchObject({ status: 'stopped', processExitConfirmed: true })
})
it('invalidates an asynchronous pre-spawn generation when exit is requested', () => {
  const owner = new BackendStopState(); const generation = owner.beginStart(); owner.prepareStop(); expect(owner.canSpawn(generation)).toBe(false); expect(owner.processExitConfirmed()).toBe(true)
})
it('cannot start a replacement while an unconfirmed old child is owned', () => {
  const f = fixture(); f.owner.prepareStop(); f.owner.requestStop(); expect(() => f.owner.beginStart()).toThrow('BACKEND_PROCESS_STILL_OWNED')
})
it('waits for saved/discard commit and never drains on cancel', async () => {
  const f = fixture(); const request = vi.fn(); const exit = new ExitCoordinator({ request, cancel: vi.fn(), unavailable: vi.fn(), quit: () => { void f.gate.commit() } })
  exit.request(); const id = request.mock.calls[0][0]; exit.decision(id, 'cancel'); expect(f.prepareStop).not.toHaveBeenCalled()
  exit.request(); exit.result({ attemptId: request.mock.calls[1][0], status: 'saved', savedSessionIds: [], issues: [] }); await vi.advanceTimersByTimeAsync(0)
  expect(exit.committed).toBe(true); expect(f.prepareStop).toHaveBeenCalledTimes(1); expect(f.gate.allowNativeQuit).toBe(false)
  f.child.emit('exit'); await vi.advanceTimersByTimeAsync(25); expect(f.gate.allowNativeQuit).toBe(true); expect(f.releaseQuit).toHaveBeenCalledTimes(1)
})
it('coalesces repeated quit requests and bounds hung begin to five seconds', async () => {
  const f = fixture({ hungBegin: true }); const first = f.gate.commit(); expect(f.gate.commit()).toBe(first)
  await vi.advanceTimersByTimeAsync(4999); expect(f.gate.allowNativeQuit).toBe(false); await vi.advanceTimersByTimeAsync(1)
  expect(await first).toMatchObject({ engineCleanup: 'unconfirmed', processExitConfirmed: false, errorReason: 'BACKEND_PROCESS_EXIT_UNCONFIRMED' }); expect(f.child.kill).toHaveBeenCalledTimes(1); expect(f.releaseQuit).toHaveBeenCalledTimes(1)
})
it('bounds hung read and keeps engine/global cleanup scopes distinct', async () => {
  const f = fixture({ hungRead: true }); const result = f.gate.commit(); await vi.advanceTimersByTimeAsync(5000)
  expect(await result).toMatchObject({ scope: 'EXECUTION_ENGINE', engineCleanup: 'unconfirmed', applicationCleanup: 'unconfirmed', degraded: true }); expect(f.child.kill).toHaveBeenCalledTimes(1)
})
it('uses remaining total budget for child exit after clean engine drain', async () => {
  const f = fixture(); const result = f.gate.commit(); await vi.advanceTimersByTimeAsync(100)
  expect(f.child.kill).toHaveBeenCalledTimes(1); expect(f.gate.allowNativeQuit).toBe(false); f.child.emit('exit'); await vi.advanceTimersByTimeAsync(25)
  expect(await result).toMatchObject({ engineCleanup: 'confirmed', processExitConfirmed: true, applicationCleanup: 'unconfirmed', errorReason: 'NON_ENGINE_CLEANUP_UNOBSERVED' })
})
it.each([{ failedKill: true }, { thrownKill: true }])('exposes kill failure while retaining ownership: %j', async options => {
  const f = fixture(options); const result = f.gate.commit(); await vi.advanceTimersByTimeAsync(5000)
  expect(await result).toMatchObject({ processStopRequested: false, processExitConfirmed: false, errorReason: 'PROCESS_STOP_REQUEST_FAILED' }); expect(f.owner.snapshot().status).toBe('stopping')
})
it('rejects malformed cleanup success instead of certifying it', async () => {
  const f = fixture({ snapshot: { ...clean, tasks: [{ status: 'RUNNING', cleanupConfirmed: true }] } }); const result = f.gate.commit(); await vi.advanceTimersByTimeAsync(0); f.child.emit('exit'); await vi.advanceTimersByTimeAsync(25)
  expect(await result).toMatchObject({ engineCleanup: 'unconfirmed', engineErrorReason: 'SHUTDOWN_STATUS_INVALID' })
})
it('takes bounded fallback on transport failure and supports no-window/no-child commit', async () => {
  const f = fixture({ readFails: true, noChild: true }); const result = await f.gate.commit()
  expect(result).toMatchObject({ processExitConfirmed: true, engineCleanup: 'unconfirmed', engineErrorReason: 'SHUTDOWN_STATUS_UNAVAILABLE' }); expect(f.releaseQuit).toHaveBeenCalledTimes(1)
})
