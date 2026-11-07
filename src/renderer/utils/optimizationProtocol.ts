import type { Candidate, Column, Generation, Report, Side, Source } from '../types/optimization'

type Obj = Record<string, unknown>
const obj = (x: unknown): x is Obj => !!x && typeof x === 'object' && !Array.isArray(x)
const str = (x: unknown): x is string => typeof x === 'string'
const nullable = (x: unknown) => x === null || str(x)
const strings = (x: unknown) => Array.isArray(x) && x.every(str)
const count = (x: unknown) => str(x) && /^(0|[1-9][0-9]*)$/.test(x)
const source = (x: unknown): x is Source => obj(x) && ['sessionId', 'sourceRevision', 'sql', 'connectionId', 'connectionRevision'].every(k => str(x[k]) && !!x[k]) && ['postgresql', 'mysql'].includes(String(x.dialect))
const candidate = (x: unknown): x is Candidate => obj(x) && str(x.id) && x.ruleVersion === 1 && ['positive-in-exists', 'count-star-exists'].includes(String(x.ruleId)) && str(x.path) && str(x.sql) && strings(x.conditions)
const column = (x: unknown): x is Column => obj(x) && str(x.label) && str(x.typeName) && Number.isInteger(x.jdbcType) && Number.isInteger(x.precision) && Number.isInteger(x.scale) && typeof x.signed === 'boolean'
const side = (x: unknown): x is Side => obj(x) && count(x.rowCount) && typeof x.complete === 'boolean' && Array.isArray(x.columns) && x.columns.length <= 32 && x.columns.every(column)
const stringMap = (x: unknown) => obj(x) && Object.values(x).every(str)
export function decodeGeneration(value: unknown): Generation {
  if (!obj(value) || value.version !== 1 || !source(value.source) || !Array.isArray(value.candidates) || value.candidates.length > 16 || !value.candidates.every(candidate) || !Array.isArray(value.skipped) || !value.skipped.every(x => obj(x) && str(x.code) && str(x.path) && str(x.message))) throw new Error('OPTIMIZATION_PROTOCOL_UNKNOWN')
  return value as unknown as Generation
}
export function decodeOptimizationReport(value: unknown): Report {
  if (!obj(value) || value.version !== 1 || !str(value.validationId) || !source(value.source) || !str(value.candidateSql) || !nullable(value.candidateId) || !(value.candidateEvidence === null || candidate(value.candidateEvidence)) ||
    !['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(String(value.status)) || !['NOT_EVALUATED', 'MATCH', 'DIFFERENT', 'INCONCLUSIVE'].includes(String(value.verdict)) ||
    value.comparisonMode !== 'EXACT_TYPED_BAG_V1' || !nullable(value.errorReason) || typeof value.degraded !== 'boolean' || !nullable(value.startedAt) || !nullable(value.completedAt) ||
    !(value.original === null || side(value.original)) || !(value.candidate === null || side(value.candidate)) || typeof value.differencesTruncated !== 'boolean' ||
    !obj(value.persistence) || !['PENDING', 'SAVED', 'FAILED'].includes(String(value.persistence.status)) || !nullable(value.persistence.errorReason) ||
    !Array.isArray(value.differences) || value.differences.length > 20 || !value.differences.every(d => obj(d) && count(d.originalCount) && count(d.candidateCount) && Array.isArray(d.cells) && d.cells.every(c => obj(c) && (c.kind === 'NULL' ? c.value === null : ['EXACT_NUMBER', 'TEXT'].includes(String(c.kind)) && str(c.value))))) throw new Error('OPTIMIZATION_PROTOCOL_UNKNOWN')
  const s = value.snapshot
  if (s !== null && (!obj(s) || !['SHARED_RR', 'UNVERIFIED'].includes(String(s.state)) || !['product', 'serverVersion', 'database', 'databaseUser', 'isolation', 'sessionIdentity'].every(k => str(s[k])) || typeof s.readOnly !== 'boolean' || !stringMap(s.settings) || !Array.isArray(s.sources) || !s.sources.every(p => obj(p) && str(p.schema) && str(p.table) && str(p.kind) && nullable(p.engine) && stringMap(p.checks) && Array.isArray(p.columns) && p.columns.every(column)))) throw new Error('OPTIMIZATION_PROTOCOL_UNKNOWN')
  const report = value as unknown as Report
  if (['MATCH', 'DIFFERENT'].includes(report.verdict) && (report.status !== 'COMPLETED' || report.degraded || !report.original?.complete || !report.candidate?.complete || report.snapshot?.state !== 'SHARED_RR' || !report.snapshot.readOnly || report.snapshot.isolation !== 'REPEATABLE_READ')) throw new Error('OPTIMIZATION_PROTOCOL_UNKNOWN')
  return report
}
