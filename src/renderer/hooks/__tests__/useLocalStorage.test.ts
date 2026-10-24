// src/renderer/hooks/__tests__/useLocalStorage.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useLocalStorage } from '../useLocalStorage'

/**
 * In-memory Storage stub. The jsdom env in this project does not expose a
 * working `localStorage.clear`, so we replace `window.localStorage` with a
 * fresh stub per test (same approach as useTabs.test.ts). This provides the
 * full Storage surface the hook relies on and guarantees zero state leakage
 * between cases.
 */
function makeMemoryStorage(): Storage {
  let store: Record<string, string> = {}
  return {
    getItem: (k: string) => (k in store ? store[k] : null),
    setItem: (k: string, v: string) => {
      store[k] = String(v)
    },
    removeItem: (k: string) => {
      delete store[k]
    },
    clear: () => {
      store = {}
    },
    key: (i: number) => Object.keys(store)[i] ?? null,
    get length() {
      return Object.keys(store).length
    },
  }
}

describe('useLocalStorage', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'localStorage', {
      value: makeMemoryStorage(),
      configurable: true,
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('returns initialValue when the key is absent', () => {
    const { result } = renderHook(() => useLocalStorage('missing', 'default'))

    expect(result.current[0]).toBe('default')
  })

  it('reads an existing value from localStorage on mount', () => {
    window.localStorage.setItem('greeting', JSON.stringify('hello'))

    const { result } = renderHook(() => useLocalStorage('greeting', 'default'))

    expect(result.current[0]).toBe('hello')
  })

  it('updates state and writes through to localStorage (direct value)', () => {
    const { result } = renderHook(() => useLocalStorage('k', 'a'))

    act(() => {
      result.current[1]('b')
    })

    expect(result.current[0]).toBe('b')
    expect(JSON.parse(window.localStorage.getItem('k') as string)).toBe('b')
  })

  it('supports functional updates based on the previous value', () => {
    const { result } = renderHook(() => useLocalStorage('count', 1))

    act(() => {
      result.current[1]((prev) => prev + 1)
    })
    act(() => {
      result.current[1]((prev) => prev + 1)
    })

    expect(result.current[0]).toBe(3)
    expect(JSON.parse(window.localStorage.getItem('count') as string)).toBe(3)
  })

  it('persists object values via a JSON round-trip', () => {
    const initial = { theme: 'dark', fontSize: 14 }
    const { result } = renderHook(() => useLocalStorage('prefs', initial))

    expect(result.current[0]).toEqual(initial)

    const next = { theme: 'light', fontSize: 16 }
    act(() => {
      result.current[1](next)
    })

    expect(result.current[0]).toEqual(next)

    // A fresh mount reads back the persisted object.
    const { result: remounted } = renderHook(() => useLocalStorage('prefs', initial))
    expect(remounted.current[0]).toEqual(next)
  })

  it('falls back to initialValue when stored JSON is corrupted', () => {
    window.localStorage.setItem('broken', '{this is not json')

    const { result } = renderHook(() => useLocalStorage('broken', 'fallback'))

    expect(result.current[0]).toBe('fallback')
  })

  it('falls back to initialValue when getItem throws (disabled storage)', () => {
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError: access denied')
    })

    const { result } = renderHook(() => useLocalStorage('k', 'fallback'))

    expect(result.current[0]).toBe('fallback')
  })

  it('still updates state when setItem throws (quota exceeded)', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })

    const { result } = renderHook(() => useLocalStorage('k', 'a'))

    // The storage error is swallowed — the update must not throw.
    act(() => {
      result.current[1]('b')
    })

    expect(result.current[0]).toBe('b')
  })

  it('distinguishes a stored null from an absent key', () => {
    // Absent key → initialValue.
    const { result } = renderHook(() => useLocalStorage<string | null>('k', 'default'))
    expect(result.current[0]).toBe('default')

    // Store null explicitly.
    act(() => {
      result.current[1](null)
    })
    expect(result.current[0]).toBeNull()

    // A fresh mount reads back the stored null (not the initialValue).
    const { result: remounted } = renderHook(() => useLocalStorage<string | null>('k', 'default'))
    expect(remounted.current[0]).toBeNull()
  })

  it('keeps a stable setter identity across re-renders for the same key', () => {
    const { result, rerender } = renderHook(() => useLocalStorage('k', 'a'))
    const firstSetter = result.current[1]

    rerender()

    expect(result.current[1]).toBe(firstSetter)
  })

  it('changing the key reloads value from localStorage', () => {
    window.localStorage.setItem('a', JSON.stringify('from-a'))
    window.localStorage.setItem('b', JSON.stringify('from-b'))

    const { result, rerender } = renderHook(
      ({ key }) => useLocalStorage(key, 'default'),
      { initialProps: { key: 'a' } }
    )

    expect(result.current[0]).toBe('from-a')

    // Switching the key reloads the value from the new key's storage
    rerender({ key: 'b' })
    expect(result.current[0]).toBe('from-b')

    // Setter writes to the new key
    act(() => {
      result.current[1]('written')
    })
    expect(JSON.parse(window.localStorage.getItem('b') as string)).toBe('written')
  })
})
