import type { KeyValue } from '../types/diagnostics'

const identifier = /^[A-Za-z_][A-Za-z0-9_]{0,62}$/
export function sourceSql(dialect: string, schema: string, relation: string): string {
  if (dialect !== 'postgresql' && dialect !== 'mysql') throw new Error('SOURCE_DIALECT_UNSUPPORTED')
  if (!identifier.test(relation) || schema !== '' && !identifier.test(schema)) throw new Error('SOURCE_IDENTIFIER_INVALID')
  const quote = dialect === 'mysql' ? '`' : '"'
  return 'SELECT * FROM ' + [schema, relation].filter(Boolean).map(s => quote + s + quote).join('.')
}
export function sourceKeys(keys: KeyValue[], columns: string[]): KeyValue[] {
  if (!keys.length || keys.length !== columns.length || columns.some(c => !identifier.test(c))) throw new Error('SOURCE_KEY_INVALID')
  return keys.map((key, i) => ({ ...key, column: columns[i] }))
}
