import { tr } from '../i18n'
import type { Metric, NodeMetrics, Evaluation } from '../types/execution'
export function metricText(metric?: Metric | null): string {
  if (!metric || metric.state === 'UNKNOWN') return tr("Unknown")
  if (metric.state === 'NOT_EXECUTED') return tr("Not executed")
  if (metric.state === 'NOT_COLLECTED') return tr("Not collected")
  if (metric.state === 'PARSE_ERROR') return tr("Unavailable: invalid metric")
  if (metric.state !== 'AVAILABLE') return tr("Unknown: unsupported metric state")
  if (typeof metric.value !== 'string' || !/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(metric.value)) return tr("Unavailable: invalid metric")
  return metric.value
}
export function nodeMetric(metrics: NodeMetrics | null | undefined, key: keyof Pick<NodeMetrics, 'estimatedRows' | 'actualRows' | 'actualDurationMs' | 'loops' | 'startupCost' | 'totalCost'>): Metric | undefined {
  return metrics?.version === 1 ? metrics[key] : undefined
}
export function evaluationText(e?: Evaluation | null): string {
  if (!e || e.version !== 1 || !Array.isArray(e.rules)) return tr("Evaluation coverage unknown")
  return tr('{0} evaluated · {1} not applicable · {2} not evaluated · {3} failed · Source: {4}', ...['EVALUATED', 'NOT_APPLICABLE', 'SKIPPED', 'FAILED'].map(status => e.rules.filter(r => r.status === status).length), e.sourceState)
}

/** Compare nonnegative exact decimal strings without Number rounding. Unknowns sort last. */
export function compareMetricsDescending(a?: Metric | null, b?: Metric | null): number {
  const valid = (m?: Metric | null) => m?.state === 'AVAILABLE' && typeof m.value === 'string' && /^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(m.value) ? m.value : null
  const av = valid(a), bv = valid(b)
  if (av === null || bv === null) return av === bv ? 0 : av === null ? 1 : -1
  const [ai, af = ''] = av.split('.'), [bi, bf = ''] = bv.split('.')
  if (ai.length !== bi.length) return bi.length - ai.length
  if (ai !== bi) return ai < bi ? 1 : -1
  const n = Math.max(af.length, bf.length), ap = af.padEnd(n, '0'), bp = bf.padEnd(n, '0')
  return ap === bp ? 0 : ap < bp ? 1 : -1
}
