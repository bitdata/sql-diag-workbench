// src/renderer/hooks/__tests__/useDiagnostics.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useDiagnostics } from '../useDiagnostics'
import { mockFetchResponses, lastFetchBody, resetMocks } from '../../test/testUtils'

describe('useDiagnostics', () => {
  beforeEach(() => {
    resetMocks()
  })

  it('analyze() populates findings from backend response', async () => {
    const findings = [
      {
        id: 'f1',
        ruleId: 'R001',
        severity: 'HIGH',
        title: 'Missing index',
        message: 'No index on column x',
        evidence: ['seq scan detected'],
        confidence: 0.9,
        verificationSql: null,
        affectedNodeId: 'node-1',
        status: 'SUSPECTED',
        verificationResult: null,
      },
    ]
    mockFetchResponses({ body: { findings } })

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    expect(result.current.findings).toEqual([])
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()

    await act(async () => {
      result.current.analyze({ id: 'frag-1' }, { operation: 'Seq Scan' }, { durationMs: 42 }, null)
    })

    expect(result.current.loading).toBe(false)
    expect(result.current.findings).toEqual(findings)
    expect(result.current.error).toBeNull()
  })

  it('analyze() sends correct POST body and URL', async () => {
    mockFetchResponses({ body: { findings: [] } })

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    const fragment = { id: 'frag-1', sql: 'SELECT 1' }
    const plan = { operation: 'Seq Scan' }
    const timing = { durationMs: 10 }

    await act(async () => {
      result.current.analyze(fragment, plan, timing, 'sess-1')
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    expect(fetchCalls).toHaveLength(1)
    expect(fetchCalls[0][0]).toBe('http://localhost:8080/api/diagnostics/analyze')
    expect(fetchCalls[0][1].method).toBe('POST')

    const body = lastFetchBody()
    expect(body).toEqual({ fragment, plan, timing, sessionId: 'sess-1', fragmentId: 'frag-1' })
  })

  it('analyze() with empty findings array — no error, findings is []', async () => {
    mockFetchResponses({ body: { findings: [] } })

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    await act(async () => {
      result.current.analyze({}, {}, {}, null)
    })

    expect(result.current.findings).toEqual([])
    expect(result.current.error).toBeNull()
    expect(result.current.loading).toBe(false)
  })

  it('analyze() handles missing findings key (defaults to [])', async () => {
    // Backend omits "findings" entirely — hook uses ?? [] fallback
    mockFetchResponses({ body: {} })

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    await act(async () => {
      result.current.analyze({}, {}, {}, null)
    })

    expect(result.current.findings).toEqual([])
    expect(result.current.error).toBeNull()
    expect(result.current.loading).toBe(false)
  })

  it('analyze() sets loading=true during request, then false on completion', async () => {
    // Use a deferred promise to observe the loading intermediate state
    let resolveFetch: (v: unknown) => void
    globalThis.fetch = vi.fn().mockReturnValue(
      new Promise(resolve => { resolveFetch = resolve })
    )

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    // Fire analyze without awaiting — observe loading
    let analyzePromise: Promise<void>
    act(() => {
      analyzePromise = result.current.analyze({}, {}, {}, null)
    })

    // After setState fires synchronously, loading should be true
    expect(result.current.loading).toBe(true)
    expect(result.current.error).toBeNull()

    // Resolve the fetch
    await act(async () => {
      resolveFetch!({
        json: () => Promise.resolve({ findings: [] }),
      })
      await analyzePromise
    })

    expect(result.current.loading).toBe(false)
  })

  it('analyze() on network error — error state populated', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error('Network failure'))

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    await act(async () => {
      result.current.analyze({}, {}, {}, null)
    })

    expect(result.current.error).toBe('Network failure')
    expect(result.current.loading).toBe(false)
    expect(result.current.findings).toEqual([])
  })

  it('analyze() does nothing when backendUrl is null', async () => {
    const { result } = renderHook(() => useDiagnostics(null))

    await act(async () => {
      result.current.analyze({}, {}, {}, null)
    })

    // fetch was never called
    expect(globalThis.fetch).not.toHaveBeenCalled()
    expect(result.current.loading).toBe(false)
    expect(result.current.findings).toEqual([])
    expect(result.current.error).toBeNull()
  })

  it('clear() resets all state to initial values', async () => {
    const findings = [
      {
        id: 'f1',
        ruleId: 'R001',
        severity: 'LOW',
        title: 'Test',
        message: 'msg',
        evidence: [],
        confidence: 1,
        verificationSql: null,
        affectedNodeId: null,
        status: 'SUSPECTED',
        verificationResult: null,
      },
    ]
    mockFetchResponses({ body: { findings } })

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    // Populate state first
    await act(async () => {
      result.current.analyze({}, {}, {}, null)
    })

    expect(result.current.findings).toHaveLength(1)

    // Clear
    act(() => {
      result.current.clear()
    })

    expect(result.current.findings).toEqual([])
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('clear() resets state after a network error', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error('timeout'))

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    await act(async () => {
      result.current.analyze({}, {}, {}, null)
    })

    expect(result.current.error).toBe('timeout')

    act(() => {
      result.current.clear()
    })

    expect(result.current.error).toBeNull()
    expect(result.current.findings).toEqual([])
    expect(result.current.loading).toBe(false)
  })

  it('initial state is correct', () => {
    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    expect(result.current.findings).toEqual([])
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
    expect(result.current.verifyingIds).toEqual(new Set())
    expect(typeof result.current.analyze).toBe('function')
    expect(typeof result.current.clear).toBe('function')
    expect(typeof result.current.verify).toBe('function')
  })

  it('verify() updates finding status on success', async () => {
    // First populate findings
    const findings = [{
      id: 'f1', ruleId: 'R001', severity: 'HIGH', title: 'Test',
      message: 'msg', evidence: [], confidence: 0.9,
      verificationSql: 'SELECT 1', affectedNodeId: null,
      status: 'SUSPECTED' as const, verificationResult: null,
    }]
    mockFetchResponses(
      { body: { findings } },     // analyze response
      { body: { findingId: 'f1', status: 'CONFIRMED', resultSummary: '1 row(s) found' } }  // verify response
    )

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    await act(async () => {
      await result.current.analyze({ id: 'frag-1' }, {}, {}, 'sess-1')
    })

    expect(result.current.findings[0].status).toBe('SUSPECTED')

    await act(async () => {
      await result.current.verify('f1', 'SELECT 1', 'conn-1')
    })

    expect(result.current.findings[0].status).toBe('CONFIRMED')
    expect(result.current.findings[0].verificationResult).toBe('1 row(s) found')
  })

  it('verify() adds findingId to verifyingIds during request', async () => {
    const findings = [{
      id: 'f1', ruleId: 'R001', severity: 'HIGH', title: 'Test',
      message: 'msg', evidence: [], confidence: 0.9,
      verificationSql: 'SELECT 1', affectedNodeId: null,
      status: 'SUSPECTED' as const, verificationResult: null,
    }]
    mockFetchResponses({ body: { findings } })

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    await act(async () => {
      await result.current.analyze({ id: 'frag-1' }, {}, {}, null)
    })

    // Start verify with deferred promise
    let resolveFetch: (v: unknown) => void
    globalThis.fetch = vi.fn().mockReturnValue(
      new Promise(resolve => { resolveFetch = resolve })
    )

    let verifyPromise: Promise<void>
    act(() => {
      verifyPromise = result.current.verify('f1', 'SELECT 1', 'conn-1')
    })

    expect(result.current.verifyingIds.has('f1')).toBe(true)

    // Resolve
    await act(async () => {
      resolveFetch!({
        json: () => Promise.resolve({ findingId: 'f1', status: 'REFUTED', resultSummary: '0 rows' }),
      })
      await verifyPromise
    })

    expect(result.current.verifyingIds.has('f1')).toBe(false)
  })

  it('verify() keeps SUSPECTED on network error', async () => {
    const findings = [{
      id: 'f1', ruleId: 'R001', severity: 'MEDIUM', title: 'Test',
      message: 'msg', evidence: [], confidence: 0.5,
      verificationSql: 'SELECT 1', affectedNodeId: null,
      status: 'SUSPECTED' as const, verificationResult: null,
    }]
    mockFetchResponses({ body: { findings } })

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    await act(async () => {
      await result.current.analyze({}, {}, {}, null)
    })

    globalThis.fetch = vi.fn().mockRejectedValue(new Error('Network error'))

    await act(async () => {
      await result.current.verify('f1', 'SELECT 1', 'conn-1')
    })

    expect(result.current.findings[0].status).toBe('SUSPECTED')
    expect(result.current.verifyingIds.has('f1')).toBe(false)
  })

  it('verify() failure populates verifyErrors with error message', async () => {
    const findings = [{
      id: 'f1', ruleId: 'R001', severity: 'HIGH', title: 'Test',
      message: 'msg', evidence: [], confidence: 0.9,
      verificationSql: 'SELECT 1', affectedNodeId: null,
      status: 'SUSPECTED' as const, verificationResult: null,
    }]
    mockFetchResponses({ body: { findings } })

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    await act(async () => {
      await result.current.analyze({}, {}, {}, null)
    })

    globalThis.fetch = vi.fn().mockRejectedValue(new Error('Connection refused'))

    await act(async () => {
      await result.current.verify('f1', 'SELECT 1', 'conn-1')
    })

    expect(result.current.findings[0].status).toBe('SUSPECTED')
    expect(result.current.verifyErrors['f1']).toBe('Connection refused')
  })

  it('clearVerifyError removes the error for a finding', async () => {
    const findings = [{
      id: 'f1', ruleId: 'R001', severity: 'HIGH', title: 'Test',
      message: 'msg', evidence: [], confidence: 0.9,
      verificationSql: 'SELECT 1', affectedNodeId: null,
      status: 'SUSPECTED' as const, verificationResult: null,
    }]
    mockFetchResponses({ body: { findings } })

    const { result } = renderHook(() => useDiagnostics('http://localhost:8080'))

    await act(async () => {
      await result.current.analyze({}, {}, {}, null)
    })

    globalThis.fetch = vi.fn().mockRejectedValue(new Error('timeout'))

    await act(async () => {
      await result.current.verify('f1', 'SELECT 1', 'conn-1')
    })

    expect(result.current.verifyErrors['f1']).toBe('timeout')

    act(() => {
      result.current.clearVerifyError('f1')
    })

    expect(result.current.verifyErrors['f1']).toBeUndefined()
  })
})
