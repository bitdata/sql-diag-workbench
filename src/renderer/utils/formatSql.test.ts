import { expect, it, vi } from 'vitest'
import { format } from 'sql-formatter'
import { formatSql as formatSqlOutcome } from './formatSql'

for (const sql of [
  'SELECT CAST(1 AS NUMBER(10,2)) n FROM dual', "SELECT CAST(N'中文' AS NVARCHAR2(8)) n FROM dual",
  'WITH "q" AS (SELECT 1 n FROM dual) SELECT * FROM "q"', 'SELECT "n", "N" FROM "q"',
  'SELECT 1 FROM dual MINUS SELECT 2 FROM dual', 'SELECT ROW_NUMBER() OVER (ORDER BY 1) n FROM dual',
  "SELECT q'[text]' FROM dual", '-- comment\r\nSELECT 1 FROM dual',
]) it(`preserves Oracle original text with an observable restriction: ${sql}`, () => {
  const formatter = vi.fn(format)
  expect(formatSqlOutcome(sql, { dialect: 'oracle' }, formatter)).toEqual({ sql, formatted: false, degraded: true, errorReason: 'ORACLE_FORMAT_UNSUPPORTED' })
  expect(formatter).not.toHaveBeenCalled()
})
for (const dialect of ['postgresql','mysql','sqlserver'] as const) it(`preserves existing successful formatting output for ${dialect}`, () => {
  const sql = 'select a,b from t where a=1 order by b'
  expect(formatSqlOutcome(sql, { dialect })).toEqual({ sql: format(sql, { language: dialect === 'mysql' ? 'mysql' : 'postgresql', tabWidth: 2, keywordCase: 'upper', linesBetweenQueries: 2 }), formatted: true, degraded: false, errorReason: null })
})
it('makes an injected formatter failure observable while retaining original text', () => {
  const sql = 'SELECT 1'
  expect(formatSqlOutcome(sql, { dialect: 'postgresql' }, () => { throw new Error('synthetic formatter failure') })).toEqual({ sql, formatted: false, degraded: true, errorReason: 'SQL_FORMAT_FAILED' })
})
