import { describe, expect, it } from 'vitest'
import { decodePlanSqlMapping, sqlSha256 } from '../planSqlMapping'

describe('plan mapping response boundary', () => {
  it('checks exact UTF-8 submitted SQL, execution, dialect and capture key', async () => {
    expect(await sqlSha256('SELECT 1')).toBe('e004ebd5b5532a4b85984a62f8ad48a81aa3460c1ca07701f386135d72cdecf5')
    const hash = await sqlSha256("SELECT '中文'")
    const scope = { executionId: 'exec-1', dialect: 'mysql', hash }
    const value = { version: 1, executionId: 'exec-1', dialect: 'mysql', submittedSqlSha256: hash,
      captureKey: `exec-1:mysql:${hash}`, edges: [], degraded: false, errorReason: null, issues: [] }
    expect(decodePlanSqlMapping(value, scope)).toEqual(value)
    for (const change of [{ executionId: 'other' }, { dialect: 'postgresql' }, { submittedSqlSha256: 'other' }, { captureKey: 'other' }])
      expect(() => decodePlanSqlMapping({ ...value, ...change }, scope)).toThrow('MAPPING_SCOPE_MISMATCH')
    for (const change of [{ version: 2 }, { edges: null }, { edges: [null] }, { issues: [{}] }])
      expect(() => decodePlanSqlMapping({ ...value, ...change }, scope)).toThrow('MAPPING_RESPONSE_INVALID')
  })
})
