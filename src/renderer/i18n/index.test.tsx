import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { memo, useState } from 'react'
import SettingsPanel from '../components/Settings/SettingsPanel'
import zh from './zh-CN.json'
import { getLocale, getLocalePreferenceError, LOCALE_STORAGE_KEY, setLocale, tr, useLocale } from './index'

beforeEach(() => {
  const values = new Map<string, string>()
  vi.stubGlobal('localStorage', { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value) } })
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); setLocale('en'); vi.unstubAllGlobals() })

describe('language preferences', () => {
  it('updates an open settings portal while preserving draft values', () => {
    render(<SettingsPanel settings={{ dialect: 'mysql', rowLimit: 500, queryTimeout: 15, autoCleanupDays: 7, theme: 'dark' }} onChange={() => {}} onClose={() => {}} />)
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Row limit' }), { target: { value: '42' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'Language' }), { target: { value: 'zh-CN' } })
    expect(document.documentElement.lang).toBe('zh-CN')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('zh-CN')
    expect(screen.getByRole('heading', { name: '设置' })).toBeInTheDocument()
    expect(screen.getByRole('spinbutton', { name: tr('Row limit') })).toHaveValue(42)
    fireEvent.change(screen.getByRole('combobox', { name: '语言' }), { target: { value: 'en' } })
    expect(screen.getByRole('spinbutton', { name: 'Row limit' })).toHaveValue(42)
  })

  it('refreshes memoized consumers without remounting or translating parameters', () => {
    const Probe = memo(() => {
      useLocale()
      const [sql, update] = useState('SELECT 来源 FROM EMPLOYEES')
      return <><label>{tr('Settings')}<input value={sql} onChange={e => update(e.target.value)} /></label><p>{tr('User edit: {0}...', sql)}</p></>
    })
    render(<Probe />)
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'SELECT 自定义 FROM EMPLOYEES' } })
    act(() => setLocale('zh-CN'))
    expect(screen.getByRole('textbox')).toBe(input)
    expect(input).toHaveValue('SELECT 自定义 FROM EMPLOYEES')
    expect(screen.getByText(tr('User edit: {0}...', 'SELECT 自定义 FROM EMPLOYEES'))).toBeInTheDocument()
    expect(tr('Table employees.EMPLOYEES does not exist')).toBe('Table employees.EMPLOYEES does not exist')
  })

  it('exposes persistence errors even when selecting the current locale', () => {
    function Probe() { useLocale(); return <p>{getLocalePreferenceError()}</p> }
    render(<Probe />)
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    act(() => setLocale('en'))
    expect(screen.getByText(/preference could not be saved/)).toBeInTheDocument()
    expect(getLocale()).toBe('en')
  })

  it('accepts cross-window changes and clearing the preference', () => {
    window.dispatchEvent(new StorageEvent('storage', { key: LOCALE_STORAGE_KEY, newValue: 'zh-CN' }))
    expect(getLocale()).toBe('zh-CN')
    window.dispatchEvent(new StorageEvent('storage', { key: null }))
    expect(getLocale()).toBe('en')
  })

  it('preserves every interpolation placeholder across the catalog', () => {
    const slots = (s: string) => [...s.matchAll(/\{\d+\}/g)].map(m => m[0]).sort()
    for (const [key, value] of Object.entries(zh)) expect(slots(value), key).toEqual(slots(key))
  })
})
