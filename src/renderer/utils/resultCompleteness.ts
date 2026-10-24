import { tr } from '../i18n'
import type { ResultCompleteness } from '../types/execution'

export function normalizeCompleteness(value: unknown): ResultCompleteness {
  if (value && typeof value === 'object') {
    const c = value as ResultCompleteness
    if (c.state === 'UNKNOWN') return { state: 'UNKNOWN', appliedLimit: null,
      errorReason: typeof c.errorReason === 'string' && c.errorReason ? c.errorReason : 'COMPLETENESS_UNVERIFIED' }
    if ((c.state === 'COMPLETE' || c.state === 'TRUNCATED') && Number.isInteger(c.appliedLimit)
      && c.appliedLimit! >= 1 && c.appliedLimit! <= 10000 && !c.errorReason) return c
  }
  return { state: 'UNKNOWN', appliedLimit: null, errorReason: 'COMPLETENESS_UNVERIFIED' }
}

export function completenessText(value: unknown): string {
  const c = normalizeCompleteness(value)
  return c.state === 'COMPLETE' ? tr("Result complete") : c.state === 'TRUNCATED'
    ? tr("Truncated at {0} · total unknown", c.appliedLimit) : tr("Completeness unknown")
}
