import { useEffect, useState } from 'react'

/**
 * Returns `value` only after it has remained unchanged for `delayMs`.
 * Used by the editor → session save loop (800ms) to coalesce rapid
 * keystrokes into one PUT request.
 */
export function useDebounce<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(t)
  }, [value, delayMs])
  return debounced
}