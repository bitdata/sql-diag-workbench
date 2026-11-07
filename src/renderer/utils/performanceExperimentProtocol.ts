import type { ExperimentReport } from '../types/optimization'
type Obj = Record<string, unknown>
const obj = (x: unknown): x is Obj => !!x && typeof x === 'object' && !Array.isArray(x)
const str = (x: unknown): x is string => typeof x === 'string'
const nullable = (x: unknown) => x === null || str(x)
const count = (x: unknown) => str(x) && /^(0|[1-9][0-9]*)$/.test(x)
const one = (x: unknown, values: string[]) => str(x) && values.includes(x)
const fail = (): never => { throw Error('EXPERIMENT_PROTOCOL_UNKNOWN') }
const planMetric = (x: unknown) => {
  if (!obj(x)) fail()
  const metric = x as Obj
  if (!str(metric.state) || !str(metric.unit) || !str(metric.basis) || !str(metric.sourceField) || !nullable(metric.value) || !nullable(metric.errorReason)) fail()
}
const planDetails = (x: unknown) => {
  if (!obj(x)) fail()
  const details = x as Obj
  if (details.version !== 1 || !one(details.channel, ['POSTGRESQL_JSON','MYSQL_TREE']) || !nullable(details.rawPlan) || !nullable(details.database) || !nullable(details.databaseUser) || !obj(details.settings) || !obj(details.planningTimeMs) || !obj(details.executionTimeMs) || !Array.isArray(details.nodes) || !nullable(details.errorReason)) fail()
  const nodes = details.nodes as unknown[]
  planMetric(details.planningTimeMs)
  planMetric(details.executionTimeMs)
  for (const node of nodes) {
    if (!obj(node)) fail()
    const item = node as Obj
    if (!str(item.sourcePath) || !nullable(item.raw) || !obj(item.firstRowMs)) fail()
    planMetric(item.firstRowMs)
  }
}
const planEvidence = (x: unknown) => {
  if (!obj(x)) fail()
  const evidence = x as Obj
  if (!one(evidence.status, ['COMPLETE','PARTIAL','FAILED','SKIPPED']) || !one(evidence.dialect, ['postgresql','mysql']) || !one(evidence.channel, ['POSTGRESQL_JSON','MYSQL_TREE']) || !nullable(evidence.connectionRevision) || !nullable(evidence.submittedSql) || !nullable(evidence.explainSql) || !str(evidence.capturedAt) || !nullable(evidence.databaseProduct) || !nullable(evidence.databaseVersion) || !nullable(evidence.sessionIdentity) || !(evidence.details === null || obj(evidence.details)) || !nullable(evidence.errorReason) || typeof evidence.degraded !== 'boolean') fail()
  if (evidence.details !== null) planDetails(evidence.details)
}
export function decodePerformanceExperiment(value: unknown): ExperimentReport {
  if (!obj(value)) fail(); const v = value as Obj
  if (v.version !== 1 || !str(v.experimentId) || !one(v.status, ['QUEUED','RUNNING','COMPLETED','FAILED','TIMED_OUT','CANCELLED']) || !one(v.phase, ['ADMISSION','CONNECTING','VALIDATING','WARMUP','MEASURED','ACTUAL_PLAN','CLEANUP','PERSISTING','DONE','CLEANUP_UNCONFIRMED']) || !one(v.comparisonState, ['NOT_EVALUATED','IN_PROGRESS','COMPLETE','INCOMPARABLE']) || !nullable(v.errorReason) || typeof v.degraded !== 'boolean' || !Array.isArray(v.samples) || v.samples.length > 24) fail()
  const request=v.request as Obj, protocol=v.protocol as Obj
  if (!obj(v.request) || request.version !== 1 || !obj(request.source)) fail(); const source=request.source as Obj
  if (!['sessionId','sourceRevision','sql','connectionId','connectionRevision'].every(k=>str(source[k])&&!!source[k]) || !one(source.dialect,['postgresql','mysql']) || !str(request.candidateSql) || !(request.candidateId===null||str(request.candidateId)) || !(request.seed===null||count(request.seed)) || !(request.timeoutSeconds===null||Number.isInteger(request.timeoutSeconds)&&Number(request.timeoutSeconds)>=1&&Number(request.timeoutSeconds)<=300)) fail()
  if (!obj(protocol) || protocol.id!=='PAIRED_SELECT_V1' || !count(protocol.seed) || protocol.warmupPairs!==2 || protocol.measuredPairs!==10 || protocol.orderAlgorithm!=='LCG32_FISHER_YATES_V1' || protocol.pgPreparation!=='THRESHOLD_ZERO' || protocol.mysqlPreparation!=='CLIENT_PREPARED_STREAMING') fail()
  const summary=v.summary as Obj, samples=v.samples as unknown[]
  if (!obj(v.summary) || !one(summary.state,['DESCRIPTIVE','NOT_AVAILABLE']) || !Array.isArray(summary.notes)) fail()
  if (!(v.actualPlans === null || v.actualPlans === undefined || obj(v.actualPlans))) fail()
  if (obj(v.actualPlans)) { if (!obj(v.actualPlans.original) || !obj(v.actualPlans.candidate) || typeof v.actualPlans.degraded !== 'boolean' || !nullable(v.actualPlans.errorReason)) fail(); planEvidence(v.actualPlans.original); planEvidence(v.actualPlans.candidate) }
  for (const item of samples) if (!obj(item) || !Number.isInteger(item.pair) || !one(item.phase,['WARMUP','MEASURED']) || !one(item.order,['AB','BA']) || !one(item.side,['A','B']) || !one(item.status,['COMPLETE','FAILED','TIMED_OUT','CANCELLED']) || !count(item.rows) || !count(item.bytes) || !nullable(item.errorReason) || !['durationNs','executeReturnNs','drainNs','closeNs'].every(k=>item[k]===null||count(item[k]))) fail()
  if (v.comparisonState==='COMPLETE') {
    const planStopped = one(v.status,['CANCELLED','TIMED_OUT']) && v.phase==='CLEANUP' && obj(v.actualPlans) && v.actualPlans.degraded===true && v.errorReason===(v.status==='CANCELLED'?'CANCELLED':'DEADLINE_EXCEEDED')
    const finished = v.status==='COMPLETED' && v.phase==='DONE'
    const capturing = v.status==='RUNNING' && v.phase==='ACTUAL_PLAN'
    if ((!finished && !capturing && !planStopped)||samples.length!==24||samples.some((item:unknown)=>!obj(item)||item.status!=='COMPLETE')||summary.state!=='DESCRIPTIVE') fail()
  }
  return value as unknown as ExperimentReport
}
