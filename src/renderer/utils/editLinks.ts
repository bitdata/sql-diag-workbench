import { tr } from '../i18n'
// src/renderer/utils/editLinks.ts
import type { FragmentNode } from '../types/fragment'

/** Persisted provenance between independent SQL workspaces. */
export interface EditLink {
  linkId?: string
  sequence?: number
  sourceAlive?: boolean
  targetAlive?: boolean
  sourceNodeId: string
  sourceSessionId: string
  sourceTabName: string
  targetSessionId: string
  targetTabName: string
  targetNodeId: string
}

export interface EditLinksState {
  bySource: Map<string, EditLink>
  byTarget: Map<string, EditLink>
  /** An absent mark is not proof of no history while loading has failed. */
  degraded: boolean
  reason: 'NOT_PERSISTED' | 'LOADING' | 'LOAD_FAILED' | null
  allLinks: Map<string, EditLink>
}

export function createEditLinks(): EditLinksState {
  // Before a backend is available, provenance is explicitly unavailable.
  return { bySource: new Map(), byTarget: new Map(), degraded: true, reason: 'NOT_PERSISTED', allLinks: new Map() }
}

/** Immutable insert. A facet edited more than once points at its latest target. */
export function editLinkKey(sessionId: string, nodeId: string): string { return JSON.stringify([sessionId, nodeId]) }

export function addEditLink(state: EditLinksState, link: EditLink): EditLinksState {
  const bySource = new Map(state.bySource)
  const byTarget = new Map(state.byTarget)
  bySource.set(editLinkKey(link.sourceSessionId, link.sourceNodeId), link)
  byTarget.set(editLinkKey(link.targetSessionId, link.targetNodeId), link)
  const allLinks = new Map(state.allLinks)
  allLinks.set(link.linkId ?? editLinkKey(link.targetSessionId, link.targetNodeId), link)
  return { bySource, byTarget, allLinks, degraded: state.degraded, reason: state.reason }
}

export type EditMarkKind = 'link' | 'orphan' | 'legacy'

/** The mark shown ON the source facet. */
export interface EditMark {
  kind: EditMarkKind
  text: string
  /** Non-null only when jumping is actually possible (kind === 'link'). */
  targetSessionId: string | null
}

/**
 * - link:   the edit is known and its target tab is still open.
 * - orphan: the edit is known but the target tab is closed — say so instead of
 *           offering a jump that would land nowhere.
 * - legacy: no link (session restored, or the node predates the index) but the
 *           node is itself a USER_EDITED product → still gets a mark, per
 *           spec §6.5 ("存量产物不隐藏，带 ✎ 留在树上").
 */
export function resolveEditMark(
  node: FragmentNode,
  links: EditLinksState,
  openSessionIds: string[],
  sessionId: string
): EditMark | null {
  const link = links.bySource.get(editLinkKey(sessionId, node.id))
  if (link) {
    return link.targetAlive !== false && openSessionIds.includes(link.targetSessionId)
      ? { kind: 'link', text: tr("Edited → {0}", link.targetTabName), targetSessionId: link.targetSessionId }
      : { kind: 'orphan', text: tr("Edited (target closed)"), targetSessionId: null }
  }
  if (node.type === 'USER_EDITED') {
    return { kind: 'legacy', text: tr("Edited"), targetSessionId: null }
  }
  return null
}

export type EditOriginKind = 'link' | 'orphan'

/** The reverse mark shown on the facet an edit PRODUCED (inside the new tab). */
export interface EditOriginMark {
  kind: EditOriginKind
  text: string
  sessionId: string | null
}

export function resolveEditOrigin(
  node: FragmentNode,
  links: EditLinksState,
  openSessionIds: string[],
  sessionId: string
): EditOriginMark | null {
  const link = links.byTarget.get(editLinkKey(sessionId, node.id))
  if (!link) return null
  return link.sourceAlive !== false && openSessionIds.includes(link.sourceSessionId)
    ? { kind: 'link', text: tr("← From {0}", link.sourceTabName), sessionId: link.sourceSessionId }
    : { kind: 'orphan', text: tr("← From a closed tab"), sessionId: null }
}
