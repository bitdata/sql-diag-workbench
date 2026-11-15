import { expect, it } from 'vitest'
import samples from './fixtures/oracle-native-receipts.json'
import { decodeObservationReceipt } from '../investigationProtocol'
import { decodeOracleExecution } from '../oraclePlanProtocol'

it.each(samples)('accepts Java native evidence through receipt and live decoders: $name', ({ receipt }) => {
  expect(decodeObservationReceipt(receipt).planCapture.details?.channel).toBe('ORACLE_PLAN_ROWS')
  expect(decodeOracleExecution({ status: 'COMPLETED', plan: receipt.plan, planCapture: receipt.planCapture }).plan).toBe(receipt.plan)
})

it('keeps a declared structural failure observable without inventing a plan tree', () => {
  const value = structuredClone(samples[0].receipt)
  const capture = { ...value.planCapture, parseStatus: 'FAILED', issues: [{ code: 'PLAN_DEPTH_LIMIT', sourcePath: null, message: 'Bounded structure rejected' }],
    details: { ...value.planCapture.details, errorReason: 'PLAN_DEPTH_LIMIT', nativeEvidence: null } }
  const result = { status: 'COMPLETED', plan: null, planCapture: capture, degraded: true, errorReason: 'PLAN_DEPTH_LIMIT' }
  expect(decodeOracleExecution(result).errorReason).toBe('PLAN_DEPTH_LIMIT')
  expect(() => decodeOracleExecution({ ...result, degraded: false })).toThrow('ORACLE_PLAN_INVALID_RESPONSE')
  expect(() => decodeOracleExecution({ ...result, planCapture: { ...capture, parseStatus: 'COMPLETE' } })).toThrow('ORACLE_PLAN_INVALID_RESPONSE')
})

it.each([
  (v: any) => { v.planCapture.details.nativeEvidence.nodes[0].metrics.lastOutputRows.value = 9007199254740993 },
  (v: any) => { v.planCapture.details.nativeEvidence.nodes[0].metrics.lastElapsedMicros.unit = 'SECONDS' },
  (v: any) => { v.planCapture.details.nativeEvidence.nodes[0].sourcePath = 'oracle/id/unknown' },
  (v: any) => { v.planCapture.details.nodes = [] },
  (v: any) => { v.planCapture.details.rawPlan = '计'.repeat(180000) },
  (v: any) => { v.planCapture.details.channel = 'SQLSERVER_XML' },
])('rejects corrupt native evidence before live or archived rendering %#', mutate => {
  const value = structuredClone(samples[0].receipt)
  mutate(value)
  expect(() => decodeObservationReceipt(value)).toThrow('ORACLE_PLAN_INVALID_RESPONSE')
  expect(() => decodeOracleExecution({ status: 'COMPLETED', plan: value.plan, planCapture: value.planCapture })).toThrow('ORACLE_PLAN_INVALID_RESPONSE')
})
