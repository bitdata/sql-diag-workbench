import { spawn, type ChildProcess } from 'child_process'
import { join, resolve } from 'path'
import { app } from 'electron'
import net from 'net'
import { EventEmitter } from 'events'
import fs from 'fs'
import { randomBytes } from 'crypto'

export type BackendStatus = 'starting' | 'running' | 'stopped' | 'crashed' | 'error'
export interface BackendStatusEvent {
  status: BackendStatus
  error?: string
  errorReason?: string
  degraded?: boolean
  port?: number
}
export class BackendFailure extends Error {
  constructor(readonly errorReason: string, message: string) { super(message) }
}
export function backendPaths(packaged: boolean, platform: string, resources: string, devDirectory: string) {
  return {
    jar: packaged ? join(resources, 'backend.jar') : resolve(devDirectory, '../../backend/target/backend.jar'),
    java: packaged && platform === 'win32' ? join(resources, 'runtime/bin/java.exe') : 'java',
  }
}
function findFreePort(): Promise<number> {
  return new Promise((resolvePort, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      if (!address || typeof address === 'string') { server.close(); reject(new Error('Port allocation failed')); return }
      server.close(error => error ? reject(error) : resolvePort(address.port))
    })
  })
}

export class BackendLauncher extends EventEmitter {
  private process: ChildProcess | null = null
  private port = 0
  private restartCount = 0
  private lastStartTime = 0
  private restartTimer: ReturnType<typeof setTimeout> | null = null
  private stoppedIntentionally = false
  private authToken = ''
  private state: BackendStatusEvent = { status: 'stopped', degraded: true, errorReason: 'BACKEND_NOT_STARTED' }
  private abortStartup: (() => void) | null = null

  getStatus() { return { ...this.state } }
  async getHealthStatus() {
    const initial = this.state
    if (initial.status === 'running') {
      try {
        const response = await fetch(`http://127.0.0.1:${this.port}/api/health`, { signal: AbortSignal.timeout(2000) })
        if (!response.ok) throw new Error('Health check failed')
      } catch {
        if (this.state === initial) return { status: 'DOWN' as const, degraded: true, errorReason: 'BACKEND_HEALTH_UNAVAILABLE', error: 'The backend is not responding. Close the app and try again.', backendEvent: this.getStatus() }
      }
    }
    const event = this.getStatus()
    return { ...event, status: event.status === 'running' ? 'UP' as const : 'DOWN' as const, backendEvent: event }
  }
  getPort() { return this.port }
  getToken() { return this.authToken }

  async start(): Promise<number> {
    this.stoppedIntentionally = false
    this.restartCount = 0
    try {
      this.port = await findFreePort()
      this.authToken = randomBytes(32).toString('hex')
      await this.launchProcess()
      return this.port
    } catch (error) {
      if (!this.stoppedIntentionally) this.failure(error)
      throw error
    }
  }
  private failure(error: unknown) {
    const failure = error instanceof BackendFailure ? error : new BackendFailure('BACKEND_START_FAILED', 'Could not start the backend. Close the app and try again. If the problem persists, reinstall the application.')
    this.emitStatus('error', failure.message, failure.errorReason)
  }
  private async launchProcess(): Promise<void> {
    const paths = backendPaths(app.isPackaged, process.platform, process.resourcesPath, __dirname)
    if (!fs.existsSync(paths.jar)) throw new BackendFailure('BACKEND_JAR_MISSING', app.isPackaged
      ? 'The backend is missing. Reinstall the application; your saved data will be kept.'
      : 'Backend JAR not found. Build it first with Maven: cd backend && mvn package -DskipTests.')
    if (app.isPackaged && process.platform === 'win32' && !fs.existsSync(paths.java)) {
      throw new BackendFailure('BUNDLED_RUNTIME_MISSING', 'The bundled Java runtime is missing. Reinstall the application; your saved data will be kept.')
    }
    this.lastStartTime = Date.now()
    this.emitStatus('starting')
    const child = spawn(paths.java, ['-jar', paths.jar, `--server.port=${this.port}`, `--sql-workbench.auth.token=${this.authToken}`,
      ...(process.env.SQL_WORKBENCH_TEST_ROOT ? [
        `--sql-workbench.data-dir=${join(resolve(process.env.SQL_WORKBENCH_TEST_ROOT), 'data')}`,
        `--sql-workbench.credentials.legacy-path=${join(resolve(process.env.SQL_WORKBENCH_TEST_ROOT), 'data', 'credentials.dat')}`,
      ] : [])], { windowsHide: true })
    this.process = child
    let ready = false
    let ended = false
    child.stdout?.on('data', (data: Buffer) => console.log(`[backend] ${data.toString().trim()}`))
    child.stderr?.on('data', (data: Buffer) => console.error(`[backend:err] ${data.toString().trim()}`))
    await new Promise<void>((resolveReady, reject) => {
      let timer: ReturnType<typeof setTimeout> | undefined
      let request: AbortController | undefined
      const cleanup = () => { clearTimeout(timer); request?.abort(); this.abortStartup = null }
      const fail = (reason: string, message: string) => {
        if (ended) return
        ended = true; cleanup()
        if (this.process === child) this.process = null
        child.kill()
        reject(new BackendFailure(reason, message))
      }
      this.abortStartup = () => fail('BACKEND_STOPPED', 'Backend startup was stopped.')
      child.once('error', (error: NodeJS.ErrnoException) => {
        const missing = error.code === 'ENOENT'
        fail(missing ? (app.isPackaged && process.platform === 'win32' ? 'BUNDLED_RUNTIME_MISSING' : 'JAVA_NOT_FOUND') : 'BACKEND_SPAWN_FAILED',
          missing && !app.isPackaged ? 'Java runtime not found. Install JDK 17+ and add java to PATH.'
            : 'Could not launch the backend runtime. Reinstall the application or check that security software has not blocked it.')
      })
      child.once('exit', () => {
        if (this.process === child) this.process = null
        if (!ready) { fail('BACKEND_EXITED_EARLY', 'The backend stopped before it was ready. Reinstall the application or check the application logs.'); return }
        if (this.stoppedIntentionally) { this.emitStatus('stopped'); return }
        this.emitStatus('crashed', 'The backend stopped unexpectedly. Attempting to restart…', 'BACKEND_CRASHED')
        if (Date.now() - this.lastStartTime >= 30_000) this.restartCount = 0
        this.scheduleRestart()
      })
      const deadline = Date.now() + 30_000
      const check = async () => {
        if (ended || this.stoppedIntentionally) return
        request = new AbortController()
        const requestTimer = setTimeout(() => request?.abort(), 1500)
        try {
          const response = await fetch(`http://127.0.0.1:${this.port}/api/health`, { signal: request.signal })
          if (response.ok && !ended && !this.stoppedIntentionally) {
            ready = true; ended = true; cleanup()
            this.emitStatus('running'); resolveReady(); return
          }
        } catch {
          // A bounded health probe can fail while starting; state remains observably "starting".
        } finally { clearTimeout(requestTimer) }
        if (ended) return
        if (Date.now() >= deadline) { fail('BACKEND_READY_TIMEOUT', 'The backend did not become ready within 30 seconds. Close the app and try again.'); return }
        timer = setTimeout(check, 300)
      }
      void check()
    })
  }
  private scheduleRestart(): void {
    if (this.stoppedIntentionally || this.restartTimer) return
    if (++this.restartCount > 5) { this.emitStatus('error', 'The backend could not stay running after five restart attempts. Close the app and try again.', 'BACKEND_RESTART_EXHAUSTED'); return }
    this.restartTimer = setTimeout(async () => {
      this.restartTimer = null
      try { await this.launchProcess() }
      catch (error) { if (!this.stoppedIntentionally) { this.failure(error); this.scheduleRestart() } }
    }, Math.min(1000 * 2 ** (this.restartCount - 1), 60_000))
  }
  private emitStatus(status: BackendStatus, error?: string, errorReason?: string) {
    this.state = { status, error, errorReason, degraded: status !== 'running', port: this.port }
    this.emit('status-change', this.getStatus())
  }
  stop(): void {
    this.stoppedIntentionally = true
    if (this.restartTimer) clearTimeout(this.restartTimer)
    this.restartTimer = null
    this.abortStartup?.()
    this.process?.kill('SIGTERM')
    this.process = null
    this.emitStatus('stopped', undefined, 'BACKEND_STOPPED')
  }
}
