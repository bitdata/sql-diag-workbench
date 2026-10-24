// src/renderer/hooks/__tests__/useDebounce.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useDebounce } from '../useDebounce'

describe('useDebounce', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('returns the initial value immediately, before any delay elapses', () => {
    const { result } = renderHook(() => useDebounce('hello', 300))

    expect(result.current).toBe('hello')
  })

  it('updates the debounced value once the delay has elapsed', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce(value, 300),
      { initialProps: { value: 'a' } }
    )

    rerender({ value: 'b' })

    act(() => {
      vi.advanceTimersByTime(300)
    })

    expect(result.current).toBe('b')
  })

  it('does not update before the delay has fully elapsed', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce(value, 300),
      { initialProps: { value: 'a' } }
    )

    rerender({ value: 'b' })

    act(() => {
      vi.advanceTimersByTime(299)
    })
    expect(result.current).toBe('a')

    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(result.current).toBe('b')
  })

  it('coalesces rapid changes — only the latest value is emitted', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce(value, 300),
      { initialProps: { value: 'a' } }
    )

    // Rapid keystrokes, each of which resets the pending timer.
    rerender({ value: 'ab' })
    act(() => {
      vi.advanceTimersByTime(100)
    })
    rerender({ value: 'abc' })
    act(() => {
      vi.advanceTimersByTime(100)
    })
    rerender({ value: 'abcd' })

    // Nothing has settled for a full 300ms yet.
    expect(result.current).toBe('a')

    act(() => {
      vi.advanceTimersByTime(300)
    })
    expect(result.current).toBe('abcd')
  })

  it('resets the pending timer when delayMs changes', () => {
    const { result, rerender } = renderHook(
      ({ value, delay }) => useDebounce(value, delay),
      { initialProps: { value: 'a', delay: 300 } }
    )

    rerender({ value: 'b', delay: 300 })
    act(() => {
      vi.advanceTimersByTime(200)
    })
    expect(result.current).toBe('a')

    // Changing the delay reschedules the timer from the current time.
    rerender({ value: 'b', delay: 500 })
    act(() => {
      vi.advanceTimersByTime(400)
    })
    // Only 400ms since the reschedule, not the full 500ms.
    expect(result.current).toBe('a')

    act(() => {
      vi.advanceTimersByTime(100)
    })
    expect(result.current).toBe('b')
  })

  it('handles a delay of 0', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce(value, 0),
      { initialProps: { value: 'a' } }
    )

    rerender({ value: 'b' })

    act(() => {
      vi.advanceTimersByTime(0)
    })

    expect(result.current).toBe('b')
  })

  it('handles null values', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce<string | null>(value, 300),
      { initialProps: { value: null as string | null } }
    )

    expect(result.current).toBeNull()

    rerender({ value: 'set' })
    act(() => {
      vi.advanceTimersByTime(300)
    })
    expect(result.current).toBe('set')

    rerender({ value: null })
    act(() => {
      vi.advanceTimersByTime(300)
    })
    expect(result.current).toBeNull()
  })

  it('handles undefined values', () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce<string | undefined>(value, 300),
      { initialProps: { value: undefined as string | undefined } }
    )

    expect(result.current).toBeUndefined()

    rerender({ value: 'defined' })
    act(() => {
      vi.advanceTimersByTime(300)
    })
    expect(result.current).toBe('defined')
  })

  it('debounces object values by reference', () => {
    const first = { id: 1 }
    const second = { id: 2 }
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce(value, 300),
      { initialProps: { value: first } }
    )

    expect(result.current).toBe(first)

    rerender({ value: second })
    act(() => {
      vi.advanceTimersByTime(300)
    })

    expect(result.current).toBe(second)
  })

  it('clears the pending timer on unmount (no update after unmount)', () => {
    const { result, rerender, unmount } = renderHook(
      ({ value }) => useDebounce(value, 300),
      { initialProps: { value: 'a' } }
    )

    rerender({ value: 'b' })
    unmount()

    // Advancing the timer after unmount must not throw or schedule updates.
    expect(() => {
      act(() => {
        vi.advanceTimersByTime(300)
      })
    }).not.toThrow()

    // Last value observed before unmount (the debounce never fired).
    expect(result.current).toBe('a')
  })
})
