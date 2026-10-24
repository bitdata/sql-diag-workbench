import { EventEmitter } from 'node:events'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const mock = vi.hoisted(() => ({ spawn: vi.fn(), exists: vi.fn(), app: { isPackaged: true } }))
vi.mock('electron', () => ({ app: mock.app }))
vi.mock('child_process', () => ({ spawn: mock.spawn }))
vi.mock('fs', () => ({ default: { existsSync: mock.exists } }))
import { BackendLauncher, backendPaths } from './backend-launcher'

describe('backend runtime and retained startup outcomes', () => {
  let launcher: BackendLauncher
  let child: EventEmitter & { kill: ReturnType<typeof vi.fn>; stdout: EventEmitter; stderr: EventEmitter }
  beforeEach(() => {
    Object.defineProperty(process, 'resourcesPath', { value: 'C:/test-resources', configurable: true })
    vi.useFakeTimers()
    mock.app.isPackaged = true
    mock.exists.mockReset().mockReturnValue(true)
    child = Object.assign(new EventEmitter(), { kill: vi.fn(), stdout: new EventEmitter(), stderr: new EventEmitter() })
    mock.spawn.mockReset().mockReturnValue(child)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
    launcher = new BackendLauncher()
  })
  afterEach(() => { launcher.stop(); vi.useRealTimers(); vi.unstubAllGlobals() })
  it('uses a bundled Windows executable even if PATH Java exists; dev and other platforms keep their existing executable', () => {
    expect(backendPaths(true, 'win32', 'C:/app/resources', 'C:/dev/out/main').java).toMatch(/runtime[\\/]bin[\\/]java.exe$/)
    expect(backendPaths(false, 'win32', '', 'C:/dev/out/main').java).toBe('java')
    expect(backendPaths(true, 'linux', '/app/resources', '').java).toBe('java')
  })
  it('retains the missing runtime reason for the initial read, with no PATH fallback', async () => {
    mock.exists.mockImplementation((file: string) => !file.endsWith('java.exe'))
    await expect(launcher.start()).rejects.toMatchObject({ errorReason: 'BUNDLED_RUNTIME_MISSING' })
    expect(mock.spawn).not.toHaveBeenCalled()
    expect(await launcher.getHealthStatus()).toMatchObject({ status: 'DOWN', degraded: true, errorReason: 'BUNDLED_RUNTIME_MISSING' })
  })
  it('rejects missing JAR without spawning or exposing filesystem paths', async () => {
    mock.exists.mockReturnValue(false)
    await expect(launcher.start()).rejects.toMatchObject({ errorReason: 'BACKEND_JAR_MISSING' })
    expect(mock.spawn).not.toHaveBeenCalled()
    expect(launcher.getStatus().error).not.toContain('test-resources')
  })
  it('does not erase a spawn failure with a later readiness timeout', async () => {
    mock.spawn.mockImplementation(() => {
      queueMicrotask(() => child.emit('error', Object.assign(new Error('sensitive native path'), { code: 'EACCES' })))
      return child
    })
    await expect(launcher.start()).rejects.toMatchObject({ errorReason: 'BACKEND_SPAWN_FAILED' })
    await vi.advanceTimersByTimeAsync(31000)
    expect(launcher.getStatus()).toMatchObject({ status: 'error', errorReason: 'BACKEND_SPAWN_FAILED' })
    expect(launcher.getStatus().error).not.toContain('sensitive')
  })
  it('reports health failure while keeping the launcher lifecycle status available', async () => {
    await launcher.start()
    expect(await launcher.getHealthStatus()).toMatchObject({ status: 'UP', degraded: false })
    vi.mocked(fetch).mockRejectedValueOnce(new Error('secret'))
    expect(await launcher.getHealthStatus()).toMatchObject({ status: 'DOWN', errorReason: 'BACKEND_HEALTH_UNAVAILABLE', degraded: true })
  })
  it('bounds readiness and kills an unready child with an observable timeout', async () => {
    vi.mocked(fetch).mockResolvedValue({ ok: false } as Response)
    let spawned!: () => void
    const created = new Promise<void>(resolve => { spawned = resolve })
    mock.spawn.mockImplementation(() => { spawned(); return child })
    const outcome = launcher.start().catch(error => error)
    await created
    await vi.advanceTimersByTimeAsync(31000)
    expect(await outcome).toMatchObject({ errorReason: 'BACKEND_READY_TIMEOUT' })
    expect(child.kill).toHaveBeenCalled()
    expect(await launcher.getHealthStatus()).toMatchObject({ status: 'DOWN', degraded: true, errorReason: 'BACKEND_READY_TIMEOUT' })
  })
  it('retains an early process exit instead of waiting for readiness timeout', async () => {
    vi.mocked(fetch).mockResolvedValue({ ok: false } as Response)
    mock.spawn.mockImplementation(() => {
      queueMicrotask(() => child.emit('exit', 1))
      return child
    })
    await expect(launcher.start()).rejects.toMatchObject({ errorReason: 'BACKEND_EXITED_EARLY' })
    await vi.advanceTimersByTimeAsync(31000)
    expect(launcher.getStatus().errorReason).toBe('BACKEND_EXITED_EARLY')
  })
  it('stops a pending restart without spawning a replacement', async () => {
    await launcher.start()
    child.emit('exit', 1)
    expect(launcher.getStatus().errorReason).toBe('BACKEND_CRASHED')
    launcher.stop()
    await vi.advanceTimersByTimeAsync(65000)
    expect(mock.spawn).toHaveBeenCalledTimes(1)
    expect(launcher.getStatus().status).toBe('stopped')
  })
})
