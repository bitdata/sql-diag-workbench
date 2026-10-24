import { describe, it, expect } from 'vitest'
import { sourceSql, sourceKeys } from '../sourceVerification'
describe('declared source SQL', () => {
  it('quotes exact-case schema and relation separately for the actual connection dialect', () => {
    expect(sourceSql('postgresql', 'public', 'Sales')).toBe('SELECT * FROM "public"."Sales"')
    expect(sourceSql('mysql', 'm1_support', 'order')).toBe('SELECT * FROM `m1_support`.`order`')
  })
  it.each(['sales WHERE 1=1', 'a.b', 'x;DROP TABLE y', 'x--', 'x/*', '"x"', '`x`', '', 'x'.repeat(64)])('rejects SQL and invalid names: %s', value => {
    expect(() => sourceSql('postgresql', '', value)).toThrow('SOURCE_IDENTIFIER_INVALID')
  })
  it('does not fall back for an unsupported connection dialect', () => expect(() => sourceSql('oracle', '', 'x')).toThrow('SOURCE_DIALECT_UNSUPPORTED'))
  it('maps source columns while retaining types, NULL and literal values as parameters', () => {
    const keys = [{ column: 'output', type: 'NULL' as const, value: null }, { column: 'invoice', type: 'TEXT' as const, value: "x' OR 1=1" }]
    expect(sourceKeys(keys, ['source_col', 'parent'])).toEqual([{ ...keys[0], column: 'source_col' }, { ...keys[1], column: 'parent' }])
    expect(keys[0].column).toBe('output')
    expect(() => sourceKeys(keys, ['x'])).toThrow('SOURCE_KEY_INVALID')
    expect(() => sourceKeys(keys, ['a.b', 'parent'])).toThrow('SOURCE_KEY_INVALID')
  })
})
