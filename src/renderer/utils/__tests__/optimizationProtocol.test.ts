import { describe, it, expect } from 'vitest'
import { decodeGeneration, decodeOptimizationReport } from '../optimizationProtocol'
const source = { sessionId: 'session', sourceRevision: '1', sql: 'SELECT o.x FROM public.a AS o', connectionId: 'connection', connectionRevision: 'revision', dialect: 'postgresql' }
const side = { rowCount: '9007199254740993', complete: true, columns: [{ label: 'x', jdbcType: 4, typeName: 'int4', precision: 10, scale: 0, signed: true }] }
const report = { version: 1, validationId: 'id', status: 'COMPLETED', verdict: 'MATCH', errorReason: null, degraded: false, source, candidateSql: source.sql, candidateId: null, candidateEvidence: null,
  comparisonMode: 'EXACT_TYPED_BAG_V1', snapshot: { state: 'SHARED_RR', product: 'PostgreSQL', serverVersion: '16.14', database: 'db', databaseUser: 'user', isolation: 'REPEATABLE_READ', readOnly: true, sessionIdentity: '1', settings: {}, sources: [] },
  original: side, candidate: side, differences: [], differencesTruncated: false, startedAt: 'time', completedAt: 'time', persistence: { status: 'SAVED', errorReason: null } }
describe('optimization protocol', () => {
  it('preserves exact counts and requires explicit nullable fields', () => {
    expect(decodeOptimizationReport(report).original?.rowCount).toBe('9007199254740993')
    const { candidateEvidence: _removed, ...missing } = report
    expect(() => decodeOptimizationReport(missing)).toThrow('OPTIMIZATION_PROTOCOL_UNKNOWN')
  })
  it('never converts partial unknown historical or future evidence to a current match', () => {
    for (const mutation of [{ version: 2 }, { status: 'FUTURE' }, { verdict: 'UNKNOWN' }, { snapshot: null }, { degraded: true }, { original: { ...side, complete: false } }, { original: { ...side, rowCount: 1 } }, { snapshot: { ...report.snapshot, readOnly: false } }, { persistence: { status: 'NEW' } }])
      expect(() => decodeOptimizationReport({ ...report, ...mutation })).toThrow('OPTIMIZATION_PROTOCOL_UNKNOWN')
  })
  it('rejects malformed candidates before field consumption', () => {
    expect(decodeGeneration({ version: 1, source, candidates: [], skipped: [] }).source).toEqual(source)
    expect(() => decodeGeneration({ version: 1, source, candidates: [{ id: 'id', sql: 'sql' }], skipped: [] })).toThrow('OPTIMIZATION_PROTOCOL_UNKNOWN')
  })
})
