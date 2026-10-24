import { randomUUID } from 'crypto'
export interface ExitIssue { sessionId?: string; tabName?: string; code: string; message: string }
export interface ExitResult {
  attemptId: string
  status: 'saved' | 'failed' | 'blocked' | 'timed-out'
  savedSessionIds: string[]
  issues: ExitIssue[]
}
export type ExitDecision = 'retry' | 'cancel' | 'discard'

export function validExitResult(value: unknown): value is ExitResult {
  if (!value || typeof value !== 'object') return false
  const r = value as ExitResult
  return typeof r.attemptId === 'string' && r.attemptId.length <= 100
    && ['saved', 'failed', 'blocked', 'timed-out'].includes(r.status)
    && Array.isArray(r.savedSessionIds) && r.savedSessionIds.length <= 10000
    && r.savedSessionIds.every(id => typeof id === 'string' && id.length <= 200)
    && Array.isArray(r.issues) && r.issues.length <= 1000
    && r.issues.every(i => i && typeof i.code === 'string' && i.code.length <= 100
      && typeof i.message === 'string' && i.message.length <= 2000
      && (i.sessionId === undefined || typeof i.sessionId === 'string' && i.sessionId.length <= 200)
      && (i.tabName === undefined || typeof i.tabName === 'string' && i.tabName.length <= 500))
    && (r.status !== 'saved' || r.issues.length === 0)
}

/** Electron-independent state machine. Only current-attempt replies can release quit. */
export class ExitCoordinator {
  private attempt: string | null = null
  private timer?: ReturnType<typeof setTimeout>
  private nativeChoice = false
  committed = false
  constructor(private readonly actions: {
    request: (attemptId: string) => void
    cancel: (attemptId: string) => void
    unavailable: (attemptId: string) => void
    quit: () => void
  }, private readonly timeoutMs = 35000) {}
  request() {
    if (this.committed || this.attempt) return
    const id = randomUUID()
    this.nativeChoice = false
    this.attempt = id
    this.timer = setTimeout(() => this.unavailable(id), this.timeoutMs)
    try { this.actions.request(id) } catch { this.unavailable(id) }
  }
  unavailable(id = this.attempt) {
    if (!id || id !== this.attempt || this.nativeChoice) return
    this.nativeChoice = true
    clearTimeout(this.timer)
    this.actions.unavailable(id)
  }
  result(value: unknown) {
    if (this.nativeChoice || !validExitResult(value) || value.attemptId !== this.attempt) return
    clearTimeout(this.timer)
    if (value.status === 'saved') this.commit()
  }
  decision(id: string, decision: ExitDecision) {
    if (id !== this.attempt || !this.attempt) return
    clearTimeout(this.timer)
    if (decision === 'discard') this.commit()
    else {
      this.attempt = null
      this.actions.cancel(id)
      if (decision === 'retry') this.request()
    }
  }
  private commit() {
    clearTimeout(this.timer)
    this.committed = true
    this.attempt = null
    this.actions.quit()
  }
}
