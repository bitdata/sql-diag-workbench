import { format } from 'sql-formatter'
import type { SqlDialect } from '../types/session'

export interface FormatOptions {
  dialect: SqlDialect
  tabWidth?: number
  keywordCase?: 'upper' | 'lower' | 'preserve'
}

const DEFAULT_FORMAT_OPTIONS: FormatOptions = {
  dialect: 'postgresql',
  tabWidth: 2,
  keywordCase: 'upper',
}

export interface FormatOutcome { sql: string; formatted: boolean; degraded: boolean; errorReason: string | null }

/** Unsupported or failed formatting retains the original bytes and exposes a reason. */
export function formatSql(sql: string, options: FormatOptions = DEFAULT_FORMAT_OPTIONS, formatter: typeof format = format): FormatOutcome {
  if (options.dialect === 'oracle') return { sql, formatted: false, degraded: true, errorReason: 'ORACLE_FORMAT_UNSUPPORTED' }
  try {
    return { sql: formatter(sql, { language: options.dialect === 'mysql' ? 'mysql' : 'postgresql', tabWidth: options.tabWidth ?? 2,
      keywordCase: options.keywordCase ?? 'upper', linesBetweenQueries: 2 }), formatted: true, degraded: false, errorReason: null }
  } catch { return { sql, formatted: false, degraded: true, errorReason: 'SQL_FORMAT_FAILED' } }
}
