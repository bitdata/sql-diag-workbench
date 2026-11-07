import type { DiagnosticFinding } from '../types/diagnostics'
import type { PlanSqlMapping } from '../types/execution'
import { isDegraded, type FragmentNode } from '../types/fragment'

/** Client-only snapshot of the exact mapping request. Never persisted. */
export interface MappingEvidenceSnapshot {
  key: string
  dialect: string
  executionId: string
  submittedSql: string
  submittedSqlSha256: string
  fragment: unknown
  findings: DiagnosticFinding[]
  evaluationExecutionId: string | null
  planPaths: (string | null)[]
}
export interface MappingPreviewSnapshot extends MappingEvidenceSnapshot {
  generation: number
  scopeKey: string
  draftScope: string | undefined
  backendUrl: string
  sessionId: string | null
  connectionId: string
}
export type MappingAvailability = { status: 'READY' } | { status: 'UNAVAILABLE'; code: string; message: string }
export const unavailable = (code: string, message: string): Extract<MappingAvailability, { status: 'UNAVAILABLE' }> => ({ status: 'UNAVAILABLE', code, message })
export interface MappingRevealRequest {
  draftScope: string | undefined
  executionId: string
  connectionId: string
  dialect: string
  submittedSql: string
  fragmentId: string
  fragmentSql: string
}
export type MappingTarget =
  | { kind: 'SQL_SPAN'; sql: string; start: number; end: number }
  | { kind: 'FRAGMENT'; fragment: FragmentNode; reveal?: MappingRevealRequest }
  | { kind: 'DIAGNOSTIC_EVIDENCE'; finding: DiagnosticFinding }
export type MappingResolution = { status: 'READY'; target: MappingTarget } | Extract<MappingAvailability, { status: 'UNAVAILABLE' }>
export interface MappingPreviewSource {
  snapshot: MappingEvidenceSnapshot
  mode?: 'archive'
  resolve: (ordinal: number) => MappingResolution
  checkReveal?: (request: MappingRevealRequest) => MappingAvailability
  reveal?: (request: MappingRevealRequest) => MappingAvailability
}

/** Iterative traversal: JSON input must not crash preview rendering. */
export function fragmentOccurrences(root: unknown, id: string): FragmentNode[] {
  const found: FragmentNode[] = [], todo: unknown[] = root ? [root] : []
  const visited = new Set<unknown>()
  while (todo.length) {
    const value = todo.pop()
    if (!value || typeof value !== 'object' || visited.has(value)) continue
    visited.add(value)
    const node = value as FragmentNode
    if (node.id === id) found.push(node)
    if (Array.isArray(node.children)) todo.push(...node.children)
  }
  return found
}
const fragmentContent = (node: FragmentNode) => JSON.stringify([
  node.sqlFragment, node.type, node.label, node.executable, node.executionHint,
  node.canonicalSql, node.canonicalizationError ?? null,
])
const splitSurrogate = (sql: string, offset: number) => offset > 0 && offset < sql.length
  && /[\uD800-\uDBFF]/.test(sql[offset - 1]) && /[\uDC00-\uDFFF]/.test(sql[offset])

export function resolveMappingTarget(mapping: PlanSqlMapping, snapshot: MappingPreviewSnapshot,
  ordinal: number, currentScope: string): MappingResolution {
  if (snapshot.scopeKey !== currentScope)
    return unavailable('MAPPING_PREVIEW_STALE', 'This mapping preview is no longer current.')
  const resolved = resolveMappingEvidence(mapping, snapshot, ordinal)
  if (resolved.status === 'READY' && resolved.target.kind === 'FRAGMENT') return {
    status: 'READY', target: { ...resolved.target, reveal: {
      draftScope: snapshot.draftScope, executionId: snapshot.executionId, connectionId: snapshot.connectionId,
      dialect: snapshot.dialect, submittedSql: snapshot.submittedSql,
      fragmentId: resolved.target.fragment.id, fragmentSql: resolved.target.fragment.sqlFragment,
    } },
  }
  return resolved
}

/** Resolves immutable evidence without granting navigation or execution rights. */
export function resolveMappingEvidence(mapping: PlanSqlMapping, snapshot: MappingEvidenceSnapshot, ordinal: number): MappingResolution {
  if (mapping.executionId !== snapshot.executionId || mapping.dialect !== snapshot.dialect
      || mapping.submittedSqlSha256 !== snapshot.submittedSqlSha256
      || mapping.captureKey !== `${snapshot.executionId}:${snapshot.dialect}:${snapshot.submittedSqlSha256}`)
    return unavailable('MAPPING_SCOPE_MISMATCH', 'Mapping identity does not match the submitted query.')
  const edge = mapping.edges[ordinal]
  if (!edge) return unavailable('MAPPING_TARGET_MISSING', 'The mapping target is unavailable.')
  if (edge.confidence === 'UNKNOWN') return unavailable('MAPPING_TARGET_UNKNOWN', 'There is insufficient evidence to view this target.')
  if (!edge.planNodePath || snapshot.planPaths.filter(path => path === edge.planNodePath).length !== 1)
    return unavailable('MAPPING_PLAN_PATH_INVALID', 'The plan path is missing or ambiguous.')
  const relation = { SQL_SPAN: 'PLAN_TO_SQL', FRAGMENT: 'PLAN_TO_FRAGMENT', DIAGNOSTIC_EVIDENCE: 'PLAN_TO_EVIDENCE' }
  if (edge.relation !== relation[edge.targetKind] || (edge.targetKind === 'SQL_SPAN' ? edge.targetId !== null : edge.span !== null))
    return unavailable('MAPPING_RELATION_INVALID', 'The mapping relation does not match its target.')
  if (edge.targetKind === 'SQL_SPAN') {
    const span = edge.span, sql = snapshot.submittedSql
    if (!span || !span.nodeId || !Number.isInteger(span.startOffset) || !Number.isInteger(span.endOffset)
        || span.startOffset < 0 || span.endOffset <= span.startOffset || span.endOffset > sql.length
        || splitSurrogate(sql, span.startOffset) || splitSurrogate(sql, span.endOffset))
      return unavailable('MAPPING_SPAN_INVALID', 'The SQL range is invalid for the submitted text.')
    const root = span.nodeId === 'submitted_root'
    if (edge.confidence !== (root ? 'EXACT' : 'INFERRED'))
      return unavailable('MAPPING_CONFIDENCE_INVALID', 'The evidence strength is unsupported for this target.')
    if (root ? span.type !== 'QUERY' || span.startOffset !== 0 || span.endOffset !== sql.length
        || snapshot.planPaths[0] !== edge.planNodePath : span.type !== 'TABLE_REF')
      return unavailable('MAPPING_SPAN_INVALID', 'The SQL range is invalid for the submitted text.')
    if (!root && sql.slice(span.startOffset, span.endOffset) !== span.label)
      return unavailable('MAPPING_SPAN_TEXT_MISMATCH', 'The SQL range does not match the submitted text.')
    return { status: 'READY', target: { kind: 'SQL_SPAN', sql, start: span.startOffset, end: span.endOffset } }
  }
  if (edge.confidence !== 'INFERRED')
    return unavailable('MAPPING_CONFIDENCE_INVALID', 'The evidence strength is unsupported for this target.')
  if (!edge.targetId) return unavailable('MAPPING_TARGET_MISSING', 'The mapping target is unavailable.')
  if (edge.targetKind === 'FRAGMENT') {
    const nodes = fragmentOccurrences(snapshot.fragment, edge.targetId)
    if (!nodes.length) return unavailable('MAPPING_TARGET_MISSING', 'The mapping target is unavailable.')
    if (nodes.some(node => fragmentContent(node) !== fragmentContent(nodes[0])))
      return unavailable('MAPPING_TARGET_AMBIGUOUS', 'Multiple different targets share this identifier.')
    const fragment = nodes[0]
    if (typeof fragment.sqlFragment !== 'string' || typeof fragment.label !== 'string' || typeof fragment.type !== 'string')
      return unavailable('MAPPING_TARGET_MISSING', 'The mapping target is unavailable.')
    if (isDegraded(fragment) || fragment.executable !== true)
      return unavailable('MAPPING_TARGET_DEGRADED', 'The target identity or execution is degraded.')
    return { status: 'READY', target: { kind: 'FRAGMENT', fragment } }
  }
  const findings = snapshot.findings.filter(finding => finding.id === edge.targetId)
  if (!findings.length || snapshot.evaluationExecutionId !== snapshot.executionId)
    return unavailable('MAPPING_TARGET_MISSING', 'The mapping target is unavailable.')
  if (findings.some(finding => JSON.stringify(finding) !== JSON.stringify(findings[0])))
    return unavailable('MAPPING_TARGET_AMBIGUOUS', 'Multiple different targets share this identifier.')
  const finding = findings[0]
  if (finding.planNodePath !== edge.planNodePath)
    return unavailable('MAPPING_PLAN_PATH_INVALID', 'The plan path is missing or ambiguous.')
  if (finding.degraded) return unavailable('MAPPING_TARGET_DEGRADED', 'The target identity or execution is degraded.')
  return { status: 'READY', target: { kind: 'DIAGNOSTIC_EVIDENCE', finding } }
}

export interface MappingRevealContext {
  locked: boolean
  draftScope: string
  executionId: string | undefined
  provenance: { connectionId: string; dialect: string; sql: string } | null
  node: FragmentNode | undefined
  tree: FragmentNode | null
}
export function checkMappingReveal(request: MappingRevealRequest, context: MappingRevealContext): MappingAvailability {
  if (context.locked) return unavailable('MAPPING_WORKSPACE_LOCKED', 'The workspace is locked.')
  if (request.draftScope !== context.draftScope || request.executionId !== context.executionId
      || request.connectionId !== context.provenance?.connectionId || request.dialect !== context.provenance?.dialect
      || request.submittedSql !== context.provenance?.sql)
    return unavailable('MAPPING_PREVIEW_STALE', 'This mapping preview is no longer current.')
  if (!context.node || context.node.sqlFragment !== request.fragmentSql)
    return unavailable('MAPPING_TARGET_MISSING', 'The mapping target is unavailable.')
  if (isDegraded(context.node) || !context.node.executable)
    return unavailable('MAPPING_TARGET_DEGRADED', 'The target identity or execution is degraded.')
  const nodes = fragmentOccurrences(context.tree, request.fragmentId)
  if (!nodes.length) return unavailable('MAPPING_TREE_TARGET_NOT_VISIBLE', 'This fragment is not shown in the current tree.')
  if (nodes.some(node => node.sqlFragment !== request.fragmentSql))
    return unavailable('MAPPING_TARGET_AMBIGUOUS', 'Multiple different targets share this identifier.')
  return { status: 'READY' }
}
