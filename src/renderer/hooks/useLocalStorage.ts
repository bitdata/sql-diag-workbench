import { useState, useCallback, useEffect, useRef } from 'react'

/**
 * Persistent state backed by {@code window.localStorage}. Returns a
 * [value, setValue] tuple with the same shape as {@code useState}, but
 * writes through to localStorage on every update and reads the initial
 * value on mount and whenever the {@code key} changes.
 *
 * <p>Storage errors (quota exceeded, parse failure, disabled storage)
 * are swallowed and logged; the value falls back to {@code initialValue}.
 *
 * <p>The hook is SSR-safe: when {@code window.localStorage} is undefined
 * (e.g. in a non-browser environment) the hook behaves like {@code useState}.
 */
export function useLocalStorage<T>(
  key: string,
  initialValue: T
): [T, (next: T | ((prev: T) => T)) => void] {
  const keyRef = useRef(key)

  const [value, setValue] = useState<T>(() => {
    if (typeof window === 'undefined') return initialValue
    try {
      const raw = window.localStorage.getItem(key)
      if (raw === null) return initialValue
      return JSON.parse(raw) as T
    } catch {
      return initialValue
    }
  })

  // Reload value from localStorage when key changes
  useEffect(() => {
    if (keyRef.current === key) return
    keyRef.current = key
    if (typeof window === 'undefined') return
    try {
      const raw = window.localStorage.getItem(key)
      setValue(raw === null ? initialValue : JSON.parse(raw) as T)
    } catch {
      setValue(initialValue)
    }
  }, [key, initialValue])

  // Write to localStorage whenever value changes (pure — no side effects in
  // the state updater). Skip the initial mount to avoid overwriting stored
  // values with the initializer's read result.
  const isInitialMount = useRef(true)
  useEffect(() => {
    if (isInitialMount.current) {
      isInitialMount.current = false
      return
    }
    if (typeof window !== 'undefined') {
      try {
        window.localStorage.setItem(keyRef.current, JSON.stringify(value))
      } catch {
        // Ignore quota / serialization errors
      }
    }
  }, [value])

  const set = useCallback(
    (next: T | ((prev: T) => T)) => {
      setValue(next)
    },
    []
  )

  return [value, set]
}
