import type { ExecutionResult, Metric, NativeMetric } from '../types/execution'

const commonUnits: readonly Metric['unit'][] = ['ROWS', 'MILLISECONDS', 'LOOPS', 'COST']
const commonBases: readonly Metric['basis'][] = ['PER_INVOCATION', 'PER_LOOP_AVERAGE', 'TOTAL', 'NODE_ESTIMATE', 'QUERY_ESTIMATE', 'PREFIX_ESTIMATE', 'UNKNOWN']
const nativeUnits: readonly NativeMetric['unit'][] = [...commonUnits, 'READS', 'EXECUTIONS', 'OPTIMIZER_COST', 'MICROSECONDS']
const nativeBases: readonly NativeMetric['basis'][] = [...commonBases, 'REPORTED_THREAD_TOTAL', 'REPORTED_QUERY_TOTAL', 'NATIVE_SUBTREE', 'REPORTED_LAST_EXECUTION', 'REPORTED_LAST_EXECUTION_INCLUSIVE']
type Obj = Record<string, unknown>
function fail(): never { throw new Error('ORACLE_PLAN_INVALID_RESPONSE') }
function obj(v: unknown): Obj { if (!v || typeof v !== 'object' || Array.isArray(v)) return fail(); return v as Obj }
function text(v: unknown, max = 1024): v is string { return typeof v === 'string' && v.length <= max && new TextEncoder().encode(v).length <= max * 4 }
function optionalText(v: unknown, max = 1024) { return v == null || text(v, max) }
function array(v: unknown, max: number): unknown[] { if (!Array.isArray(v) || v.length > max) return fail(); return v }
function metric(v: unknown, native: boolean) {
  const m = obj(v), units = native ? nativeUnits : commonUnits, bases = native ? nativeBases : commonBases
  if (!['AVAILABLE', 'NOT_COLLECTED', 'NOT_EXECUTED', 'UNKNOWN', 'PARSE_ERROR'].includes(String(m.state))
    || !units.some(u => u === m.unit) || !bases.some(b => b === m.basis) || !optionalText(m.sourceField) || !optionalText(m.errorReason)) fail()
  if (m.state === 'AVAILABLE') {
    if (!text(m.value, 2049) || !/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(m.value) || m.errorReason != null) fail()
  } else if (m.value != null || !text(m.errorReason) || !m.errorReason) fail()
}
function metrics(v: unknown) { const values = Object.values(obj(v)); if (values.length > 32) fail(); for (const m of values) metric(m, true) }
export function validateOraclePlan(plan: unknown, captureValue: unknown) {
  const capture = obj(captureValue), details = obj(capture.details)
  if (capture.dialect !== 'oracle' || details.channel !== 'ORACLE_PLAN_ROWS' || details.version !== 1
    || !text(details.rawPlan, 512 * 1024) || new TextEncoder().encode(details.rawPlan).length > 512 * 1024) fail()
  metric(details.planningTimeMs, false); metric(details.executionTimeMs, false)
  const sourcePaths = new Set<string>(), todo: { value: unknown; depth: number }[] = [{ value: plan, depth: 0 }]
  while (todo.length) {
    const { value, depth } = todo.pop()!, node = obj(value), m = obj(node.metrics)
    if (depth > 128 || sourcePaths.size >= 4096 || !text(node.operation) || !text(m.sourcePath) || sourcePaths.has(m.sourcePath) || !/^oracle\/id\/(?:0|[1-9]\d{0,9})$/.test(m.sourcePath) || m.version !== 1 || m.origin !== 'DATABASE_NODE') fail()
    sourcePaths.add(m.sourcePath)
    for (const key of ['estimatedRows', 'actualRows', 'actualDurationMs', 'loops', 'startupCost', 'totalCost']) metric(m[key], false)
    for (const child of array(node.children, 4096)) todo.push({ value: child, depth: depth + 1 })
  }
  const evidencePaths = new Set<string>()
  for (const value of array(details.nodes, 4096)) {
    const n = obj(value)
    if (!text(n.sourcePath) || !sourcePaths.has(n.sourcePath) || evidencePaths.has(n.sourcePath) || !text(n.raw, 512 * 1024)) fail()
    evidencePaths.add(n.sourcePath); metric(n.firstRowMs, false)
  }
  if (evidencePaths.size !== sourcePaths.size) fail()
  const native = obj(details.nativeEvidence), nativePaths = new Set<string>()
  if (native.version !== 1) fail()
  for (const value of array(native.nodes, 4096)) {
    const n = obj(value)
    if (!text(n.sourcePath) || !sourcePaths.has(n.sourcePath) || nativePaths.has(n.sourcePath) || !text(n.physicalOp) || !optionalText(n.logicalOp)) fail()
    nativePaths.add(n.sourcePath)
    const attributes = obj(n.attributes); if (Object.keys(attributes).length > 32) fail()
    for (const [key, val] of Object.entries(attributes)) if (!text(key, 128) || !text(val)) fail()
    metrics(n.metrics)
    const threads = array(n.threads, 32), paths = new Set<string>()
    for (const value of threads) { const t = obj(value); if (!text(t.sourcePath) || paths.has(t.sourcePath) || !text(t.threadId)) fail(); paths.add(t.sourcePath); metrics(t.metrics) }
  }
  if (nativePaths.size !== sourcePaths.size) fail()
  for (const value of array(native.queryStats, 32)) { const q = obj(value); if (!text(q.sourcePath)) fail(); metrics(q.metrics) }
  return plan
}

/** Validate native evidence on live polling as well as archived receipts. */
export function decodeOracleExecution(raw: unknown): ExecutionResult {
  const result = obj(raw)
  if (result.planCapture != null) {
    const capture = obj(result.planCapture)
    const details = capture.details == null ? null : obj(capture.details)
    if (details?.channel === 'ORACLE_PLAN_ROWS' || (capture.dialect === 'oracle' && result.plan != null)) {
      if (result.plan == null && capture.parseStatus === 'FAILED') {
        // Structural rejection is an observable result, with no fabricated native tree.
        if (capture.dialect !== 'oracle' || details?.channel !== 'ORACLE_PLAN_ROWS' || details.version !== 1
          || !['COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED'].includes(String(result.status)) || result.degraded !== true
          || !text(result.errorReason) || !result.errorReason || details.errorReason !== result.errorReason
          || details.nativeEvidence != null || !array(capture.issues, 4096).some(v => obj(v).code === result.errorReason)
          || (details.rawPlan != null && (!text(details.rawPlan, 512 * 1024) || new TextEncoder().encode(details.rawPlan).length > 512 * 1024))) fail()
        for (const value of array(details.nodes, 4096)) {
          const node = obj(value); if (!text(node.sourcePath) || !text(node.raw, 512 * 1024)) fail()
        }
      } else validateOraclePlan(result.plan, capture)
    }
  }
  return raw as ExecutionResult
}
