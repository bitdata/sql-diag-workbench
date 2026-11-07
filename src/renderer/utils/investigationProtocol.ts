import type { InvestigationSnapshot, ObservationReceipt, ReceiptSummary, SnapshotSummary } from '../types/investigation'
import { decodeDiagnosticResponse } from './diagnosticResponse'
import { decodePlanSqlMapping } from './planSqlMapping'
import { resolveMappingEvidence, type MappingEvidenceSnapshot, type MappingPreviewSource } from './mappingTargets'

type Obj = Record<string, unknown>
const object = (x: unknown): x is Obj => !!x && typeof x === 'object' && !Array.isArray(x)
const text = (x: unknown): x is string => typeof x === 'string'
// Spring's v1 mapper omits nullable record properties. Absence and JSON null
// have the same meaning for these optional values; required objects stay strict.
const optionalText = (x: unknown) => x == null || text(x)
const hash = (x: unknown) => text(x) && /^[0-9a-f]{64}$/.test(x)
const date = (x: unknown) => text(x) && Number.isFinite(Date.parse(x))
const invalid = (): never => { throw new Error('SNAPSHOT_INVALID_RESPONSE') }
const requireObject = (x: unknown): Obj => object(x) ? x : invalid()

function plan(raw: unknown) {
  const todo: unknown[] = [raw], seen = new Set<unknown>()
  while (todo.length) {
    const n = requireObject(todo.pop())
    if (seen.has(n) || !text(n.operation) || !Array.isArray(n.children)) invalid()
    seen.add(n)
    if (n.metrics != null) {
      const m = requireObject(n.metrics)
      if (m.version !== 1 || !optionalText(m.sourcePath) || !text(m.origin)) invalid()
      for (const key of ['estimatedRows', 'actualRows', 'loops', 'actualDurationMs', 'totalCost']) {
        const metric = requireObject(m[key])
        if (!['AVAILABLE', 'NOT_COLLECTED', 'NOT_EXECUTED', 'UNKNOWN', 'PARSE_ERROR'].includes(String(metric.state))
          || !optionalText(metric.value) || !text(metric.unit) || !text(metric.basis) || !optionalText(metric.sourceField) || !optionalText(metric.errorReason)) invalid()
      }
    }
    todo.push(...n.children as unknown[])
  }
}
function workspace(raw: unknown) {
  const todo: unknown[] = [raw], seen = new Set<unknown>()
  while (todo.length) {
    const n = requireObject(todo.pop())
    if (seen.has(n) || !text(n.id) || !text(n.sqlFragment) || !text(n.label) || !text(n.type)
      || typeof n.executable !== 'boolean' || !Array.isArray(n.children)) invalid()
    seen.add(n); todo.push(...n.children as unknown[])
  }
}

export function decodeObservationReceipt(raw: unknown, expectedId?: string): ObservationReceipt {
  const p = requireObject(raw), capture = requireObject(p.planCapture)
  if (p.version !== 1 || capture.version !== 1) throw new Error('SNAPSHOT_VERSION_UNSUPPORTED')
  for (const key of ['id', 'executionId', 'connectionId', 'dialect', 'submittedSql']) if (!text(p[key]) || !p[key]) invalid()
  if (expectedId !== undefined && p.id !== expectedId) invalid()
  if (!optionalText(p.sessionId) || !optionalText(p.sessionName) || !hash(p.submittedSqlSha256)
    || !date(p.capturedAt) || !date(p.createdAt) || !date(p.expiresAt) || typeof p.degraded !== 'boolean' || !optionalText(p.errorReason)
    || !['estimated', 'actual'].includes(String(p.requestedMode)) || !Array.isArray(capture.issues)) invalid()
  for (const key of ['connectionId', 'sessionId', 'dialect', 'submittedSql', 'capturedAt', 'requestedMode']) if (p[key] !== capture[key]) invalid()
  if ((capture.issues as unknown[]).some(i => !object(i) || !text(i.code) || !optionalText(i.nodePath) || !text(i.message))) invalid()
  if (capture.details != null) {
    const d = requireObject(capture.details)
    if (d.version !== 1 || !Array.isArray(d.nodes) || !optionalText(d.rawPlan)
      || !optionalText(d.database) || !optionalText(d.databaseUser) || !object(d.settings)) invalid()
    for (const n of d.nodes as unknown[]) if (!object(n) || !text(n.sourcePath) || !optionalText(n.raw)) invalid()
  }
  plan(p.plan)
  return raw as ObservationReceipt
}

export function decodeInvestigationSnapshot(raw: unknown, expectedId?: string): InvestigationSnapshot {
  const v = requireObject(raw), c = requireObject(v.content), integrity = requireObject(v.integrity)
  if (c.version !== 1 || integrity.version !== 1) throw new Error('SNAPSHOT_VERSION_UNSUPPORTED')
  if (!text(c.id) || (expectedId !== undefined && c.id !== expectedId) || !date(c.createdAt) || !hash(integrity.payloadSha256)) invalid()
  const p = decodeObservationReceipt(c.observation), source = requireObject(c.source), annotation = requireObject(c.annotation)
  if (c.sourceObservationId !== p.id || !text(annotation.title) || !annotation.title.trim()
    || !optionalText(annotation.hypothesis) || !optionalText(annotation.observation)) invalid()
  for (const key of ['executionId', 'connectionId', 'sessionId', 'sessionName', 'dialect', 'submittedSql', 'submittedSqlSha256', 'capturedAt', 'requestedMode'] as const)
    if (source[key] !== p[key]) invalid()
  for (const key of ['workspace', 'diagnostics', 'mapping']) {
    const s = requireObject(c[key])
    if (!['AVAILABLE', 'DEGRADED', 'NOT_AVAILABLE'].includes(String(s.state)) || !optionalText(s.reason)
      || (s.state === 'NOT_AVAILABLE' ? s.value != null || !text(s.reason) || !s.reason : s.value == null)) invalid()
  }
  const w = requireObject(c.workspace), d = requireObject(c.diagnostics), m = requireObject(c.mapping)
  if (w.value != null) { workspace(w.value); if (requireObject(w.value).sqlFragment !== p.submittedSql) invalid() }
  if (d.value != null) { if (!w.value) invalid(); decodeDiagnosticResponse(d.value, p.executionId) }
  if (m.value != null) { if (!w.value) invalid(); decodePlanSqlMapping(m.value, { executionId: p.executionId, dialect: p.dialect, hash: p.submittedSqlSha256 }) }
  return raw as InvestigationSnapshot
}

export function decodeInvestigationPage(raw: unknown, kind: 'snapshots' | 'receipts'): { items: (ReceiptSummary | SnapshotSummary)[]; nextCursor: string | null } {
  const page = requireObject(raw)
  if (!Array.isArray(page.items) || !optionalText(page.nextCursor)) invalid()
  const ids = new Set<string>()
  for (const rawItem of page.items as unknown[]) {
    const item = requireObject(rawItem)
    if (!text(item.id) || !item.id || ids.has(item.id) || !date(item.createdAt)
      || !['AVAILABLE', 'UNAVAILABLE'].includes(String(item.availability)) || !optionalText(item.errorReason)
      || !optionalText(item.sessionName) || !optionalText(item.dialect)) invalid()
    ids.add(item.id as string)
    if (item.availability === 'UNAVAILABLE' && (!text(item.errorReason) || !item.errorReason)) invalid()
    if (kind === 'snapshots') {
      if (!text(item.title) || !optionalText(item.serverProduct) || !optionalText(item.serverVersion)
        || ['partial', 'degraded', 'pinned'].some(k => typeof item[k] !== 'boolean')) invalid()
    } else if (!date(item.expiresAt) || !optionalText(item.capturedAt)) invalid()
  }
  return raw as ReturnType<typeof decodeInvestigationPage>
}

export function archivedMappingSource(snapshot: InvestigationSnapshot): MappingPreviewSource | undefined {
  const c = snapshot.content, p = c.observation, mapping = c.mapping.value
  if (!mapping) return undefined
  const paths: (string | null)[] = [], todo = [p.plan]
  while (todo.length) { const node = todo.pop()!; paths.push(node.metrics?.sourcePath ?? null); todo.push(...[...node.children].reverse()) }
  const evidence: MappingEvidenceSnapshot = {
    key: `archive:${c.id}:${snapshot.integrity.payloadSha256}`, executionId: p.executionId, dialect: p.dialect,
    submittedSql: p.submittedSql, submittedSqlSha256: p.submittedSqlSha256,
    fragment: c.workspace.value, findings: c.diagnostics.value?.findings ?? [],
    evaluationExecutionId: c.diagnostics.value?.evaluation.executionId ?? null,
    planPaths: paths,
  }
  return { mode: 'archive', snapshot: evidence, resolve: ordinal => resolveMappingEvidence(mapping, evidence, ordinal) }
}
