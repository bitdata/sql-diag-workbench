import { act, renderHook } from '@testing-library/react'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createTabIntent, useTabs } from '../useTabs'

const base = 'http://localhost:8080'
const session = (id = 'target', sqlText = 'SELECT 2') => ({ id, name: 'Saved target', sqlText,
  dialect: 'postgresql', connectionId: null, fragmentTreeJson: null, updatedAt: '2026-10-12T00:00:00Z' })
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { resolve, promise }
}
describe('session creation intent recovery', () => {
  beforeEach(() => {
    const values = new Map<string, string>()
    Object.defineProperty(window, 'localStorage', { configurable: true, value: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key), clear: () => values.clear(),
    } })
  })
  afterEach(() => vi.restoreAllMocks())

  it('retains boot payload after response loss and setting changes; next explicit operation gets a new key', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce(response(session('boot', ''))).mockResolvedValueOnce(response(session('next', '')))
    const { result, rerender } = renderHook(({ dialect }) => useTabs(base, { dialect }), { initialProps: { dialect: 'postgresql' } })
    await act(async () => { await result.current.createTab() })
    const first = fetch.mock.calls[0][1]!.body
    rerender({ dialect: 'mysql' })
    await act(async () => { await result.current.retryBoot() })
    expect(fetch.mock.calls[1][1]!.body).toBe(first)
    expect(JSON.parse(String(first))).toMatchObject({ name: 'Untitled 1', dialect: 'postgresql' })
    expect(result.current.tabs).toHaveLength(1)
    await act(async () => { await result.current.createTab() })
    expect(JSON.parse(String(fetch.mock.calls[2][1]!.body)).creationRequestId)
      .not.toBe(JSON.parse(String(first)).creationRequestId)
  })
  it('history retry uses frozen intent and the current server SQL and name', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce(response(session('history', 'SELECT 99')))
    const { result, rerender } = renderHook(({ dialect }) => useTabs(base, { dialect }), { initialProps: { dialect: 'postgresql' } })
    await act(async () => { await result.current.openInNewTab('SELECT 1', { name: 'History SQL' }) })
    rerender({ dialect: 'mysql' })
    await act(async () => { await result.current.retryBoot() })
    expect(fetch.mock.calls[1][1]!.body).toBe(fetch.mock.calls[0][1]!.body)
    expect(result.current.activeWorkspace).toMatchObject({ sql: 'SELECT 99', tabName: 'Saved target' })
  })
  it('attaches a repeated intent only once and preserves an already mounted dirty workspace', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => response(session()))
    const intent = createTabIntent('SELECT 2')
    const { result } = renderHook(() => useTabs(base))
    await act(async () => { await result.current.openInNewTab('SELECT 2', { intent }) })
    act(() => result.current.updateWorkspace('target', { sql: 'SELECT 9' }))
    await act(async () => { await result.current.openInNewTab('SELECT 2', { intent }) })
    expect(result.current.tabs).toHaveLength(1)
    expect(result.current.activeWorkspace?.sql).toBe('SELECT 9')
    expect(fetch.mock.calls[0][1]!.body).toBe(fetch.mock.calls[1][1]!.body)
    expect(JSON.parse(window.localStorage.getItem('tabs.v1')!).tabSessionIds).toEqual(['target'])
  })
  it.each([400, 409, 410])('HTTP %s stops retry and dismissal allows an explicit new operation', async status => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response({ message: 'Cannot replay' }, status))
      .mockResolvedValueOnce(response(session('new', '')))
    const { result } = renderHook(() => useTabs(base))
    await act(async () => { await result.current.createTab() })
    expect(result.current.bootErrorRetryable).toBe(false)
    await act(async () => { await result.current.retryBoot() })
    expect(fetch).toHaveBeenCalledTimes(1)
    act(() => result.current.dismissBootError!())
    expect(result.current.bootError).toBeNull()
    await act(async () => { await result.current.createTab() })
    expect(result.current.tabs).toHaveLength(1)
    expect(fetch.mock.calls[1][1]!.body).not.toBe(fetch.mock.calls[0][1]!.body)
  })
  it('an invalid successful response remains recoverable with the same key', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response({ id: 'incomplete' }))
      .mockResolvedValueOnce(response(session('valid', '')))
    const { result } = renderHook(() => useTabs(base))
    await act(async () => { await result.current.createTab() })
    expect(result.current.bootError).toMatch(/response was invalid/)
    await act(async () => { await result.current.retryBoot() })
    expect(fetch.mock.calls[1][1]!.body).toBe(fetch.mock.calls[0][1]!.body)
    expect(result.current.tabs[0].sessionId).toBe('valid')
  })
  it('a late replay cannot resurrect a target closed while it was opening', async () => {
    const late = deferred<Response>()
    const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(response(session()))
      .mockImplementationOnce(() => late.promise).mockResolvedValueOnce(new Response(null, { status: 204 }))
    const intent = createTabIntent('SELECT 2')
    const onError = vi.fn()
    const { result } = renderHook(() => useTabs(base))
    await act(async () => { await result.current.openInNewTab('SELECT 2', { intent }) })
    let opening!: Promise<string | null>
    act(() => { opening = result.current.openInNewTab('SELECT 2', { intent, onError }) })
    await act(async () => { await result.current.closeTab('target', { force: true }) })
    await act(async () => { late.resolve(response(session())); expect(await opening).toBeNull() })
    expect(result.current.tabs).toHaveLength(0)
    expect(onError).toHaveBeenCalledWith(expect.stringMatching(/closed/), 'CREATION_TARGET_GONE')
    expect(fetch).toHaveBeenCalledTimes(3)
  })
})
