import { EventEmitter } from 'node:events'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const mock = vi.hoisted(() => ({ spawn: vi.fn(), exists: vi.fn(), server: null as any }))
vi.mock('electron', () => ({ app: { isPackaged: true } }))
vi.mock('child_process', () => ({ spawn: mock.spawn }))
vi.mock('fs', () => ({ default: { existsSync: mock.exists } }))
vi.mock('net', () => ({ default: { createServer: () => mock.server } }))
import { BackendLauncher } from './backend-launcher'
let launcher: BackendLauncher
let child: EventEmitter & { kill: ReturnType<typeof vi.fn>; pid: number; stdout: EventEmitter; stderr: EventEmitter }
let portReady: () => void
beforeEach(() => {
  vi.useFakeTimers(); Object.defineProperty(process, 'resourcesPath', { value: 'C:/test-resources', configurable: true })
  mock.exists.mockReset().mockReturnValue(true)
  mock.server = Object.assign(new EventEmitter(), {
    listen: vi.fn((_port, _host, ready) => { portReady = ready }), address: () => ({ port: 19999 }), close: (done: () => void) => done(),
  })
  child = Object.assign(new EventEmitter(), { pid: 123, kill: vi.fn(() => true), stdout: new EventEmitter(), stderr: new EventEmitter() })
  mock.spawn.mockReset().mockReturnValue(child); vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true })); launcher = new BackendLauncher()
})
afterEach(() => { launcher.stop(); child.emit('exit'); vi.useRealTimers(); vi.unstubAllGlobals() })
async function running() { const started = launcher.start(); portReady(); await started }
it('fences late port allocation and never spawns after a stop request', async () => {
  const result = launcher.start().catch(e => e); launcher.prepareStop(); portReady()
  expect(await result).toMatchObject({ errorReason: 'BACKEND_STOPPED' }); expect(mock.spawn).not.toHaveBeenCalled(); expect(launcher.processExitConfirmed()).toBe(true)
})
it('keeps the child owned and status stopping until actual exit, with once-only SIGTERM', async () => {
  await running(); expect(launcher.stop()).toBe(true); expect(launcher.stop()).toBe(true)
  expect(launcher.getStatus()).toMatchObject({ status: 'stopping', errorReason: 'BACKEND_PROCESS_EXIT_UNCONFIRMED' })
  expect(launcher.processExitConfirmed()).toBe(false); expect(child.kill).toHaveBeenCalledTimes(1)
  await expect(launcher.start()).rejects.toThrow('BACKEND_PROCESS_STILL_OWNED')
  child.emit('exit'); expect(launcher.getStatus().status).toBe('stopped'); expect(launcher.processExitConfirmed()).toBe(true)
})
it.each(['false', 'throw'])('retains ownership after kill %s', async result => {
  await running(); child.kill.mockImplementation(() => { if (result === 'throw') throw new Error('synthetic kill refusal'); return false })
  expect(launcher.stop()).toBe(false); expect(launcher.getStatus()).toMatchObject({ status: 'stopping', errorReason: 'PROCESS_STOP_REQUEST_FAILED' })
  expect(launcher.processExitConfirmed()).toBe(false); await expect(launcher.start()).rejects.toThrow('BACKEND_PROCESS_STILL_OWNED')
})
it('prepareStop cancels a restart timer before it can create a replacement', async () => {
  await running(); child.emit('exit'); expect(launcher.getStatus().status).toBe('crashed'); launcher.prepareStop()
  await vi.advanceTimersByTimeAsync(65000); expect(mock.spawn).toHaveBeenCalledTimes(1); expect(launcher.processExitConfirmed()).toBe(true)
})
it('stops startup without releasing a spawned but unconfirmed child', async () => {
  vi.mocked(fetch).mockImplementation(() => new Promise(() => {}))
  const result = launcher.start().catch(e => e); portReady(); await vi.advanceTimersByTimeAsync(0)
  launcher.prepareStop(); expect(child.kill).not.toHaveBeenCalled(); expect(launcher.processExitConfirmed()).toBe(false)
  launcher.stop(); expect(await result).toMatchObject({ errorReason: 'BACKEND_STOPPED' })
  expect(launcher.getStatus().status).toBe('stopping'); child.emit('exit'); expect(launcher.getStatus().status).toBe('stopped')
})
it('retains an unready child after the readiness deadline instead of allowing replacement', async () => {
  vi.mocked(fetch).mockResolvedValue({ ok: false } as Response)
  const result = launcher.start().catch(e => e); portReady(); await vi.advanceTimersByTimeAsync(31000)
  expect(await result).toMatchObject({ errorReason: 'BACKEND_READY_TIMEOUT' }); expect(launcher.processExitConfirmed()).toBe(false)
  await expect(launcher.start()).rejects.toThrow('BACKEND_PROCESS_STILL_OWNED'); child.emit('exit'); expect(launcher.processExitConfirmed()).toBe(true)
})
