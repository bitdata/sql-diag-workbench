// src/renderer/hooks/__tests__/useRowTrace.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useRowTrace } from '../useRowTrace'
import { mockFetchResponses, resetMocks } from '../../test/testUtils'

describe('useRowTrace', () => {
  beforeEach(() => {
    resetMocks()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  /** Flush microtasks + advance fake timers by {@code ms}. */
  async function advanceAndFlush(ms: number) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ms)
    })
  }

  // ── helpers ──────────────────────────────────────────────────────

  const KEYS = [{ column: 'order_id', value: 12345 }]
  const FRAG_TREE = '{"root":"f1"}'

  function pollRunning(traceId: string, stepCount = 1) {
    const steps = Array.from({ length: stepCount }, (_, i) => ({
      stepIndex: i,
      fragmentId: `f${i + 1}`,
      fragmentLabel: `frag-${i + 1}`,
      sql: `SELECT 1 FROM frag_${i + 1}`,
      status: 'HAS_ROWS' as const,
      rowCount: 10,
      elapsedMs: 5,
      error: null,
    }))
    return {
      traceId,
      status: 'RUNNING' as const,
      steps,
      eliminationStep: null,
      findings: [],
      totalElapsedMs: stepCount * 5,
      error: null,
      eliminationOutcome: null,
      errorReason: null,
    }
  }

  function pollCompleted(traceId: string) {
    return {
      traceId,
      status: 'COMPLETED' as const,
      steps: [
        {
          stepIndex: 0,
          fragmentId: 'f1',
          fragmentLabel: 'orders',
          sql: 'SELECT EXISTS(...)',
          status: 'HAS_ROWS' as const,
          rowCount: 5,
          elapsedMs: 12,
          error: null,
        },
        {
          stepIndex: 1,
          fragmentId: 'f2',
          fragmentLabel: 'order_items',
          sql: 'SELECT EXISTS(...)',
          status: 'NO_ROWS' as const,
          rowCount: 0,
          elapsedMs: 8,
          error: null,
        },
      ],
      eliminationStep: {
        stepIndex: 1,
        fragmentId: 'f2',
        fragmentLabel: 'order_items',
        sql: 'SELECT EXISTS(...)',
        status: 'NO_ROWS' as const,
        rowCount: 0,
        elapsedMs: 8,
        error: null,
      },
      findings: [
        {
          id: 'finding-1',
          ruleId: 'expected-row-eliminated-at-join',
          severity: 'HIGH' as const,
          title: 'Row eliminated at JOIN',
          message: 'JOIN condition filtered out the target row',
          evidence: ['order_items JOIN orders ON ...'],
          confidence: 0.9,
          verificationSql: 'SELECT * FROM order_items WHERE order_id = 12345',
          affectedNodeId: 'f2',
          status: 'SUSPECTED' as const,
          verificationResult: null,
        },
      ],
      totalElapsedMs: 20,
      error: null,
      eliminationOutcome: null,
      errorReason: null,
    }
  }

  function pollCancelled(traceId: string, reason: string) {
    return {
      traceId,
      status: 'CANCELLED' as const,
      steps: [],
      eliminationStep: null,
      findings: [],
      totalElapsedMs: 7,
      error: reason,
      eliminationOutcome: null,
      errorReason: null,
    }
  }

  // ── tests ───────────────────────────────────────────────────────

  it.each([
    ['valid long boundary', '9223372036854775807', '9223372036854775807', '1', 'EXCEEDS_EXPECTATION', null],
    ['root disagrees with observation', '2', '3', '1', 'EXCEEDS_EXPECTATION', 'TRACE_RESPONSE_INVALID'],
    ['expectation disagrees with submission', '2', '2', '2', 'WITHIN_EXPECTATION', 'TRACE_RESPONSE_INVALID'],
    ['verdict disagrees with count', '2', '2', '1', 'WITHIN_EXPECTATION', 'TRACE_RESPONSE_INVALID'],
    ['count overflows long', '9223372036854775808', '9223372036854775808', '1', 'EXCEEDS_EXPECTATION', 'TRACE_RESPONSE_INVALID'],
  ])('%s', async (_label, count, rootCount, expectedMax, verdict, error) => {
    const response = { ...pollCompleted('trace-quantity'), mode: 'CARDINALITY',
      steps: [{ ...pollRunning('trace-quantity').steps[0], count }],
      quantity: { rootCount, expectedMax, verdict, comparisons: [] } }
    mockFetchResponses({ body: { traceId: 'trace-quantity' } }, { body: response })
    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))
    await act(async () => { result.current.trace('conn-1', '{"id":"f1","sqlFragment":"SELECT 1"}', KEYS,
      { mode: 'CARDINALITY', expectedMax: '01' }) })
    await advanceAndFlush(500)
    expect(result.current.errorReason).toBe(error)
    if (error) { expect(result.current.quantity).toBeNull(); expect(result.current.steps).toEqual([]) }
    else expect(result.current.quantity?.rootCount).toBe(count)
  })

  it('starts in idle state', () => {
    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    expect(result.current.status).toBe('idle')
    expect(result.current.traceId).toBeNull()
    expect(result.current.steps).toEqual([])
    expect(result.current.eliminationStep).toBeNull()
    expect(result.current.findings).toEqual([])
    expect(result.current.totalElapsedMs).toBe(0)
    expect(result.current.error).toBeNull()
    expect(result.current.eliminationOutcome).toBeNull()
    expect(result.current.errorReason).toBeNull()
  })

  it('submit → poll → COMPLETED flow', async () => {
    mockFetchResponses(
      // POST /api/diagnostics/trace-async — returns traceId
      { body: { traceId: 'trace-1' } },
      // GET poll 1 — still running
      { body: pollRunning('trace-1') },
      // GET poll 2 — completed
      { body: pollCompleted('trace-1') }
    )

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    // Trigger trace submission
    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    // After POST resolves: traceId set, status is RUNNING
    expect(result.current.traceId).toBe('trace-1')
    expect(result.current.status).toBe('RUNNING')
    expect(result.current.steps).toEqual([])

    // Advance past first poll interval (500ms) — returns RUNNING
    await advanceAndFlush(500)
    expect(result.current.status).toBe('RUNNING')
    expect(result.current.steps).toHaveLength(1)
    expect(result.current.steps[0].fragmentLabel).toBe('frag-1')

    // Advance past second poll interval — returns COMPLETED
    await advanceAndFlush(500)
    expect(result.current.status).toBe('COMPLETED')
    expect(result.current.steps).toHaveLength(2)
    expect(result.current.eliminationStep).toBeTruthy()
    expect(result.current.eliminationStep!.fragmentLabel).toBe('order_items')
    expect(result.current.findings).toHaveLength(1)
    expect(result.current.findings[0].ruleId).toBe('expected-row-eliminated-at-join')
    expect(result.current.totalElapsedMs).toBe(20)
    expect(result.current.error).toBeNull()
  })

  it('coerces an absent nullable key (non_null strips it) to null on poll', async () => {
    // A RUNNING poll omits `eliminationStep` and `error` entirely — both are
    // null server-side and `spring.jackson.default-property-inclusion: non_null`
    // removes the keys. The hook must still expose null, not undefined (the old
    // read assigned the raw absent key).
    mockFetchResponses(
      { body: { traceId: 'trace-null' } },
      {
        body: {
          traceId: 'trace-null',
          status: 'RUNNING',
          steps: [],
          findings: [],
          totalElapsedMs: 0,
          // eliminationStep / error deliberately absent
        },
      }
    )

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })
    await advanceAndFlush(500)

    expect(result.current.status).toBe('RUNNING')
    expect(result.current.eliminationStep).toBeNull()
    expect(result.current.error).toBeNull()
  })

  it('handles FAILED status from poll', async () => {
    mockFetchResponses(
      { body: { traceId: 'trace-2' } },
      {
        body: {
          traceId: 'trace-2',
          status: 'FAILED',
          steps: [],
          eliminationStep: null,
          findings: [],
          totalElapsedMs: 0,
          error: 'Connection refused',
          eliminationOutcome: null,
          errorReason: null,
        },
      }
    )

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    // Advance past poll interval — returns FAILED
    await advanceAndFlush(500)

    expect(result.current.status).toBe('FAILED')
    expect(result.current.error).toBe('Connection refused')
    expect(result.current.traceId).toBe('trace-2')
  })

  it('handles POST submission failure (non-ok response)', async () => {
    mockFetchResponses(
      { status: 500, body: { message: 'Thread pool exhausted' } }
    )

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    expect(result.current.status).toBe('FAILED')
    expect(result.current.error).toBe('Thread pool exhausted')
    expect(result.current.traceId).toBeNull()
  })

  it('cancel() sends DELETE and stops polling', async () => {
    mockFetchResponses(
      // POST — submit
      { body: { traceId: 'trace-3' } },
      // GET poll — RUNNING
      { body: pollRunning('trace-3') },
      // DELETE — cancel
      { body: {} },
      // GET — cancel reads the terminal result back
      { body: pollCancelled('trace-3', 'Cancelled by user request') }
    )

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    // Advance one poll cycle
    await advanceAndFlush(500)
    expect(result.current.status).toBe('RUNNING')

    // Cancel
    await act(async () => {
      result.current.cancel()
    })

    expect(result.current.status).toBe('CANCELLED')

    // Verify DELETE was sent (the last call is now the read-back GET).
    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    const deleteCall = fetchCalls.find((c) => (c[1] as RequestInit | undefined)?.method === 'DELETE')
    expect(deleteCall).toBeTruthy()
    expect(deleteCall![0]).toContain('/api/diagnostics/trace-async/trace-3')
  })

  it('cancel() reads the terminal result so the reason is shown', async () => {
    mockFetchResponses({ body: { traceId: 'trace-c1' } })
    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))
    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    // Polling phase: any number of RUNNING responses.
    const pollSpy = mockFetchResponses({ body: pollRunning('trace-c1') }) as ReturnType<typeof vi.fn>
    await advanceAndFlush(500)
    expect(result.current.status).toBe('RUNNING')

    // Cancel phase: DELETE (no payload) + one terminal GET.
    const cancelSpy = mockFetchResponses(
      { body: {} },
      { body: pollCancelled('trace-c1', 'Cancelled by user request') }
    ) as ReturnType<typeof vi.fn>

    await act(async () => {
      result.current.cancel()
    })

    expect(result.current.status).toBe('CANCELLED')
    expect(result.current.error).toBe('Cancelled by user request')

    // Call shape: DELETE first, then a method-less GET read-back.
    expect(cancelSpy).toHaveBeenCalledTimes(2)
    expect(cancelSpy.mock.calls[0][0]).toContain('/api/diagnostics/trace-async/trace-c1')
    expect((cancelSpy.mock.calls[0][1] as RequestInit | undefined)?.method).toBe('DELETE')
    expect(cancelSpy.mock.calls[1][0]).toContain('/api/diagnostics/trace-async/trace-c1')
    expect((cancelSpy.mock.calls[1][1] as RequestInit | undefined)?.method).toBeUndefined()
    expect(pollSpy).toHaveBeenCalled()
  })

  it('cancel() treats a 404 (already terminal) as a no-op, not a failure', async () => {
    // 后端对已终态 trace 的 DELETE 回 404。404 只是「这次取消没东西可取消」，
    // 读回终态即可 —— 不能报成取消失败、更不能把已完成的 trace 写成 CANCELLED。
    mockFetchResponses({ body: { traceId: 'trace-c2' } })
    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))
    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    mockFetchResponses({ body: pollRunning('trace-c2') })
    await advanceAndFlush(500)

    mockFetchResponses({ status: 404, body: {} }, { body: pollCompleted('trace-c2') })

    await act(async () => {
      result.current.cancel()
    })

    expect(result.current.status).toBe('COMPLETED')
    expect(result.current.error).toBeNull()
  })

  it('an in-flight poll resolving after cancel() cannot overwrite the terminal state', async () => {
    // 取消期间的竞态：一个在 DELETE 之前发出的轮询 GET，若在取消读回**之后**
    // 才 resolve，会把刚写下的终态改回 RUNNING；而 interval 已被 stopPolling()
    // clearInterval ⇒ UI 永久停在运行态，取消原因永不出现。
    const jsonRes = (body: unknown, status = 200) => ({
      ok: status < 400,
      status,
      json: () => Promise.resolve(body),
      text: () => Promise.resolve(JSON.stringify(body)),
    })

    // The first GET (the poll tick) is held pending; later GETs (the cancel
    // read-back) resolve immediately.
    let releasePoll: (() => void) | null = null
    globalThis.fetch = vi.fn().mockImplementation((_url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (method === 'POST') return Promise.resolve(jsonRes({ traceId: 'trace-race' }))
      if (method === 'DELETE') return Promise.resolve(jsonRes({}))
      if (!releasePoll) {
        return new Promise((resolve) => {
          releasePoll = () => resolve(jsonRes(pollRunning('trace-race')))
        })
      }
      return Promise.resolve(jsonRes(pollCancelled('trace-race', 'Cancelled by user request')))
    })

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))
    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    // Fire one poll tick — its GET is issued and left pending (in flight).
    await act(async () => {
      vi.advanceTimersByTime(500)
    })

    await act(async () => {
      result.current.cancel()
    })
    // Status is terminal here (cancelled). The reason is asserted only after
    // the in-flight poll resolves, so THIS case turns red on the missing poll
    // guard rather than on the cancel read-back.
    expect(result.current.status).toBe('CANCELLED')

    // The in-flight poll now resolves, AFTER the cancel read-back.
    await act(async () => {
      releasePoll?.()
      await Promise.resolve()
    })

    expect(result.current.status).toBe('CANCELLED')
    expect(result.current.error).toBe('Cancelled by user request')
  })

  it('clear() resets all state to idle', async () => {
    mockFetchResponses(
      { body: { traceId: 'trace-4' } },
      { body: pollCompleted('trace-4') }
    )

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    // Run trace to completion
    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })
    await advanceAndFlush(500)

    expect(result.current.status).toBe('COMPLETED')
    expect(result.current.steps).toHaveLength(2)

    // Clear
    act(() => {
      result.current.clear()
    })

    expect(result.current.status).toBe('idle')
    expect(result.current.traceId).toBeNull()
    expect(result.current.steps).toEqual([])
    expect(result.current.eliminationStep).toBeNull()
    expect(result.current.findings).toEqual([])
    expect(result.current.totalElapsedMs).toBe(0)
    expect(result.current.error).toBeNull()
  })

  it('does nothing when backendUrl is null', async () => {
    const { result } = renderHook(() => useRowTrace(null))

    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    // Should remain idle — no fetch calls
    expect(result.current.status).toBe('idle')
    expect(result.current.traceId).toBeNull()
    expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(0)
  })

  it('sends correct POST body and endpoint', async () => {
    mockFetchResponses(
      { body: { traceId: 'trace-5' } },
      { body: pollCompleted('trace-5') }
    )

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    await act(async () => {
      result.current.trace('conn-42', '{"nodes":[]}', KEYS)
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    const postCall = fetchCalls[0]

    // Correct endpoint
    expect(postCall[0]).toBe('http://localhost:8080/api/diagnostics/trace-async')
    expect(postCall[1].method).toBe('POST')

    // Correct body
    const body = JSON.parse(postCall[1].body as string)
    expect(body.connectionId).toBe('conn-42')
    expect(body.fragmentTreeJson).toBe('{"nodes":[]}')
    expect(body.keys).toEqual(KEYS)
  })

  it('polls the correct GET endpoint', async () => {
    mockFetchResponses(
      { body: { traceId: 'trace-6' } },
      { body: pollRunning('trace-6') },
      { body: pollCompleted('trace-6') }
    )

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    await advanceAndFlush(500)

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    // Second call is the GET poll
    const pollCall = fetchCalls[1]
    expect(pollCall[0]).toBe('http://localhost:8080/api/diagnostics/trace-async/trace-6')
    // GET should have no method or 'GET' method. apiFetch attaches its deadline signal
    // (spec §4.2), so the init is no longer absent — assert the absence of a METHOD, which is
    // what this test is about, and pin that the poll is bounded.
    expect(pollCall[1]?.method).toBeUndefined()
    expect(pollCall[1]?.signal).toEqual(expect.any(AbortSignal))
  })

  it('stops polling on terminal COMPLETED status', async () => {
    mockFetchResponses(
      { body: { traceId: 'trace-7' } },
      { body: pollCompleted('trace-7') }
    )

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    await advanceAndFlush(500)
    expect(result.current.status).toBe('COMPLETED')

    // Advance more — no additional fetch calls should happen
    const callsBefore = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.length
    await advanceAndFlush(1000)
    const callsAfter = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.length
    expect(callsAfter).toBe(callsBefore)
  })

  it('updates steps incrementally during RUNNING', async () => {
    mockFetchResponses(
      { body: { traceId: 'trace-8' } },
      // Poll 1: 1 step done
      { body: pollRunning('trace-8', 1) },
      // Poll 2: 3 steps done
      { body: pollRunning('trace-8', 3) },
      // Poll 3: completed
      { body: pollCompleted('trace-8') }
    )

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    await advanceAndFlush(500)
    expect(result.current.steps).toHaveLength(1)

    await advanceAndFlush(500)
    expect(result.current.steps).toHaveLength(3)

    await advanceAndFlush(500)
    expect(result.current.status).toBe('COMPLETED')
  })

  it('cancel() does nothing when no trace is active', async () => {
    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    await act(async () => {
      result.current.cancel()
    })

    // No fetch should have been called
    expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(0)
    expect(result.current.status).toBe('idle')
  })

  it('handles FAILED status from POST with missing error message', async () => {
    mockFetchResponses(
      { status: 503, body: {} }
    )

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    expect(result.current.status).toBe('FAILED')
    // Falls back to default message when response has no message field
    expect(result.current.error).toBe('Submission failed (HTTP 503)')
  })

  it('handles network error during POST', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error('Network error'))

    const { result } = renderHook(() => useRowTrace('http://localhost:8080'))

    await act(async () => {
      result.current.trace('conn-1', FRAG_TREE, KEYS)
    })

    expect(result.current.status).toBe('FAILED')
    expect(result.current.error).toBe('Network error')
  })
})
