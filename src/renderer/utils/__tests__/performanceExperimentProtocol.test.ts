import { describe, expect, it } from 'vitest'
import { decodePerformanceExperiment } from '../performanceExperimentProtocol'

const source = { sessionId: 's', sourceRevision: 'r', sql: 'SELECT 1', connectionId: 'c', connectionRevision: 'cr', dialect: 'postgresql' as const }
const sample = (pair: number, phase: 'WARMUP' | 'MEASURED', order: 'AB' | 'BA', side: 'A' | 'B') => ({ pair, phase, order, side, status: 'COMPLETE', durationNs: '10', executeReturnNs: '2', drainNs: '6', closeNs: '2', rows: '1', bytes: '8', errorReason: null })
const report = () => ({ version: 1, experimentId: 'e', status: 'COMPLETED', phase: 'DONE', comparisonState: 'COMPLETE', errorReason: null, degraded: false, request: { version: 1, source, candidateSql: 'SELECT 1', candidateId: null, seed: '42', timeoutSeconds: 120 }, protocol: { id: 'PAIRED_SELECT_V1', seed: '42', warmupPairs: 2, measuredPairs: 10, orderAlgorithm: 'LCG32_FISHER_YATES_V1', pgPreparation: 'THRESHOLD_ZERO', mysqlPreparation: 'CLIENT_PREPARED_STREAMING', fetchSize: 128 }, validation: null, environment: null, samples: Array.from({ length: 2 }, (_, pair) => ['A', 'B'].map(side => sample(pair, 'WARMUP', 'AB', side as 'A' | 'B'))).flat(), summary: { state: 'NOT_AVAILABLE', original: null, candidate: null, pairedMedianDeltaNs: null, relativeMedianChangePct: null, notes: [] }, persistence: { status: 'PENDING', errorReason: null }, createdAt: '2026-01-01T00:00:00Z', startedAt: null, completedAt: null })

describe('performance experiment protocol', () => {
  const measured = () => ({ ...report(), samples: [sample(0,'WARMUP','AB','A'),sample(0,'WARMUP','AB','B'),sample(1,'WARMUP','BA','B'),sample(1,'WARMUP','BA','A'),...Array.from({length:10},(_,pair)=>[sample(pair,'MEASURED',pair<5?'AB':'BA',pair<5?'A':'B'),sample(pair,'MEASURED',pair<5?'AB':'BA',pair<5?'B':'A')]).flat()], summary: {...report().summary,state:'DESCRIPTIVE'} })
  const failedPlans = (reason:string) => {
    const evidence={status:'FAILED',dialect:'postgresql',channel:'POSTGRESQL_JSON',connectionRevision:'cr',submittedSql:'SELECT 1',explainSql:'EXPLAIN SELECT 1',capturedAt:'2026-10-26T00:00:00Z',databaseProduct:null,databaseVersion:null,sessionIdentity:null,details:null,errorReason:reason,degraded:true}
    return {original:evidence,candidate:evidence,degraded:true,errorReason:'ACTUAL_PLAN_EVIDENCE_INCOMPLETE'}
  }
  it('accepts live actual-plan capture after complete measurements',()=>{
    expect(decodePerformanceExperiment({...measured(),status:'RUNNING',phase:'ACTUAL_PLAN'}).comparisonState).toBe('COMPLETE')
  })
  it.each([['CANCELLED','CANCELLED'],['TIMED_OUT','DEADLINE_EXCEEDED']])('retains completed measurements when plan capture ends %s',(status,reason)=>{
    const value={...measured(),status,phase:'CLEANUP',errorReason:reason,degraded:true,actualPlans:failedPlans(reason)}
    expect(decodePerformanceExperiment(value).summary.state).toBe('DESCRIPTIVE')
    expect(()=>decodePerformanceExperiment({...value,samples:value.samples.slice(1)})).toThrow('EXPERIMENT_PROTOCOL_UNKNOWN')
    expect(()=>decodePerformanceExperiment({...value,actualPlans:null})).toThrow('EXPERIMENT_PROTOCOL_UNKNOWN')
  })
  it('accepts a bounded running report without coercing numeric strings', () => expect(decodePerformanceExperiment({ ...report(), status: 'RUNNING', phase: 'MEASURED', comparisonState: 'IN_PROGRESS' }).request.seed).toBe('42'))
  it('rejects an unknown version', () => expect(() => decodePerformanceExperiment({ ...report(), version: 2 })).toThrow('EXPERIMENT_PROTOCOL_UNKNOWN'))
  it('rejects a completion without all 24 samples', () => expect(() => decodePerformanceExperiment(report())).toThrow('EXPERIMENT_PROTOCOL_UNKNOWN'))
})
