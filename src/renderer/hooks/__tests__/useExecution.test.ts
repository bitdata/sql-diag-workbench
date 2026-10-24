// src/renderer/hooks/__tests__/useExecution.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useExecution } from '../useExecution'
import { mockFetchResponses, resetMocks } from '../../test/testUtils'

describe('useExecution', () => {
  beforeEach(() => {
    resetMocks()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  /**
   * Helper: flush microtasks so async fetch callbacks resolve inside fake timers.
   * `vi.advanceTimersByTimeAsync` handles timer callbacks + their microtasks.
   */
  async function advanceAndFlush(ms: number) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ms)
    })
  }

  it('does not relabel previous data as the newly analyzed editor SQL', async () => {
    mockFetchResponses(
      { body: { executionId: 'data-old' } },
      { body: { executionId: 'data-old', status: 'COMPLETED', columns: [{ name: 'old', type: 'integer' }], rows: [[1]], rowCount: 1 } },
      { body: { executionId: 'plan-new' } },
      { body: { executionId: 'plan-new', status: 'COMPLETED', plan: { nodeType: 'Result' }, rows: null } },
    )
    const { result, rerender } = renderHook(({ sql }) => useExecution(
      'http://localhost:8080', 'editor', undefined, undefined,
      { sessionId: 'session', dialect: 'mysql', connectionId: 'conn', sql },
    ), { initialProps: { sql: 'SELECT 1' } })
    await act(async () => { await result.current.execute('SELECT 1', 'conn') })
    await advanceAndFlush(500)
    expect(result.current.dataResult?.rows).toEqual([[1]])
    rerender({ sql: 'SELECT 2' })
    await act(async () => { await result.current.analyze('SELECT 2', 'conn') })
    await advanceAndFlush(500)
    expect(result.current.planResult?.executionId).toBe('plan-new')
    expect(result.current.provenance?.sql).toBe('SELECT 2')
    expect(result.current.dataResult).toBeNull()
  })

  it('submit → poll → success flow', async () => {
    mockFetchResponses(
      // POST /api/execution/execute — returns executionId
      { body: { executionId: 'exec-1' } },
      // GET poll 1 — still running
      { body: { executionId: 'exec-1', status: 'RUNNING', columns: null, rows: null, rowCount: 0, timing: null, plan: null, planError: null, error: null } },
      // GET poll 2 — completed
      {
        body: {
          executionId: 'exec-1',
          status: 'COMPLETED',
          columns: [{ name: 'col1', type: 'integer' }],
          rows: [[1]],
          rowCount: 1,
          timing: { durationMs: 10 },
          plan: null,
          planError: null,
          error: null,
        },
      }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1')
    )

    // Initially idle
    expect(result.current.loading).toBe(false)
    expect(result.current.dataResult).toBeNull()

    // Trigger execute — the POST resolves as a microtask
    await act(async () => {
      result.current.execute('SELECT 1', 'conn-1')
    })

    // After POST resolves, loading is true and executionId is set
    expect(result.current.loading).toBe(true)
    expect(result.current.executionId).toBe('exec-1')
    expect(result.current.status).toBe('QUEUED')

    // Advance past first poll interval (500ms) — returns RUNNING
    await advanceAndFlush(500)
    expect(result.current.status).toBe('RUNNING')
    expect(result.current.loading).toBe(true)

    // Advance past second poll interval — returns COMPLETED
    await advanceAndFlush(500)

    expect(result.current.loading).toBe(false)
    expect(result.current.status).toBe('COMPLETED')
    expect(result.current.dataResult).toBeTruthy()
    expect(result.current.dataResult!.status).toBe('COMPLETED')
    expect(result.current.dataResult!.rows).toEqual([[1]])
    expect(result.current.error).toBeNull()
  })

  it('coerces an absent nullable `error` key (non_null) to null on a successful poll', async () => {
    // On success `error` is null server-side, so `non_null` removes the key.
    // The old read assigned the raw absent key (undefined); the value must match
    // its declared `string | null` type.
    mockFetchResponses(
      { body: { executionId: 'exec-null' } },
      {
        body: {
          executionId: 'exec-null',
          status: 'COMPLETED',
          columns: [{ name: 'col1', type: 'integer' }],
          rows: [[1]],
          rowCount: 1,
          timing: { durationMs: 10 },
          plan: null,
          planError: null,
          // `error` deliberately absent
        },
      }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1')
    )

    await act(async () => {
      result.current.execute('SELECT 1', 'conn-1')
    })
    await advanceAndFlush(500)

    expect(result.current.status).toBe('COMPLETED')
    expect(result.current.error).toBeNull()
  })

  it('handles FAILED status from poll', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-2' } },
      {
        body: {
          executionId: 'exec-2',
          status: 'FAILED',
          columns: null,
          rows: null,
          rowCount: 0,
          timing: null,
          plan: null,
          planError: null,
          error: 'Connection refused',
        },
      }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1')
    )

    await act(async () => {
      result.current.execute('SELECT 1', 'conn-1')
    })

    // Advance past poll interval — returns FAILED
    await advanceAndFlush(500)

    expect(result.current.loading).toBe(false)
    expect(result.current.status).toBe('FAILED')
    expect(result.current.error).toBe('Connection refused')
  })

  it('sets error when backendUrl is null', async () => {
    const { result } = renderHook(() =>
      useExecution(null, 'node-1')
    )

    await act(async () => {
      result.current.execute('SELECT 1', 'conn-1')
    })

    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBe('Backend not connected')
  })

  it('sets error when activeNodeId is null', async () => {
    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', null)
    )

    await act(async () => {
      result.current.execute('SELECT 1', 'conn-1')
    })

    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBe('No fragment selected')
  })

  it('analyze() writes to planResultCache, not dataResultCache', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-3' } },
      {
        body: {
          executionId: 'exec-3',
          status: 'COMPLETED',
          columns: null,
          rows: null,
          rowCount: 0,
          timing: { durationMs: 5 },
          plan: { operation: 'Seq Scan', startupCost: 0, totalCost: 10, planRows: 100, actualRows: 50, actualDuration: 3, children: [] },
          planError: null,
          error: null,
        },
      }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1')
    )

    await act(async () => {
      result.current.analyze('SELECT 1', 'conn-1')
    })

    await advanceAndFlush(500)

    expect(result.current.loading).toBe(false)
    expect(result.current.status).toBe('COMPLETED')
    // planResult should be populated
    expect(result.current.planResult).toBeTruthy()
    expect(result.current.planResult!.plan).toBeTruthy()
    // dataResult should remain null (analyze doesn't write to data cache)
    expect(result.current.dataResult).toBeNull()
  })

  it('sends maxRows and queryTimeoutSeconds from hook settings in POST body', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-4' } },
      {
        body: {
          executionId: 'exec-4',
          status: 'COMPLETED',
          columns: [],
          rows: [],
          rowCount: 0,
          timing: { durationMs: 1 },
          plan: null,
          planError: null,
          error: null,
        },
      }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1', undefined, { maxRows: 2000, queryTimeout: 30 })
    )

    await act(async () => {
      result.current.execute('SELECT 1', 'conn-1')
    })

    // Read the POST body — the first fetch call
    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    const postCall = fetchCalls[0]
    const postBody = JSON.parse(postCall[1].body as string)
    expect(postBody.sql).toBe('SELECT 1')
    expect(postBody.connectionId).toBe('conn-1')
    expect(postBody.queryTimeoutSeconds).toBe(30)
    expect(postBody.maxRows).toBe(2000)
  })

  it('invokes onExecutionSuccess callback on COMPLETED execute', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-5' } },
      {
        body: {
          executionId: 'exec-5',
          status: 'COMPLETED',
          columns: [{ name: 'a', type: 'int' }],
          rows: [[1], [2], [3]],
          rowCount: 3,
          timing: { durationMs: 42 },
          plan: null,
          planError: null,
          error: null,
        },
      }
    )

    const onSuccess = vi.fn()
    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1', onSuccess)
    )

    await act(async () => {
      result.current.execute('SELECT * FROM t', 'conn-1')
    })

    await advanceAndFlush(500)

    expect(result.current.loading).toBe(false)
    expect(onSuccess).toHaveBeenCalledOnce()
    expect(onSuccess).toHaveBeenCalledWith({ rows: 3, durationMs: 42, resultCompleteness: { state: 'UNKNOWN', appliedLimit: null, errorReason: 'COMPLETENESS_UNVERIFIED' } }, expect.objectContaining({
      nodeId: 'node-1', sql: 'SELECT * FROM t', connectionId: 'conn-1', kind: 'execute',
    }))
  })

  // 该文件的 mockFetchResponses 按调用序取响应、用尽后**重复最后一条**，所以轮询
  // 期间不能把 DELETE / 终态响应预先排进队列 —— 多出的一次轮询会把它们提前吃掉。
  // 正确做法是先把轮询阶段整体替换成一个可重复的 RUNNING 响应，取消前再重新 mock
  // 成「DELETE + 终态 GET」两条。这也是旧用例能断言「最后一次 fetch 是 DELETE」的
  // 原因：当时队列到 DELETE 为止。
  it('cancel() sends DELETE, then reads the terminal result so the reason is shown', async () => {
    const runningBody = { executionId: 'exec-6', status: 'RUNNING', columns: null, rows: null,
                          rowCount: 0, timing: null, plan: null, planError: null, error: null }

    const { result } = renderHook(() => useExecution('http://localhost:8080', 'node-1'))
    mockFetchResponses({ body: { executionId: 'exec-6' } })          // execute POST
    await act(async () => { result.current.execute('SELECT 1', 'conn-1') })
    await act(async () => { await Promise.resolve() })               // flush a pending promise/microtask (there is no immediate poll; the interval first fires at 500ms)

    // 轮询阶段：任何次数都返回 RUNNING
    const pollSpy = mockFetchResponses({ body: runningBody }) as ReturnType<typeof vi.fn>
    await advanceAndFlush(500)
    expect(result.current.status).toBe('RUNNING')

    // 取消阶段：DELETE（无 payload）+ 一次终态 GET
    const cancelSpy = mockFetchResponses(
      { body: {} },
      { body: { executionId: 'exec-6', status: 'CANCELLED', columns: null, rows: null, rowCount: 0,
                timing: null, plan: null, planError: null,
                error: 'Cancelled by user request' } }
    ) as ReturnType<typeof vi.fn>

    await act(async () => { result.current.cancel() })

    expect(result.current.status).toBe('CANCELLED')
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBe('Cancelled by user request')

    // 调用形状：先 DELETE，后一次无 method 的 GET。
    expect(cancelSpy).toHaveBeenCalledTimes(2)
    expect(cancelSpy.mock.calls[0][0]).toContain('/api/execution/execute/exec-6')
    expect(cancelSpy.mock.calls[0][1]?.method).toBe('DELETE')
    expect(cancelSpy.mock.calls[1][0]).toContain('/api/execution/execute/exec-6')
    expect(cancelSpy.mock.calls[1][1]?.method).toBeUndefined()
    expect(pollSpy).toHaveBeenCalled()   // 轮询在取消前确实发生过
  })

  it('cancel() treats a 404 (already terminal) as a no-op, not a failure', async () => {
    // spec §4.3 (C)：后端对已终态的执行返回 false ⇒ 控制器回 404。404 只是
    // 「这次取消没东西可取消」，读回终态即可 —— 不能报成取消失败。
    const { result } = renderHook(() => useExecution('http://localhost:8080', 'node-1'))
    mockFetchResponses({ body: { executionId: 'exec-8' } })
    await act(async () => { result.current.execute('SELECT 1', 'conn-1') })

    mockFetchResponses({ body: { executionId: 'exec-8', status: 'RUNNING', columns: null, rows: null,
                                 rowCount: 0, timing: null, plan: null, planError: null, error: null } })
    await advanceAndFlush(500)

    mockFetchResponses(
      { status: 404, body: {} },
      { body: { executionId: 'exec-8', status: 'COMPLETED', columns: [], rows: [[1]], rowCount: 1,
                timing: { durationMs: 3 }, plan: null, planError: null, error: null } }
    )

    await act(async () => { result.current.cancel() })

    expect(result.current.status).toBe('COMPLETED')
    expect(result.current.error).toBeNull()
  })

  it('an in-flight poll resolving after cancel() cannot overwrite the terminal state', async () => {
    // 取消期间的竞态（Task 13 addendum C）：一个在 DELETE 之前发出的轮询 GET，
    // 若在取消读回**之后**才 resolve，会把刚写下的终态改回 RUNNING；而 interval
    // 已被 stopPolling() clearInterval ⇒ UI 永久停在运行态，取消原因永不出现。
    const jsonRes = (body: unknown, status = 200) => ({
      ok: status < 400,
      status,
      json: () => Promise.resolve(body),
      text: () => Promise.resolve(JSON.stringify(body)),
    })
    const runningBody = { executionId: 'exec-race', status: 'RUNNING', columns: null, rows: null,
                          rowCount: 0, timing: null, plan: null, planError: null, error: null }
    const cancelledBody = { executionId: 'exec-race', status: 'CANCELLED', columns: null, rows: null,
                            rowCount: 0, timing: null, plan: null, planError: null,
                            error: 'Cancelled by user request' }

    // The first GET (the poll tick) is held pending; later GETs (the cancel
    // read-back) resolve immediately.
    let releasePoll: (() => void) | null = null
    globalThis.fetch = vi.fn().mockImplementation((_url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (method === 'POST') return Promise.resolve(jsonRes({ executionId: 'exec-race' }))
      if (method === 'DELETE') return Promise.resolve(jsonRes({}))
      if (!releasePoll) {
        return new Promise((resolve) => { releasePoll = () => resolve(jsonRes(runningBody)) })
      }
      return Promise.resolve(jsonRes(cancelledBody))
    })

    const { result } = renderHook(() => useExecution('http://localhost:8080', 'node-1'))
    await act(async () => { result.current.execute('SELECT 1', 'conn-1') })

    // Fire one poll tick — its GET is issued and left pending (in flight).
    await act(async () => { vi.advanceTimersByTime(500) })

    await act(async () => { result.current.cancel() })
    expect(result.current.status).toBe('CANCELLED')
    expect(result.current.error).toBe('Cancelled by user request')

    // The in-flight poll now resolves, AFTER the cancel read-back.
    await act(async () => { releasePoll?.(); await Promise.resolve() })

    expect(result.current.status).toBe('CANCELLED')
    expect(result.current.error).toBe('Cancelled by user request')
  })

  it('reset() clears all state', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-7' } },
      {
        body: {
          executionId: 'exec-7',
          status: 'COMPLETED',
          columns: [],
          rows: [[1]],
          rowCount: 1,
          timing: { durationMs: 5 },
          plan: null,
          planError: null,
          error: null,
        },
      }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1')
    )

    await act(async () => {
      result.current.execute('SELECT 1', 'conn-1')
    })

    await advanceAndFlush(500)

    expect(result.current.dataResult).toBeTruthy()

    // Reset
    act(() => {
      result.current.reset()
    })

    expect(result.current.dataResult).toBeNull()
    expect(result.current.planResult).toBeNull()
    expect(result.current.executionId).toBeNull()
    expect(result.current.status).toBeNull()
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('POSTs to the correct execute endpoint', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-8' } },
      {
        body: {
          executionId: 'exec-8',
          status: 'COMPLETED',
          columns: [],
          rows: [],
          rowCount: 0,
          timing: { durationMs: 1 },
          plan: null,
          planError: null,
          error: null,
        },
      }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1')
    )

    await act(async () => {
      result.current.execute('SELECT 1', 'conn-1')
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    const postUrl = fetchCalls[0][0]
    expect(postUrl).toBe('http://localhost:8080/api/execution/execute')
  })

  it('sends explicit maxRows and queryTimeout to backend', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-settings' } },
      { body: { executionId: 'exec-settings', status: 'COMPLETED', columns: [], rows: [] } }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1', undefined, { maxRows: 500, queryTimeout: 15 })
    )

    await act(async () => {
      result.current.execute('SELECT 1', 'conn-1')
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    const postCall = fetchCalls[0]
    const body = JSON.parse(postCall[1].body as string)
    expect(body.maxRows).toBe(500)
    expect(body.queryTimeoutSeconds).toBe(15)
  })

  it('uses default settings when no executionSettings provided', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-defaults' } },
      { body: { executionId: 'exec-defaults', status: 'COMPLETED', columns: [], rows: [] } }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1')
    )

    await act(async () => {
      result.current.execute('SELECT 1', 'conn-1')
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    const postCall = fetchCalls[0]
    const body = JSON.parse(postCall[1].body as string)
    expect(body.maxRows).toBe(1000)
    expect(body.queryTimeoutSeconds).toBe(30)
  })

  it('sends different maxRows values when hook settings change', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-custom' } },
      { body: { executionId: 'exec-custom', status: 'COMPLETED', columns: [], rows: [] } }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1', undefined, { maxRows: 250, queryTimeout: 60 })
    )

    await act(async () => {
      result.current.execute('SELECT 1', 'conn-1')
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    const postCall = fetchCalls[0]
    const body = JSON.parse(postCall[1].body as string)
    expect(body.maxRows).toBe(250)
    expect(body.queryTimeoutSeconds).toBe(60)
  })

  it('POSTs analyze to the correct endpoint', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-9' } },
      {
        body: {
          executionId: 'exec-9',
          status: 'COMPLETED',
          columns: null,
          rows: null,
          rowCount: 0,
          timing: { durationMs: 1 },
          plan: { operation: 'Seq Scan', startupCost: 0, totalCost: 10, planRows: 100, actualRows: 50, actualDuration: 3, children: [] },
          planError: null,
          error: null,
        },
      }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1')
    )

    await act(async () => {
      result.current.analyze('SELECT 1', 'conn-1')
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    const postUrl = fetchCalls[0][0]
    expect(postUrl).toBe('http://localhost:8080/api/execution/analyze')
  })

  it('analyze() sends analyzeMode in POST body', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-mode' } },
      {
        body: {
          executionId: 'exec-mode',
          status: 'COMPLETED',
          columns: null,
          rows: null,
          rowCount: 0,
          timing: { durationMs: 1 },
          plan: { operation: 'Seq Scan', startupCost: 0, totalCost: 10, planRows: 100, actualRows: 50, actualDuration: 3, children: [] },
          planError: null,
          error: null,
        },
      }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1')
    )

    await act(async () => {
      result.current.analyze('SELECT 1', 'conn-1', 'actual')
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    const postCall = fetchCalls[0]
    const body = JSON.parse(postCall[1].body as string)
    expect(body.analyzeMode).toBe('actual')
  })

  it('analyze() defaults to estimated mode when no mode specified', async () => {
    mockFetchResponses(
      { body: { executionId: 'exec-est' } },
      {
        body: {
          executionId: 'exec-est',
          status: 'COMPLETED',
          columns: null,
          rows: null,
          rowCount: 0,
          timing: { durationMs: 1 },
          plan: { operation: 'Seq Scan', startupCost: 0, totalCost: 10, planRows: 100, actualRows: 0, actualDuration: 0, children: [] },
          planError: null,
          error: null,
        },
      }
    )

    const { result } = renderHook(() =>
      useExecution('http://localhost:8080', 'node-1')
    )

    await act(async () => {
      result.current.analyze('SELECT 1', 'conn-1')
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    const postCall = fetchCalls[0]
    const body = JSON.parse(postCall[1].body as string)
    expect(body.analyzeMode).toBe('estimated')
  })
})
