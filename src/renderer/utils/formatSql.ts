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

/**
 * Format SQL using sql-formatter. For large SQL (>=10KB), caller should
 * invoke in a Web Worker to avoid blocking the UI thread.
 */
export function formatSql(sql: string, options: FormatOptions = DEFAULT_FORMAT_OPTIONS): string {
  try {
    return format(sql, {
      language: options.dialect === 'mysql' ? 'mysql' : 'postgresql',
      tabWidth: options.tabWidth ?? 2,
      keywordCase: options.keywordCase ?? 'upper',
      linesBetweenQueries: 2,
    })
  } catch {
    return sql
  }
}
