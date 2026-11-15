import type { DiagnosticContext, IdentityContext, SessionWorkspaceState } from '../types/session'
import type { FragmentNode } from '../types/fragment'

export const IDENTITY_POLICY = 'structural-p0p1-v2'
export const LEGACY_POLICY = 'unversioned-legacy'
export const LEGACY_READ_ONLY = 'This saved exploration is read-only. Copy its SQL into a new exploration to continue.'

/** Capture at the operation's source; never read a global active tab inside apiFetch. */
export function identityContext(ws?: SessionWorkspaceState | null): IdentityContext {
  if (!ws || ws.readOnly || ws.identityPolicy !== IDENTITY_POLICY) throw new Error(LEGACY_READ_ONLY)
  if (!Number.isSafeInteger(ws.workspaceRevision) || ws.workspaceRevision! < 0) throw new Error('Invalid workspace revision; reload this exploration.')
  return { sessionId: ws.sessionId, identityPolicy: ws.identityPolicy, workspaceRevision: ws.workspaceRevision! }
}
export function graphRequest(ws?: SessionWorkspaceState | null, nodes: Iterable<FragmentNode> = []) {
  return { identityContext: identityContext(ws), nodes: [...nodes].map(n => {
    if (!n.canonicalSql || n.canonicalizationError != null) throw new Error('A graph node has no verified identity.')
    return { id: n.id, sqlFragment: n.sqlFragment, canonicalSql: n.canonicalSql }
  }) }
}

export function diagnosticContext(ws?: SessionWorkspaceState | null): DiagnosticContext {
  const context = identityContext(ws)
  if (!ws?.workspaceInstanceId || !/^[0-9a-f]{32}$/.test(ws.workspaceInstanceId)) throw new Error('WORKSPACE_INSTANCE_REQUIRED')
  if (ws.hydration !== 'ready' || ws.recoveryBlocked) throw new Error('WORKSPACE_UNAVAILABLE')
  return { ...context, workspaceInstanceId: ws.workspaceInstanceId }
}
