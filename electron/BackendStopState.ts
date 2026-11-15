export interface ChildHandle { kill: (signal: 'SIGTERM') => boolean; once: (event: 'exit', listener: () => void) => unknown }
/** Child ownership and generation fence used by BackendLauncher. */
export class BackendStopState {
  private child: ChildHandle | null = null
  private generation = 0
  private launching = false
  private intentional = false
  private killAttempted = false
  private stopAccepted = false
  private reason: string | null = null
  beginStart() {
    if (this.child || this.launching) throw new Error('BACKEND_PROCESS_STILL_OWNED')
    this.intentional = false; this.launching = true; this.reason = null; return ++this.generation
  }
  canSpawn(generation: number) { return generation === this.generation && this.launching && !this.intentional }
  attach(child: ChildHandle, generation: number) {
    if (!this.canSpawn(generation)) throw new Error('BACKEND_STOPPED')
    this.child = child; this.launching = false; this.killAttempted = false; this.stopAccepted = false
    child.once('exit', () => { if (this.child === child) this.child = null })
  }
  startFailed(generation: number) { if (generation === this.generation && !this.child) this.launching = false }
  spawnFailed(child: ChildHandle) { if (this.child === child) this.child = null }
  prepareStop() { this.intentional = true; this.launching = false; ++this.generation }
  requestStop() {
    if (!this.intentional) this.prepareStop()
    if (!this.child) return true
    if (this.killAttempted) return this.stopAccepted
    this.killAttempted = true
    try { this.stopAccepted = this.child.kill('SIGTERM') } catch { this.stopAccepted = false }
    if (!this.stopAccepted) this.reason = 'PROCESS_STOP_REQUEST_FAILED'
    return this.stopAccepted
  }
  processExitConfirmed() { return this.child === null && !this.launching }
  snapshot() { return { status: this.child ? this.intentional ? 'stopping' : 'running' : this.launching ? 'starting' : 'stopped', processExitConfirmed: this.processExitConfirmed(), degraded: this.intentional || this.reason !== null, errorReason: this.reason ?? (this.intentional && this.child ? 'BACKEND_PROCESS_EXIT_UNCONFIRMED' : null) } }
}
