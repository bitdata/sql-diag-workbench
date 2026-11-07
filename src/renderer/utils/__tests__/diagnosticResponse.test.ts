import { describe, expect, it } from 'vitest'
import { decodeDiagnosticResponse } from '../diagnosticResponse'
import { mockFinding } from '../../test/fixtures'

const evaluation = { version: 1, executionId: 'e', sourceState: 'VERIFIED', rules: [], degraded: false, errorReason: null }
const valid = () => ({ evaluation, findings: [mockFinding], reportPersistence: { status: 'not_requested', errorReason: null } })
describe('diagnostic response boundary', () => {
  it('keeps legitimate empty findings, skipped coverage, limited observations and save warnings', () => {
    expect(decodeDiagnosticResponse({ ...valid(), findings: [] }, 'e').findings).toEqual([])
    for (const sourceState of ['VERIFIED', 'EXPIRED', 'UNVERIFIED', 'MISMATCH', 'UNAVAILABLE']) {
      const raw = { ...valid(), evaluation: { ...evaluation, sourceState, degraded: true, errorReason: 'PARTIAL_EVALUATION',
        rules: [{ ruleId: 'r', status: 'SKIPPED', errorReason: 'unsupported' }] }, reportPersistence: { status: 'failed', errorReason: 'REPORT_SAVE_FAILED' } }
      expect(decodeDiagnosticResponse(raw, 'e')).toEqual(raw)
    }
  })
  it.each([null, [], {}, { findings: [] }, { evaluation }, { ...valid(), evaluation: { ...evaluation, version: 99 } },
    { ...valid(), evaluation: { ...evaluation, sourceState: 'invented' } },
    { ...valid(), evaluation: { ...evaluation, rules: [{ ruleId: 'r', status: 'invented' }] } },
    { ...valid(), findings: [mockFinding, mockFinding] },
    ...[{ severity: 'CRITICAL' }, { status: 'READY' }, { evidence: [1] }, { confidence: NaN }, { confidence: 2 }, { verificationSql: {} }, { title: null }, { planNodePath: 3 }]
      .map(patch => ({ ...valid(), findings: [{ ...mockFinding, ...patch }] })),
    { ...valid(), reportPersistence: { status: 'invented' } }])('rejects malformed wire data %j', raw => {
    expect(() => decodeDiagnosticResponse(raw, 'e')).toThrow('DIAGNOSTICS_RESPONSE_INVALID')
  })
  it('rejects an otherwise valid receipt for a different execution', () => {
    expect(() => decodeDiagnosticResponse(valid(), 'other')).toThrow('DIAGNOSTICS_EXECUTION_MISMATCH')
  })
})
