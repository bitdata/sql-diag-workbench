import { useSyncExternalStore } from 'react'
import zh from './zh-CN.json'

export type Locale = 'en' | 'zh-CN'
export const LOCALE_STORAGE_KEY = 'sql-workbench.locale'
const catalog: Record<string, string> = zh
const listeners = new Set<() => void>()
let preferenceError: string | null = null
let locale: Locale = 'en'
let revision = 0
try {
  if (globalThis.localStorage?.getItem(LOCALE_STORAGE_KEY) === 'zh-CN') locale = 'zh-CN'
} catch {
  preferenceError = 'Language preference could not be loaded. English is used for this session.'
}
function publish() {
  revision++
  if (typeof document !== 'undefined') document.documentElement.lang = locale
  listeners.forEach(listener => listener())
}
export function getLocale(): Locale { return locale }
export function getLocalePreferenceError(): string | null { return preferenceError }
export function setLocale(next: Locale) {
  locale = next
  preferenceError = null
  try { localStorage.setItem(LOCALE_STORAGE_KEY, next) }
  catch { preferenceError = 'Language changed for this session, but the preference could not be saved.' }
  publish()
}
function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}
/** Subscribe without remounting editors, dialogs, or query state. */
export function useLocale(): Locale {
  useSyncExternalStore(subscribe, () => revision, () => 0)
  return locale
}
/** Only application-owned copy is passed here. Parameters are never translated. */
export function tr(message: string, ...parameters: unknown[]): string {
  const template = locale === 'zh-CN' ? catalog[message] ?? message : message
  return template.replace(/\{(\d+)\}/g, (match, index: string) =>
    Number(index) < parameters.length ? String(parameters[Number(index)]) : match)
}
if (typeof window !== 'undefined') {
  window.addEventListener('storage', event => {
    if (event.key !== LOCALE_STORAGE_KEY && event.key !== null) return
    locale = event.newValue === 'zh-CN' ? 'zh-CN' : 'en'
    publish()
  })
  document.documentElement.lang = locale
}
