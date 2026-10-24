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
