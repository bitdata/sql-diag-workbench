import type { PlanSqlMapping } from '../types/execution'

export async function sqlSha256(sql: string): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(sql))),
    byte => byte.toString(16).padStart(2, '0')).join('')
}

/** Decode the additive contract before any untrusted arrays reach PlanView. */
export function decodePlanSqlMapping(value: unknown, scope: { executionId: string; dialect: string; hash: string }): PlanSqlMapping {
  if (!value || typeof value !== 'object') throw new Error('MAPPING_RESPONSE_INVALID')
  const mapping = value as PlanSqlMapping
  if (mapping.version !== 1 || !Array.isArray(mapping.edges) || !Array.isArray(mapping.issues)
      || typeof mapping.degraded !== 'boolean' || !(mapping.errorReason === null || typeof mapping.errorReason === 'string'))
    throw new Error('MAPPING_RESPONSE_INVALID')
  if (mapping.executionId !== scope.executionId || mapping.dialect !== scope.dialect
      || mapping.submittedSqlSha256 !== scope.hash || mapping.captureKey !== `${scope.executionId}:${scope.dialect}:${scope.hash}`)
    throw new Error('MAPPING_SCOPE_MISMATCH')
  for (const edge of mapping.edges) {
    if (!edge || !['SQL_SPAN', 'FRAGMENT', 'DIAGNOSTIC_EVIDENCE'].includes(edge.targetKind)
        || !['EXACT', 'INFERRED', 'UNKNOWN'].includes(edge.confidence) || typeof edge.reason !== 'string'
        || !(edge.planNodePath === null || typeof edge.planNodePath === 'string')
        || !(edge.targetId === null || typeof edge.targetId === 'string')
        || !['PLAN_TO_SQL', 'PLAN_TO_FRAGMENT', 'PLAN_TO_EVIDENCE'].includes(edge.relation)
        || (edge.span !== null && (!edge.span || typeof edge.span.nodeId !== 'string' || typeof edge.span.label !== 'string' || typeof edge.span.type !== 'string'
          || !Number.isInteger(edge.span.startOffset) || !Number.isInteger(edge.span.endOffset))))
      throw new Error('MAPPING_RESPONSE_INVALID')
  }
  if (mapping.issues.some(issue => !issue || typeof issue.code !== 'string' || typeof issue.message !== 'string'
      || !(issue.planNodePath === null || typeof issue.planNodePath === 'string')))
    throw new Error('MAPPING_RESPONSE_INVALID')
  return mapping
}
