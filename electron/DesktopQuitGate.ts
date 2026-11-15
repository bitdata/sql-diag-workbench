export interface EngineDrainSnapshot { stopping: boolean; drained: boolean; degraded: boolean; errorReason: string; tasks: Array<{ status: string; cleanupConfirmed: boolean }> }
export interface QuitObservation { scope: 'EXECUTION_ENGINE'; engineCleanup: 'confirmed' | 'unconfirmed'; engineErrorReason: string; applicationCleanup: 'unconfirmed'; processStopRequested: boolean; processExitConfirmed: boolean; degraded: true; errorReason: string }
/** Exit contract: one absolute budget after save/discard commit; no Electron imports. */
export class DesktopQuitGate {
  allowNativeQuit = false
  private pending: Promise<QuitObservation> | null = null
  constructor(private readonly actions: { prepareStop: () => void; begin: (signal: AbortSignal) => Promise<void>; read: (signal: AbortSignal) => Promise<unknown>; requestStop: () => boolean; processExitConfirmed: () => boolean; releaseQuit: (value: QuitObservation) => void }, private readonly budgetMs = 5000) {}
  commit(): Promise<QuitObservation> {
    if (this.pending) return this.pending
    this.pending = new Promise(resolve => {
      const controller = new AbortController()
      let done = false, stopPhase = false, stopRequested = false, confirmed = false, engineReason = 'SHUTDOWN_CLEANUP_UNCONFIRMED'
      let next: ReturnType<typeof setTimeout> | undefined
      const requestStop = () => {
        if (stopPhase) return
        stopPhase = true; controller.abort(); clearTimeout(next)
        try { stopRequested = this.actions.requestStop() } catch { stopRequested = false }
      }
      const finish = () => {
        if (done) return
        requestStop(); done = true; clearTimeout(deadline); clearTimeout(next)
        const exited = this.actions.processExitConfirmed()
        const value: QuitObservation = { scope: 'EXECUTION_ENGINE', engineCleanup: confirmed ? 'confirmed' : 'unconfirmed', engineErrorReason: engineReason, applicationCleanup: 'unconfirmed', processStopRequested: stopRequested, processExitConfirmed: exited, degraded: true,
          errorReason: !stopRequested ? 'PROCESS_STOP_REQUEST_FAILED' : !exited ? 'BACKEND_PROCESS_EXIT_UNCONFIRMED' : engineReason !== 'NONE' ? engineReason : 'NON_ENGINE_CLEANUP_UNOBSERVED' }
        this.allowNativeQuit = true; resolve(value); this.actions.releaseQuit(value)
      }
      const deadline = setTimeout(finish, this.budgetMs)
      const observeExit = () => {
        if (done) return
        if (this.actions.processExitConfirmed()) finish()
        else next = setTimeout(observeExit, 25)
      }
      const stopAndObserve = () => { requestStop(); observeExit() }
      const read = async () => {
        try {
          const raw = await this.actions.read(controller.signal)
          if (done || stopPhase) return
          const s = raw as EngineDrainSnapshot
          if (!s || typeof s.stopping !== 'boolean' || typeof s.drained !== 'boolean' || typeof s.degraded !== 'boolean' || typeof s.errorReason !== 'string' || !Array.isArray(s.tasks) || s.tasks.some(t => !t || typeof t.status !== 'string' || typeof t.cleanupConfirmed !== 'boolean') || s.drained && (!s.stopping || s.tasks.some(t => !t.cleanupConfirmed || !['COMPLETED','CANCELLED','FAILED','TIMED_OUT'].includes(t.status)))) { engineReason = 'SHUTDOWN_STATUS_INVALID'; stopAndObserve(); return }
          if (s.drained) { confirmed = true; engineReason = s.degraded ? s.errorReason : 'NONE'; stopAndObserve() }
          else next = setTimeout(() => { void read() }, 100)
        } catch { if (!done && !stopPhase) { engineReason = 'SHUTDOWN_STATUS_UNAVAILABLE'; stopAndObserve() } }
      }
      try { this.actions.prepareStop() } catch { engineReason = 'SHUTDOWN_PREPARE_FAILED'; stopAndObserve(); return }
      void (async () => {
        try { await this.actions.begin(controller.signal); if (!done && !stopPhase) await read() }
        catch { if (!done && !stopPhase) { engineReason = 'SHUTDOWN_STATUS_UNAVAILABLE'; stopAndObserve() } }
      })()
    })
    return this.pending
  }
}
