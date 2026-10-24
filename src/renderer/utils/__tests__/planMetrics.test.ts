import { describe, expect, it } from 'vitest'
import { metricText, nodeMetric, evaluationText } from '../planMetrics'
import type { Metric, NodeMetrics } from '../../types/execution'

describe('plan evidence consumption', () => {
  const metric = (value: string): Metric => ({ state: 'AVAILABLE', value, unit: 'ROWS', basis: 'PER_INVOCATION', sourceField: 'Plan Rows', errorReason: null })
  it('preserves exact decimal strings and genuine zero', () => {
    for (const value of ['0', '9007199254740993', '0.123456789012345678901']) expect(metricText(metric(value))).toBe(value)
    for (const value of ['NaN', '-1', '1e3', '']) expect(metricText(metric(value))).toContain('invalid metric')
  })
  it('does not infer metrics or coverage from legacy numeric payloads', () => {
    expect(metricText(nodeMetric(null, 'actualRows'))).toBe('Unknown')
    expect(nodeMetric({ version: 2 } as unknown as NodeMetrics, 'actualRows')).toBeUndefined()
    expect(evaluationText()).toBe('Evaluation coverage unknown')
    expect(metricText({ ...metric('0'), state: 'NOT_EXECUTED' })).toBe('Not executed')
  })
})
