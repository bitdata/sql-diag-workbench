import { describe, expect, it } from 'vitest'
import { completenessText, normalizeCompleteness } from '../resultCompleteness'

describe('result completeness', () => {
  it('never infers completeness from old or malformed payloads', () => {
    for (const value of [null, undefined, {}, { state: 'COMPLETE' }, { state: 'COMPLETE', appliedLimit: 0 },
      { state: 'COMPLETE', appliedLimit: 10001 }, { state: 'COMPLETE', appliedLimit: 2, errorReason: 'broken' }]) {
      expect(normalizeCompleteness(value)).toEqual({ state: 'UNKNOWN', appliedLimit: null, errorReason: 'COMPLETENESS_UNVERIFIED' })
    }
  })
  it('separates returned sample limits from total size', () => {
    expect(completenessText({ state: 'TRUNCATED', appliedLimit: 2 })).toBe('Truncated at 2 · total unknown')
    expect(completenessText({ state: 'COMPLETE', appliedLimit: 2 })).toBe('Result complete')
    expect(normalizeCompleteness({ state: 'UNKNOWN', errorReason: 'HISTORY_COMPLETENESS_INVALID' }).errorReason).toBe('HISTORY_COMPLETENESS_INVALID')
  })
})
