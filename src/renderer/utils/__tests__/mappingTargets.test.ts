import { describe, expect, it } from 'vitest'
import type { PlanMappingEdge, PlanSqlMapping } from '../../types/execution'
import type { FragmentNode } from '../../types/fragment'
import { checkMappingReveal, resolveMappingTarget, type MappingPreviewSnapshot } from '../mappingTargets'
import { decodePlanSqlMapping } from '../planSqlMapping'

export const fragment: FragmentNode = { id: 'f', type: 'ORIGINAL', label: 'Orders', sqlFragment: 'SELECT * FROM orders',
  executable: true, executionHint: null, nodeCount: 0, ruleName: null, children: [] }
export const snapshot: MappingPreviewSnapshot = { key: 'generation-1', generation: 1, scopeKey: 'scope', draftScope: 'draft',
  backendUrl: 'test', sessionId: 's', connectionId: 'c', dialect: 'postgresql', executionId: 'e',
  submittedSql: 'SELECT * FROM orders', submittedSqlSha256: 'hash', fragment, findings: [{
    id: 'd', ruleId: 'r', severity: 'HIGH', title: 'Scan', message: 'Scan evidence', evidence: ['native plan'],
    confidence: .8, verificationSql: null, affectedNodeId: null, status: 'SUSPECTED', verificationResult: null, planNodePath: '$',
  }], evaluationExecutionId: 'e', planPaths: ['$'] }
export const rootEdge: PlanMappingEdge = { targetKind: 'SQL_SPAN', targetId: null, planNodePath: '$', confidence: 'EXACT',
  relation: 'PLAN_TO_SQL', reason: 'observation', span: { nodeId: 'submitted_root', type: 'QUERY', label: 'Submitted SQL', startOffset: 0, endOffset: 20 } }
export const mapping: PlanSqlMapping = { version: 1, executionId: 'e', dialect: 'postgresql', captureKey: 'e:postgresql:hash',
  submittedSqlSha256: 'hash', degraded: false, errorReason: null, issues: [], edges: [rootEdge,
    { ...rootEdge, targetKind: 'FRAGMENT', targetId: 'f', span: null, confidence: 'INFERRED', relation: 'PLAN_TO_FRAGMENT' },
    { ...rootEdge, targetKind: 'DIAGNOSTIC_EVIDENCE', targetId: 'd', span: null, confidence: 'INFERRED', relation: 'PLAN_TO_EVIDENCE' },
  ] }
const resolve = (edge: PlanMappingEdge, input = snapshot) => resolveMappingTarget({ ...mapping, edges: [edge] }, input, 0, 'scope')
const code = (result: ReturnType<typeof resolveMappingTarget>) => result.status === 'UNAVAILABLE' ? result.code : result.status

describe('mapping target resolution', () => {
  it('resolves three target kinds from the request snapshot and keeps healthy edges in degraded mappings', () => {
    const degraded = { ...mapping, degraded: true, errorReason: 'OTHER_FRAGMENT_FAILED' }
    expect([0, 1, 2].map(i => resolveMappingTarget(degraded, snapshot, i, 'scope').status)).toEqual(['READY', 'READY', 'READY'])
    const finding = resolveMappingTarget(degraded, snapshot, 2, 'scope')
    expect(finding.status === 'READY' && finding.target.kind === 'DIAGNOSTIC_EVIDENCE' && finding.target.finding.title).toBe('Scan')
  })
  it('preserves multibyte text, CRLF and UTF-16 offsets without normalization', () => {
    const sql = "SELECT '中文😀'\r\nFROM orders"
    const start = sql.indexOf('orders')
    const edge = { ...rootEdge, confidence: 'INFERRED' as const, span: { nodeId: 'relation_0', type: 'TABLE_REF', label: 'orders', startOffset: start, endOffset: sql.length } }
    expect(resolve(edge, { ...snapshot, submittedSql: sql })).toMatchObject({ status: 'READY', target: { sql, start, end: sql.length } })
    expect(code(resolve({ ...edge, span: { ...edge.span, label: 'ORDERS' } }, { ...snapshot, submittedSql: sql }))).toBe('MAPPING_SPAN_TEXT_MISMATCH')
    const emoji = sql.indexOf('😀')
    for (const [from, to] of [[emoji + 1, emoji + 2], [emoji, emoji + 1]])
      expect(code(resolve({ ...edge, span: { ...edge.span, label: sql.slice(from, to), startOffset: from, endOffset: to } }, { ...snapshot, submittedSql: sql }))).toBe('MAPPING_SPAN_INVALID')
  })
  it.each([[-1, 20], [0, 21], [4, 4], [5, 4], [0, 1.5], [1, 20]])('rejects invalid root range %s:%s', (startOffset, endOffset) => {
    expect(code(resolve({ ...rootEdge, span: { ...rootEdge.span!, startOffset, endOffset } }))).toBe('MAPPING_SPAN_INVALID')
  })
  it('keeps range failures local while rejecting malformed shape at the decoder', () => {
    const broken = { ...rootEdge, span: { ...rootEdge.span!, startOffset: -1 } }
    const decoded = decodePlanSqlMapping({ ...mapping, edges: [broken, rootEdge] }, { executionId: 'e', dialect: 'postgresql', hash: 'hash' })
    expect(code(resolveMappingTarget(decoded, snapshot, 0, 'scope'))).toBe('MAPPING_SPAN_INVALID')
    expect(code(resolveMappingTarget(decoded, snapshot, 1, 'scope'))).toBe('READY')
    expect(() => decodePlanSqlMapping({ ...mapping, edges: [{ ...rootEdge, span: { ...rootEdge.span, nodeId: 1 } }] }, { executionId: 'e', dialect: 'postgresql', hash: 'hash' })).toThrow('MAPPING_RESPONSE_INVALID')
    expect(() => decodePlanSqlMapping({ ...mapping, issues: [{ code: 'x', message: 'bad', planNodePath: 1 }] }, { executionId: 'e', dialect: 'postgresql', hash: 'hash' })).toThrow('MAPPING_RESPONSE_INVALID')
  })
  it('rejects unknown confidence, wrong relation, extra span/id and unsupported strong claims', () => {
    expect(code(resolve({ ...rootEdge, confidence: 'UNKNOWN' }))).toBe('MAPPING_TARGET_UNKNOWN')
    expect(code(resolve({ ...rootEdge, relation: 'PLAN_TO_FRAGMENT' }))).toBe('MAPPING_RELATION_INVALID')
    expect(code(resolve({ ...rootEdge, targetId: 'f' }))).toBe('MAPPING_RELATION_INVALID')
    expect(code(resolve({ ...mapping.edges[1], span: rootEdge.span }))).toBe('MAPPING_RELATION_INVALID')
    expect(code(resolve({ ...mapping.edges[1], confidence: 'EXACT' }))).toBe('MAPPING_CONFIDENCE_INVALID')
    expect(code(resolve({ ...rootEdge, confidence: 'INFERRED' }))).toBe('MAPPING_CONFIDENCE_INVALID')
    expect(code(resolve({ ...rootEdge, span: { ...rootEdge.span!, type: 'WHERE_CLAUSE' } }))).toBe('MAPPING_SPAN_INVALID')
  })
  it('requires a unique current plan path and exact execution scope', () => {
    expect(code(resolve(rootEdge, { ...snapshot, planPaths: ['$', '$'] }))).toBe('MAPPING_PLAN_PATH_INVALID')
    expect(code(resolve({ ...rootEdge, planNodePath: null }))).toBe('MAPPING_PLAN_PATH_INVALID')
    expect(code(resolveMappingTarget(mapping, snapshot, 0, 'other'))).toBe('MAPPING_PREVIEW_STALE')
    expect(code(resolveMappingTarget({ ...mapping, captureKey: 'other' }, snapshot, 0, 'scope'))).toBe('MAPPING_SCOPE_MISMATCH')
  })
  it('permits identical shared occurrences but rejects same-id different text or metadata and blank degradation', () => {
    const shared = { ...fragment, children: [{ ...fragment }] }
    expect(code(resolve(mapping.edges[1], { ...snapshot, fragment: shared }))).toBe('READY')
    for (const change of [{ sqlFragment: 'SELECT 2' }, { label: 'Other' }, { canonicalizationError: '' }])
      expect(code(resolve(mapping.edges[1], { ...snapshot, fragment: { ...shared, children: [{ ...fragment, ...change }] } }))).toBe('MAPPING_TARGET_AMBIGUOUS')
    expect(code(resolve(mapping.edges[1], { ...snapshot, fragment: { ...fragment, canonicalizationError: '' } }))).toBe('MAPPING_TARGET_DEGRADED')
    expect(code(resolve(mapping.edges[1], { ...snapshot, fragment: { ...fragment, executable: false } }))).toBe('MAPPING_TARGET_DEGRADED')
    expect(code(resolve(mapping.edges[1], { ...snapshot, fragment: null }))).toBe('MAPPING_TARGET_MISSING')
  })
  it('requires request-time finding identity and preserves candidate status', () => {
    const finding = snapshot.findings[0]
    expect(code(resolve(mapping.edges[2], { ...snapshot, evaluationExecutionId: 'other' }))).toBe('MAPPING_TARGET_MISSING')
    expect(code(resolve(mapping.edges[2], { ...snapshot, findings: [finding, { ...finding, status: 'CONFIRMED' }] }))).toBe('MAPPING_TARGET_AMBIGUOUS')
    expect(code(resolve(mapping.edges[2], { ...snapshot, findings: [{ ...finding, degraded: true }] }))).toBe('MAPPING_TARGET_DEGRADED')
    expect(code(resolve(mapping.edges[2], { ...snapshot, findings: [{ ...finding, planNodePath: 'other' }] }))).toBe('MAPPING_PLAN_PATH_INVALID')
  })
})

describe('pure tree reveal eligibility', () => {
  const request = { draftScope: 'draft', executionId: 'e', connectionId: 'c', dialect: 'postgresql', submittedSql: snapshot.submittedSql, fragmentId: 'f', fragmentSql: fragment.sqlFragment }
  const context = { locked: false, draftScope: 'draft', executionId: 'e', provenance: { connectionId: 'c', dialect: 'postgresql', sql: snapshot.submittedSql }, node: fragment, tree: fragment }
  it('allows reachable unchanged targets without modifying its input', () => {
    const before = JSON.stringify(context)
    expect(checkMappingReveal(request, context)).toEqual({ status: 'READY' })
    expect(JSON.stringify(context)).toBe(before)
  })
  it.each(['draftScope', 'executionId', 'connectionId', 'dialect', 'submittedSql'] as const)('rechecks %s at click time', key => {
    expect(checkMappingReveal({ ...request, [key]: 'foreign' }, context)).toMatchObject({ code: 'MAPPING_PREVIEW_STALE' })
  })
  it('refuses hidden projections, changed text, locked state and ambiguous displayed targets', () => {
    expect(checkMappingReveal(request, { ...context, tree: null })).toMatchObject({ code: 'MAPPING_TREE_TARGET_NOT_VISIBLE' })
    expect(checkMappingReveal(request, { ...context, locked: true })).toMatchObject({ code: 'MAPPING_WORKSPACE_LOCKED' })
    expect(checkMappingReveal(request, { ...context, node: { ...fragment, sqlFragment: 'SELECT 2' } })).toMatchObject({ code: 'MAPPING_TARGET_MISSING' })
    expect(checkMappingReveal(request, { ...context, tree: { ...fragment, children: [{ ...fragment, sqlFragment: 'SELECT 2' }] } } )).toMatchObject({ code: 'MAPPING_TARGET_AMBIGUOUS' })
  })
})
