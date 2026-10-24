// src/renderer/hooks/__tests__/useFragmentTree.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useFragmentTree } from '../useFragmentTree'
import { mockFetchResponses, lastFetchBody, resetMocks } from '../../test/testUtils'
import { sha256 } from '../../utils/sha256'
import type { FragmentNode, ParseResult, DecomposeOption } from '../../types/fragment'
import type { SessionWorkspaceState } from '../../types/session'

// ─── Fixtures ──────────────────────────────────────────────────────────────

const rootNode: FragmentNode = {
  id: 'root-1',
  type: 'ORIGINAL',
  label: 'Full Query',
  sqlFragment: 'SELECT a, b FROM t1 JOIN t2 ON t1.id = t2.id WHERE t1.x > 10',
  executable: true,
  executionHint: null,
  nodeCount: 0,
  ruleName: null,
  children: [],
  parentIds: [],
}

const sampleOptions: DecomposeOption[] = [
  { ruleName: 'join-split', displayName: 'Split JOIN', description: 'Split by JOIN', level: 'STRUCTURE' },
  { ruleName: 'clause-stripping', displayName: 'Strip Clauses', description: 'Remove WHERE', level: 'CLAUSE' },
]

const sampleParseResult: ParseResult = {
  tree: rootNode,
  options: sampleOptions,
  errors: [],
}

const childA: FragmentNode = {
  id: 'child-a',
  type: 'JOIN_SPLIT',
  label: 'Table t1',
  sqlFragment: 'SELECT a FROM t1 WHERE t1.x > 10',
  executable: true,
  executionHint: null,
  nodeCount: 0,
  ruleName: 'join-split',
  children: [],
  parentIds: ['root-1'],
}

const childB: FragmentNode = {
  id: 'child-b',
  type: 'JOIN_SPLIT',
  label: 'Table t2',
  sqlFragment: 'SELECT b FROM t2',
  executable: true,
  executionHint: null,
  nodeCount: 0,
  ruleName: 'join-split',
  children: [],
  parentIds: ['root-1'],
}

function batch(children: FragmentNode[]) {
  return { status: children.length ? 'COMPLETE' : 'NOT_APPLICABLE', children, issues: [], outputs: children.map((c, i) => ({
    sourceNodeId: c.parentIds?.[0] ?? rootNode.id, ruleName: c.ruleName ?? 'join-split', targetNodeId: c.id, ordinal: i + 1,
    effect: { sourceSqlHash: sha256(rootNode.sqlFragment), targetSqlHash: sha256(c.sqlFragment), context: 'PRESERVED', changes: [], retained: [], limitations: [] },
  })) }
}
function optionsEnvelope(options: DecomposeOption[]) { return { options, scan: { status: 'COMPLETE', issues: [] } } }

const BACKEND = 'http://localhost:8080'

// ─── Tests ─────────────────────────────────────────────────────────────────

describe('useFragmentTree', () => {
  beforeEach(() => {
    resetMocks()
  })

  it('passes the selected connection through parse, option scanning and splitting; refreshes scans on switch', async () => {
    const workspace: SessionWorkspaceState = {
      sessionId: 'connected', tabName: 'Connected', sql: rootNode.sqlFragment,
      isDirty: false, lastSavedAt: null, connectionId: 'db-a', dialect: 'mysql',
      fragmentTreeJson: null, selectedNodeId: null, workspaceMode: 'data', viewerMode: 'browse',
    }
    const { result, rerender } = renderHook(({ ws }) => useFragmentTree(BACKEND, ws), { initialProps: { ws: workspace } })
    mockFetchResponses({ body: sampleParseResult })
    await act(async () => { await result.current.parse(rootNode.sqlFragment, 'mysql') })
    expect(lastFetchBody()).toMatchObject({ connectionId: 'db-a' })
    rerender({ ws: { ...workspace, connectionId: 'db-b' } })
    mockFetchResponses({ body: optionsEnvelope(sampleOptions) })
    await act(async () => { result.current.selectNode(rootNode.id) })
    expect(lastFetchBody()).toMatchObject({ connectionId: 'db-b', dialect: 'mysql' })
    mockFetchResponses({ body: batch([childA, childB]) })
    await act(async () => { await result.current.decomposeNode(rootNode.id, 'join-split') })
    expect(lastFetchBody()).toMatchObject({ connectionId: 'db-b', ruleName: 'join-split' })
    expect(result.current.result?.tree?.children).toHaveLength(2)
  })

  it('ignores a late metadata scan from the previously selected connection', async () => {
    const workspace: SessionWorkspaceState = {
      sessionId: 'connected', tabName: 'Connected', sql: rootNode.sqlFragment,
      isDirty: false, lastSavedAt: null, connectionId: 'db-a', dialect: 'mysql',
      fragmentTreeJson: JSON.stringify(rootNode), selectedNodeId: rootNode.id, workspaceMode: 'data', viewerMode: 'browse',
    }
    const { result, rerender } = renderHook(({ ws }) => useFragmentTree(BACKEND, ws), { initialProps: { ws: workspace } })
    let finish!: (value: unknown) => void
    globalThis.fetch = vi.fn().mockImplementation(() => new Promise(resolve => { finish = resolve }))
    act(() => { result.current.selectNode(rootNode.id) })
    rerender({ ws: { ...workspace, connectionId: 'db-b' } })
    await act(async () => {
      finish({ ok: true, json: async () => optionsEnvelope(sampleOptions) })
    })
    mockFetchResponses({ body: optionsEnvelope([]) })
    await act(async () => { result.current.selectNode(rootNode.id) })
    expect(lastFetchBody()).toMatchObject({ connectionId: 'db-b' })
    expect(globalThis.fetch).toHaveBeenCalledTimes(1)
  })

  it('keeps a parsed CTE usable when the option scan has unresolved JOIN candidates', async () => {
    const issue = { code: 'UNRESOLVED_DEPENDENCY', message: 'Column ownership cannot be proven without a schema', ruleName: 'join-split' }
    mockFetchResponses({ body: { ...sampleParseResult, scan: { status: 'PARTIAL', issues: [issue, issue] } } })
    const { result } = renderHook(() => useFragmentTree(BACKEND))
    await act(async () => { await result.current.parse(rootNode.sqlFragment, 'mysql') })
    expect(result.current.error).toBeNull()
    expect(result.current.warning).toBe(`${issue.code}: ${issue.message}`)
    expect(result.current.result?.tree).toEqual(rootNode)
    expect(result.current.result?.scan?.issues).toHaveLength(2)
    await act(async () => { result.current.reset() })
    expect(result.current.warning).toBeNull()
  })

  it('reports option-scan limitations separately when selecting an extracted fragment', async () => {
    const issue = { code: 'UNRESOLVED_DEPENDENCY', message: 'bare column needs qualification' }
    mockFetchResponses({ body: { ...sampleParseResult, tree: { ...rootNode, children: [childA] } } },
      { body: { options: sampleOptions, scan: { status: 'PARTIAL', issues: [issue, issue] } } })
    const { result } = renderHook(() => useFragmentTree(BACKEND))
    await act(async () => { await result.current.parse(rootNode.sqlFragment) })
    await act(async () => { result.current.selectNode(childA.id) })
    expect(result.current.error).toBeNull()
    expect(result.current.warning).toBe('UNRESOLVED_DEPENDENCY: bare column needs qualification')
    expect(result.current.optionsCache[childA.id]).toEqual(sampleOptions)
  })

  it('retains an error for a failed option scan despite a successful syntax parse', async () => {
    mockFetchResponses({ body: { ...sampleParseResult, scan: { status: 'FAILED', issues: [{ code: 'RULE_FAILED', message: 'scan failed' }] } } })
    const { result } = renderHook(() => useFragmentTree(BACKEND))
    await act(async () => { await result.current.parse(rootNode.sqlFragment) })
    expect(result.current.error).toContain('RULE_FAILED')
    expect(result.current.warning).toBeNull()
  })

  it.each([false, true])('isolates the tree, DAG and selection across tabs (StrictMode=%s)', async (reactStrictMode) => {
    const source: SessionWorkspaceState = {
      sessionId: 'source', tabName: 'Source', sql: rootNode.sqlFragment,
      isDirty: false, lastSavedAt: null, connectionId: null, dialect: 'postgresql',
      fragmentTreeJson: JSON.stringify({ ...rootNode, children: [childA] }),
      selectedNodeId: childA.id, workspaceMode: 'data', viewerMode: 'browse',
    }
    const edited = { ...source, sessionId: 'edited', sql: 'SELECT 42',
      fragmentTreeJson: null, selectedNodeId: null, viewerMode: 'input' as const }
    const { result, rerender } = renderHook(
      ({ workspace }) => useFragmentTree(BACKEND, workspace),
      { initialProps: { workspace: source }, reactStrictMode },
    )
    expect(result.current.selectedNode?.id).toBe(childA.id)
    expect(result.current.dag.nodeMap.has(childA.id)).toBe(true)
    rerender({ workspace: edited })
    expect(result.current.result).toBeNull()
    expect(result.current.selectedNode).toBeNull()
    expect(result.current.dag.nodeMap.size).toBe(0)
    mockFetchResponses({ body: { tree: { ...rootNode, id: 'edited-root', sqlFragment: 'SELECT 42' }, options: [], errors: [] } })
    await act(async () => { await result.current.parse(edited.sql) })
    expect(result.current.selectedNode?.sqlFragment).toBe('SELECT 42')
    rerender({ workspace: source })
    expect(result.current.selectedNode?.id).toBe(childA.id)
    expect(result.current.result?.tree?.children).toEqual([childA])
    expect(result.current.dag.nodeMap.has('edited-root')).toBe(false)
    rerender({ workspace: edited })
    expect(result.current.selectedNode?.sqlFragment).toBe('SELECT 42')
    expect(result.current.dag.nodeMap.has(childA.id)).toBe(false)
  })

  it.each((['parse', 'options', 'decompose'] as const).flatMap(operation =>
    [false, true].map(reactStrictMode => ({ operation, reactStrictMode }))))(
    'ignores a late $operation response across tabs with the same root (StrictMode=$reactStrictMode)',
    async ({ operation, reactStrictMode }) => {
      const workspace: SessionWorkspaceState = {
        sessionId: 'source', tabName: 'Source', sql: rootNode.sqlFragment,
        isDirty: false, lastSavedAt: null, connectionId: null, dialect: 'postgresql',
        fragmentTreeJson: JSON.stringify(rootNode), selectedNodeId: rootNode.id,
        workspaceMode: 'data', viewerMode: 'browse',
      }
      let respond!: (response: unknown) => void
      globalThis.fetch = vi.fn().mockImplementation(() => new Promise((resolve) => { respond = resolve }))
      const { result, rerender } = renderHook(
        ({ ws }) => useFragmentTree(BACKEND, ws), { initialProps: { ws: workspace }, reactStrictMode },
      )
      let pending: Promise<void> | undefined
      act(() => {
        if (operation === 'parse') pending = result.current.parse(workspace.sql)
        else if (operation === 'decompose') pending = result.current.decomposeNode(rootNode.id, 'join-split')
        else result.current.selectNode(rootNode.id)
      })
      rerender({ ws: { ...workspace, sessionId: 'target' } })
      await act(async () => {
        respond({ ok: true, json: async () => operation === 'parse'
          ? { tree: { ...rootNode, children: [childA] }, options: sampleOptions, errors: [] }
          : operation === 'decompose' ? batch([childA]) : optionsEnvelope(sampleOptions) })
        })
      expect(result.current.result?.tree?.children).toEqual([])
      expect(result.current.dag.nodeMap.has(childA.id)).toBe(false)
      expect(result.current.optionsCache).toEqual({})
      expect(result.current.selectedNodeId).toBe(rootNode.id)
    },
  )

  it.each([false, true])('restores a reloaded workspace with the same session id (StrictMode=%s)', (reactStrictMode) => {
    const workspace: SessionWorkspaceState = {
      sessionId: 'source', tabName: 'Source', sql: rootNode.sqlFragment,
      isDirty: false, lastSavedAt: null, connectionId: null, dialect: 'postgresql',
      fragmentTreeJson: JSON.stringify({ ...rootNode, children: [childA] }),
      selectedNodeId: childA.id, workspaceMode: 'data', viewerMode: 'browse', loadEpoch: 0,
    }
    const { result, rerender } = renderHook(({ ws }) => useFragmentTree(BACKEND, ws), {
      initialProps: { ws: workspace }, reactStrictMode,
    })
    const epoch = result.current.treeEpoch
    rerender({ ws: { ...workspace, sql: 'SELECT 42', loadEpoch: 1,
      fragmentTreeJson: JSON.stringify({ ...rootNode, id: 'reloaded', sqlFragment: 'SELECT 42' }),
      selectedNodeId: 'reloaded' } })
    expect(result.current.selectedNode?.sqlFragment).toBe('SELECT 42')
    expect(result.current.dag.nodeMap.has(childA.id)).toBe(false)
    expect(result.current.treeEpoch).toBeGreaterThan(epoch)
  })

  // ── Initial state ──────────────────────────────────────────────────────

  it('has correct initial state', () => {
    const { result } = renderHook(() => useFragmentTree(BACKEND))

    expect(result.current.result).toBeNull()
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
    expect(result.current.selectedNodeId).toBeNull()
    expect(result.current.selectedNode).toBeNull()
    expect(result.current.optionsCache).toEqual({})
    expect(result.current.dag.nodeMap.size).toBe(0)
    expect(result.current.dag.appliedRules.size).toBe(0)
  })

  // ── parse() ────────────────────────────────────────────────────────────

  it('parse(sql) populates result and selects root node', async () => {
    mockFetchResponses({ body: sampleParseResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT a, b FROM t1 JOIN t2 ON t1.id = t2.id WHERE t1.x > 10')
    })

    expect(result.current.result).toEqual(sampleParseResult)
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
    expect(result.current.selectedNodeId).toBe('root-1')
  })

  it('parse() sends correct POST body and URL', async () => {
    mockFetchResponses({ body: sampleParseResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1', 'postgresql')
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    expect(fetchCalls).toHaveLength(1)
    expect(fetchCalls[0][0]).toBe(`${BACKEND}/api/sql/parse`)
    expect(fetchCalls[0][1].method).toBe('POST')
    expect(fetchCalls[0][1].headers['Content-Type']).toBe('application/json')

    const body = lastFetchBody()
    expect(body).toEqual({ sql: 'SELECT 1', dialect: 'postgresql' })
  })

  it('parse() defaults dialect to postgresql', async () => {
    mockFetchResponses({ body: sampleParseResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    const body = lastFetchBody()
    expect(body).toEqual({ sql: 'SELECT 1', dialect: 'postgresql' })
  })

  it('parse() caches root options in optionsCache', async () => {
    mockFetchResponses({ body: sampleParseResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    expect(result.current.optionsCache['root-1']).toEqual(sampleOptions)
  })

  it('parse() indexes tree into DAG nodeMap', async () => {
    mockFetchResponses({ body: sampleParseResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    expect(result.current.dag.nodeMap.has('root-1')).toBe(true)
    expect(result.current.dag.nodeMap.get('root-1')?.id).toBe('root-1')
  })

  it('parse() sets error from parse errors', async () => {
    const errorResult: ParseResult = {
      tree: null,
      options: [],
      errors: [{ code: 'SYNTAX_ERROR', message: 'Unexpected token at line 1' }],
    }
    mockFetchResponses({ body: errorResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELEC bad sql')
    })

    expect(result.current.error).toBe('Unexpected token at line 1')
    expect(result.current.result).toEqual(errorResult)
    expect(result.current.selectedNodeId).toBeNull()
  })

  it('parse() sets error when backendUrl is null', async () => {
    const { result } = renderHook(() => useFragmentTree(null))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    expect(result.current.error).toBe('Backend not connected')
    expect(result.current.loading).toBe(false)
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('parse() sets error on network failure', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error('Network failure'))

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    expect(result.current.error).toBe('Network failure')
    expect(result.current.loading).toBe(false)
    expect(result.current.result).toBeNull()
  })

  it('parse() sets loading=true during request, then false', async () => {
    let resolveFetch: (v: unknown) => void
    globalThis.fetch = vi.fn().mockReturnValue(
      new Promise(resolve => { resolveFetch = resolve })
    )

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    // Fire parse — it won't resolve yet
    let parsePromise: Promise<void>
    act(() => {
      parsePromise = result.current.parse('SELECT 1')
    })

    // loading should be true while in-flight
    expect(result.current.loading).toBe(true)

    await act(async () => {
      resolveFetch!({
        ok: true,
        status: 200,
        json: () => Promise.resolve(sampleParseResult),
        text: () => Promise.resolve(''),
      })
      await parsePromise!
    })

    expect(result.current.loading).toBe(false)
    expect(result.current.result).toEqual(sampleParseResult)
  })

  // ── selectNode() ──────────────────────────────────────────────────────

  it('selectNode(id) updates selectedNodeId', async () => {
    mockFetchResponses(
      { body: sampleParseResult },
      { body: optionsEnvelope(sampleOptions) }  // fetchOptions triggered for root-1 on parse (already cached)
    )

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    // root-1 is selected, its options are already cached from parse
    expect(result.current.selectedNodeId).toBe('root-1')

    // Selecting same node (already cached) should not trigger extra fetch
    await act(async () => {
      result.current.selectNode('root-1')
    })

    expect(result.current.selectedNodeId).toBe('root-1')
  })

  it('selectNode(null) clears selection', async () => {
    mockFetchResponses({ body: sampleParseResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    expect(result.current.selectedNodeId).toBe('root-1')

    act(() => {
      result.current.selectNode(null)
    })

    expect(result.current.selectedNodeId).toBeNull()
    expect(result.current.selectedNode).toBeNull()
  })

  it('selectedNode is derived from result tree and selectedNodeId', async () => {
    mockFetchResponses({ body: sampleParseResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    expect(result.current.selectedNode).toEqual(rootNode)
  })

  // ── reset() ────────────────────────────────────────────────────────────

  it('reset() clears all state', async () => {
    mockFetchResponses({ body: sampleParseResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    expect(result.current.result).not.toBeNull()
    expect(result.current.selectedNodeId).toBe('root-1')

    act(() => {
      result.current.reset()
    })

    expect(result.current.result).toBeNull()
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
    expect(result.current.selectedNodeId).toBeNull()
    expect(result.current.selectedNode).toBeNull()
    expect(result.current.optionsCache).toEqual({})
    expect(result.current.dag.nodeMap.size).toBe(0)
    expect(result.current.dag.appliedRules.size).toBe(0)
  })

  // ── decomposeNode() ────────────────────────────────────────────────────

  it('decomposeNode() adds children to tree and updates DAG', async () => {
    mockFetchResponses(
      { body: sampleParseResult },   // parse
      { body: batch([childA, childB]) }      // decompose
    )

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT a, b FROM t1 JOIN t2 ON t1.id = t2.id')
    })

    expect(result.current.result!.tree!.children).toHaveLength(0)

    await act(async () => {
      await result.current.decomposeNode('root-1', 'join-split')
    })

    // Tree should now have children
    const tree = result.current.result!.tree!
    expect(tree.children).toHaveLength(2)
    expect(tree.children[0].id).toBe('child-a')
    expect(tree.children[1].id).toBe('child-b')

    // First child auto-selected
    expect(result.current.selectedNodeId).toBe('child-a')

    // DAG updated
    expect(result.current.dag.appliedRules.has('root-1::join-split')).toBe(true)
    expect(result.current.dag.decomposedChildren.has('root-1')).toBe(true)
    expect(result.current.dag.nodeMap.has('child-a')).toBe(true)
    expect(result.current.dag.nodeMap.has('child-b')).toBe(true)
  })

  it('keeps partial results retryable and replaces only the matching source/rule', async () => {
    const other = { ...childB, ruleName: 'clause-stripping' }
    const replacement = { ...childA, id: 'replacement' }
    mockFetchResponses({ body: sampleParseResult },
      { body: { ...batch([childA]), status: 'PARTIAL', issues: [{ code: 'UNRESOLVED_DEPENDENCY', message: 'candidate requires outer binding', sql: 'SELECT outer.id' }] } },
      { body: batch([other]) }, { body: batch([replacement]) })
    const { result } = renderHook(() => useFragmentTree(BACKEND))
    await act(async () => { await result.current.parse(rootNode.sqlFragment) })
    await act(async () => { await result.current.decomposeNode(rootNode.id, 'join-split') })
    expect(result.current.dag.appliedRules.has('root-1::join-split')).toBe(false)
    expect(result.current.optionsCache[rootNode.id].some(o => o.ruleName === 'join-split')).toBe(true)
    expect(result.current.error).toBeNull()
    expect(result.current.warning).toContain('UNRESOLVED_DEPENDENCY')
    await act(async () => { await result.current.decomposeNode(rootNode.id, 'clause-stripping') })
    await act(async () => { await result.current.decomposeNode(rootNode.id, 'join-split') })
    expect(result.current.result?.tree?.children.map(n => n.id).sort()).toEqual(['child-b', 'replacement'])
    expect(result.current.dag.ruleResults.get('root-1::clause-stripping')?.childIds).toEqual(['child-b'])
    expect(result.current.dag.appliedRules.has('root-1::join-split')).toBe(true)
  })

  it('rejects failed envelopes and SQL hash drift without applying graph changes', async () => {
    const forged = batch([childA]); forged.outputs[0].effect.targetSqlHash = 'forged'
    mockFetchResponses({ body: sampleParseResult }, { body: { status: 'FAILED', children: [], outputs: [], issues: [{ code: 'RULE_FAILED', message: 'retry required' }] } }, { body: forged })
    const { result } = renderHook(() => useFragmentTree(BACKEND))
    await act(async () => { await result.current.parse(rootNode.sqlFragment) })
    await act(async () => { await result.current.decomposeNode(rootNode.id, 'join-split') })
    expect(result.current.error).toContain('RULE_FAILED')
    expect(result.current.dag.ruleResults.get('root-1::join-split')?.status).toBe('FAILED')
    await act(async () => { await result.current.decomposeNode(rootNode.id, 'join-split') })
    expect(result.current.error).toContain('Invalid decomposition evidence')
    expect(result.current.result?.tree?.children).toEqual([])
  })

  it('decomposeNode() sends correct POST body and URL', async () => {
    mockFetchResponses(
      { body: sampleParseResult },
      { body: batch([childA, childB]) }
    )

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    await act(async () => {
      await result.current.decomposeNode('root-1', 'join-split')
    })

    const fetchCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    // 2nd call is the decompose
    expect(fetchCalls[1][0]).toBe(`${BACKEND}/api/sql/decompose`)
    expect(fetchCalls[1][1].method).toBe('POST')

    const body = lastFetchBody()
    expect(body).toEqual({
      sqlFragment: rootNode.sqlFragment,
      dialect: 'postgresql',
      ruleName: 'join-split',
      sourceNodeId: 'root-1',
    })
  })

  it('decomposeNode() removes applied rule from optionsCache', async () => {
    mockFetchResponses(
      { body: sampleParseResult },
      { body: batch([childA, childB]) }
    )

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    expect(result.current.optionsCache['root-1']).toHaveLength(2)

    await act(async () => {
      await result.current.decomposeNode('root-1', 'join-split')
    })

    // join-split removed from options
    expect(result.current.optionsCache['root-1']).toHaveLength(1)
    expect(result.current.optionsCache['root-1'][0].ruleName).toBe('clause-stripping')
  })

  it('decomposeNode() does nothing when backendUrl is null', async () => {
    const { result } = renderHook(() => useFragmentTree(null))

    await act(async () => {
      await result.current.decomposeNode('root-1', 'join-split')
    })

    expect(globalThis.fetch).not.toHaveBeenCalled()
    expect(result.current.result).toBeNull()
  })

  it('decomposeNode() does nothing when no tree exists', async () => {
    mockFetchResponses({ body: [] })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.decomposeNode('root-1', 'join-split')
    })

    // Only the initial fetch mock was set up; decompose should not have fired
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('decomposeNode() does nothing when node not found in tree', async () => {
    mockFetchResponses({ body: sampleParseResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    // Reset fetch count tracking by re-mocking
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(batch([childA])),
      text: () => Promise.resolve(''),
    })

    await act(async () => {
      await result.current.decomposeNode('nonexistent-id', 'join-split')
    })

    // Should not have called fetch for a nonexistent node
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('decomposeNode() handles network error gracefully', async () => {
    mockFetchResponses({ body: sampleParseResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    globalThis.fetch = vi.fn().mockRejectedValue(new Error('Decompose failed'))

    await act(async () => {
      await result.current.decomposeNode('root-1', 'join-split')
    })

    // Tree unchanged
    expect(result.current.result!.tree!.children).toHaveLength(0)
    // decomposing flag cleared
    expect(result.current.decomposing['root-1']).toBeFalsy()
  })

  it('decomposeNode() replaces children from same rule on re-apply', async () => {
    const updatedChildA = { ...childA, id: 'child-a-v2' }
    const updatedChildB = { ...childB, id: 'child-b-v2' }

    mockFetchResponses(
      { body: sampleParseResult },
      { body: batch([childA, childB]) },
      { body: batch([updatedChildA, updatedChildB]) }
    )

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    // First decompose
    await act(async () => {
      await result.current.decomposeNode('root-1', 'join-split')
    })

    expect(result.current.result!.tree!.children).toHaveLength(2)
    expect(result.current.result!.tree!.children[0].id).toBe('child-a')

    // Re-apply same rule — children should be replaced, not appended
    await act(async () => {
      await result.current.decomposeNode('root-1', 'join-split')
    })

    const children = result.current.result!.tree!.children
    expect(children).toHaveLength(2)
    expect(children[0].id).toBe('child-a-v2')
    expect(children[1].id).toBe('child-b-v2')
  })

  // ── parse() resets DAG on re-parse ─────────────────────────────────────

  it('re-parsing resets DAG and optionsCache', async () => {
    const newRoot: FragmentNode = {
      id: 'root-2',
      type: 'ORIGINAL',
      label: 'New Query',
      sqlFragment: 'SELECT 1',
      executable: true,
      executionHint: null,
      nodeCount: 0,
      ruleName: null,
      children: [],
      parentIds: [],
    }
    const newParseResult: ParseResult = {
      tree: newRoot,
      options: [],
      errors: [],
    }

    mockFetchResponses(
      { body: sampleParseResult },
      { body: batch([childA, childB]) },
      { body: newParseResult }
    )

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT a FROM t1 JOIN t2')
    })

    await act(async () => {
      await result.current.decomposeNode('root-1', 'join-split')
    })

    // DAG has entries from first parse
    expect(result.current.dag.appliedRules.has('root-1::join-split')).toBe(true)

    // Re-parse
    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    // DAG is fresh — old entries gone
    expect(result.current.dag.appliedRules.size).toBe(0)
    expect(result.current.dag.nodeMap.has('root-1')).toBe(false)
    expect(result.current.dag.nodeMap.has('root-2')).toBe(true)
    expect(result.current.selectedNodeId).toBe('root-2')
  })

  // ── clearResult alias ──────────────────────────────────────────────────

  it('clearResult is an alias for reset()', async () => {
    mockFetchResponses({ body: sampleParseResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    expect(result.current.result).not.toBeNull()

    act(() => {
      result.current.clearResult()
    })

    expect(result.current.result).toBeNull()
    expect(result.current.selectedNodeId).toBeNull()
  })

  // ── parse() with null tree in response ─────────────────────────────────

  it('parse() handles null tree gracefully', async () => {
    const nullTreeResult: ParseResult = {
      tree: null,
      options: [],
      errors: [],
    }
    mockFetchResponses({ body: nullTreeResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('')
    })

    expect(result.current.result).toEqual(nullTreeResult)
    expect(result.current.selectedNodeId).toBeNull()
    expect(result.current.error).toBeNull()
  })

  // ── parse() with empty options ─────────────────────────────────────────

  it('parse() with empty options does not populate optionsCache', async () => {
    const noOptionsResult: ParseResult = {
      tree: rootNode,
      options: [],
      errors: [],
    }
    mockFetchResponses({ body: noOptionsResult })

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    // Root id exists but no options → cache should not have an entry
    expect(result.current.optionsCache['root-1']).toBeUndefined()
  })

  // ── AbortError is silently ignored ─────────────────────────────────────

  it('parse() silently ignores AbortError', async () => {
    const abortError = new DOMException('The operation was aborted', 'AbortError')
    globalThis.fetch = vi.fn().mockRejectedValue(abortError)

    const { result } = renderHook(() => useFragmentTree(BACKEND))

    await act(async () => {
      await result.current.parse('SELECT 1')
    })

    // No error set, no result set — AbortError was swallowed
    expect(result.current.error).toBeNull()
    expect(result.current.result).toBeNull()
  })

  describe('submitUserEdit — backend integration', () => {
    it('calls POST /api/fragment/create-user-edit with the same payload as before', async () => {
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      mockFetchResponses({ body: sampleParseResult })
      await act(async () => { await result.current.parse('select 1 from t') })

      // Read the root id off the parsed tree rather than hardcoding a literal:
      // this file's local fixture root is `root-1`, and the shared
      // `node-root` fixture id belongs to other files, not to this tree.
      const rootId = result.current.result?.tree?.id
      expect(rootId).toBeTruthy()

      const fetchSpy = mockFetchResponses({ body: {
        node: { id: 'edited-1', type: 'USER_EDITED', label: 'edited', sqlFragment: 'select 1 from t where x',
                executable: true, executionHint: null, nodeCount: 0, ruleName: null, children: [], editParentId: rootId! },
        classification: 'EDIT',
        navigatedToExisting: false,
      } }) as ReturnType<typeof vi.fn>

      let outcome: Awaited<ReturnType<typeof result.current.submitUserEdit>> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit(rootId!, 'select 1 from t where x', 'add filter', 'postgresql')
      })

      const body = JSON.parse(fetchSpy.mock.calls[0][1]?.body as string)
      expect(body).toMatchObject({
        sql: 'select 1 from t where x',
        parentFragmentId: rootId,
        dialect: 'postgresql',
        description: 'add filter',
      })
      expect(Array.isArray(body.dagContextIds)).toBe(true)
      expect(outcome).toEqual({ status: 'EDIT', nodeId: 'edited-1', sql: 'select 1 from t where x' })
    })

    it('EDIT does NOT insert the new node into the tree — an edit is another SQL, not a facet', async () => {
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      mockFetchResponses({ body: sampleParseResult })
      await act(async () => { await result.current.parse('select 1 from t') })
      const rootId = result.current.result?.tree?.id
      expect(rootId).toBeTruthy()
      const before = result.current.result?.tree

      mockFetchResponses({ body: {
        node: { id: 'edited-1', type: 'USER_EDITED', label: 'edited', sqlFragment: 'select 1 from t where x',
                executable: true, executionHint: null, nodeCount: 0, ruleName: null, children: [], editParentId: rootId! },
        classification: 'EDIT',
        navigatedToExisting: false,
      } })
      await act(async () => {
        await result.current.submitUserEdit(rootId!, 'select 1 from t where x', 'add filter', 'postgresql')
      })

      expect(result.current.result?.tree).toBe(before)          // same reference: untouched
      expect(result.current.result?.tree?.children).toEqual([])
    })

    // Review Focus 4 precondition: NAVIGATE must not produce a tab. The App
    // half of that contract is asserted in Task 11's E2E (tab count unchanged).
    //
    // The parsed fixture NEEDS A CHILD, and we navigate to that child. `parse`
    // already sets `selectedNodeId` to the tree ROOT (see `parse`'s
    // `selectedNodeId: rootId ?? null`), so navigating to the root makes the
    // selection assertion below pass whether or not the NAVIGATE branch runs —
    // a vacuous pin (found by a review probe: deleting the branch's `setState`
    // left the suite green). Navigating to an in-DAG child that is NOT
    // currently selected is what makes it discriminating.
    it('NAVIGATE selects the existing facet and reports it, without touching the tree', async () => {
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      // A tree whose root has a child, so there is a second in-DAG node to
      // navigate to. `rootNode.children` is `[]` and `childA.id` is `'child-a'`.
      const parseWithChild: ParseResult = {
        ...sampleParseResult,
        tree: { ...rootNode, children: [childA] },
      }
      mockFetchResponses({ body: parseWithChild })
      await act(async () => { await result.current.parse('select 1 from t') })

      const rootId = result.current.result?.tree?.id
      expect(rootId).toBeTruthy()
      // Precondition, asserted so the pin cannot silently go vacuous again:
      // parse selected the ROOT, not the child we are about to navigate to.
      expect(result.current.selectedNodeId).toBe(rootId)
      const before = result.current.result?.tree

      // NAVIGATE returns an id that ALREADY exists in this DAG (ADR-0002) —
      // `childA` is in it via the parsed tree above.
      mockFetchResponses({ body: {
        node: { id: childA.id },
        classification: 'NAVIGATE',
        navigatedToExisting: true,
      } })
      let outcome: Awaited<ReturnType<typeof result.current.submitUserEdit>> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit(rootId!, 'select 1 from t', 'same', 'postgresql')
      })

      expect(outcome).toEqual({ status: 'NAVIGATE', nodeId: childA.id })
      expect(result.current.selectedNodeId).toBe(childA.id)
      expect(result.current.result?.tree).toBe(before)
    })

    it('a non-ok response reports FAILED with the HTTP status instead of console.warn only', async () => {
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      mockFetchResponses({ body: sampleParseResult })
      await act(async () => { await result.current.parse('select 1 from t') })
      const rootId = result.current.result?.tree?.id
      expect(rootId).toBeTruthy()

      mockFetchResponses({ status: 500, body: { message: 'boom' } })
      let outcome: Awaited<ReturnType<typeof result.current.submitUserEdit>> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit(rootId!, 'select 1 from t where x', 'x', 'postgresql')
      })
      expect(outcome).toEqual({ status: 'FAILED', reason: 'HTTP 500' })
    })

    it('a thrown network error reports FAILED with the message', async () => {
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      mockFetchResponses({ body: sampleParseResult })
      await act(async () => { await result.current.parse('select 1 from t') })
      const rootId = result.current.result?.tree?.id
      expect(rootId).toBeTruthy()

      // The file's existing style for a thrown fetch (see the network-failure
      // cases earlier in this file): replace the mock outright.
      globalThis.fetch = vi.fn().mockRejectedValue(new Error('socket closed'))
      let outcome: Awaited<ReturnType<typeof result.current.submitUserEdit>> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit(rootId!, 'select 1 from t where x', 'x', 'postgresql')
      })
      expect(outcome).toEqual({ status: 'FAILED', reason: 'socket closed' })
    })

    // Rule #2: the guard branch is a failure route too. Without a backend URL
    // (or without a parsed tree) nothing is fetched, so no other test in this
    // block can reach it — a review probe showed the suite stayed green with
    // this branch's reason string rewritten.
    it('reports FAILED without a backend URL instead of silently doing nothing', async () => {
      const { result } = renderHook(() => useFragmentTree(null))

      let outcome: Awaited<ReturnType<typeof result.current.submitUserEdit>> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit('root-1', 'select 1 from t where x', 'x', 'postgresql')
      })

      expect(outcome).toEqual({ status: 'FAILED', reason: 'Backend not connected or no parsed SQL' })
    })

    it('reports UNSUPPORTED with the backend reason instead of inventing a node (spec §5.3)', async () => {
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      mockFetchResponses({ body: sampleParseResult })
      await act(async () => { await result.current.parse('select 1 from t') })
      const rootId = result.current.result?.tree?.id
      expect(rootId).toBeTruthy()

      mockFetchResponses({ body: {
        node: null,
        classification: 'UNSUPPORTED',
        navigatedToExisting: false,
        errorReason: 'unsupported node types: SqlUpdate#a1b2c3d4e5f6',
      } })

      let outcome: Awaited<ReturnType<typeof result.current.submitUserEdit>> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit(rootId!, 'UPDATE t SET a = 1', '', 'postgresql')
      })

      expect(outcome).toEqual({
        status: 'UNSUPPORTED',
        reason: 'unsupported node types: SqlUpdate#a1b2c3d4e5f6',
      })
      expect(result.current.selectedNodeId).toBe(rootId)   // 没有导航、没有换选
      // Positive constraint only — NOT differential evidence for this fix. The
      // red run failed at `expect(outcome).toEqual({ status: 'UNSUPPORTED', … })`
      // above (the old impl threw reading `data.node.id`), so this line was never
      // reached and cannot turn red for the old behaviour. It pins "no invented
      // node" for the new code only.
      expect(result.current.result?.tree?.children).toEqual([])
    })

    it('reports UNSUPPORTED even without a reason, rather than reading node.id off null', async () => {
      // 旧实现把响应 cast 成 `node: FragmentNode & {id: string}` 并直接读
      // `data.node.id` ⇒ node 为 null 时 TypeError 被 catch 成 FAILED（把「不支持」
      // 误报成「执行失败」）。这条钉住即使 errorReason 缺失也走 UNSUPPORTED 分支。
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      mockFetchResponses({ body: sampleParseResult })
      await act(async () => { await result.current.parse('select 1 from t') })
      const rootId = result.current.result?.tree?.id

      mockFetchResponses({ body: {
        node: null, classification: 'UNSUPPORTED', navigatedToExisting: false, errorReason: null,
      } })

      let outcome: Awaited<ReturnType<typeof result.current.submitUserEdit>> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit(rootId!, 'UPDATE t SET a = 1', '', 'postgresql')
      })

      expect(outcome?.status).toBe('UNSUPPORTED')
      expect((outcome as { reason: string }).reason).not.toBe('')
    })

    it('reports UNSUPPORTED when the backend omits the null `node` key entirely (non_null wire shape)', async () => {
      // spring.jackson.default-property-inclusion: non_null ⇒ a null `node` is
      // REMOVED from the response body, not sent as `"node": null`. The two
      // mocks above write `node: null`, which is a shape the backend never
      // emits; this one models the real wire body: the key is absent.
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      mockFetchResponses({ body: sampleParseResult })
      await act(async () => { await result.current.parse('select 1 from t') })
      const rootId = result.current.result?.tree?.id
      expect(rootId).toBeTruthy()

      mockFetchResponses({ body: {
        classification: 'UNSUPPORTED',
        navigatedToExisting: false,
        errorReason: 'canonicalization failed: boom',
      } })

      let outcome: Awaited<ReturnType<typeof result.current.submitUserEdit>> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit(rootId!, 'UPDATE t SET a = 1', '', 'postgresql')
      })

      expect(outcome).toEqual({ status: 'UNSUPPORTED', reason: 'canonicalization failed: boom' })
    })

    it('EDIT from a degraded source carries sourceDegradedReason', async () => {
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      const degradedRoot: FragmentNode = {
        ...rootNode,
        canonicalizationError: 'unsupported node types: SqlExplain',
      }
      mockFetchResponses({ body: { ...sampleParseResult, tree: degradedRoot } })
      await act(async () => { await result.current.parse('select 1 from t') })
      const rootId = result.current.result?.tree?.id
      expect(rootId).toBeTruthy()

      mockFetchResponses({ body: {
        node: { id: 'edited-1', type: 'USER_EDITED', label: 'edited', sqlFragment: 'select 1 from t where x',
                executable: true, executionHint: null, nodeCount: 0, ruleName: null, children: [], editParentId: rootId! },
        classification: 'EDIT',
        navigatedToExisting: false,
      } })
      let outcome: Awaited<ReturnType<typeof result.current.submitUserEdit>> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit(rootId!, 'select 1 from t where x', 'add filter', 'postgresql')
      })

      expect(outcome?.status).toBe('EDIT')
      expect(outcome).toMatchObject({
        status: 'EDIT',
        nodeId: 'edited-1',
        sql: 'select 1 from t where x',
        sourceDegradedReason: 'unsupported node types: SqlExplain',
      })
    })

    it('EDIT from a blank-marker source carries a present-but-null reason (spec §6)', async () => {
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      const blankRoot: FragmentNode = { ...rootNode, canonicalizationError: '' }
      mockFetchResponses({ body: { ...sampleParseResult, tree: blankRoot } })
      await act(async () => { await result.current.parse('select 1 from t') })
      const rootId = result.current.result?.tree?.id
      expect(rootId).toBeTruthy()

      mockFetchResponses({ body: {
        node: { id: 'edited-1', type: 'USER_EDITED', label: 'edited', sqlFragment: 'select 1 from t where x',
                executable: true, executionHint: null, nodeCount: 0, ruleName: null, children: [], editParentId: rootId! },
        classification: 'EDIT',
        navigatedToExisting: false,
      } })
      let outcome: Awaited<ReturnType<typeof result.current.submitUserEdit>> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit(rootId!, 'select 1 from t where x', 'add filter', 'postgresql')
      })

      // Degraded (so the caller must state it) but with no reason text.
      expect(outcome?.status).toBe('EDIT')
      expect(outcome).toMatchObject({ status: 'EDIT', sourceDegradedReason: null })
    })

    it('EDIT from a clean source carries NO sourceDegradedReason key at all', async () => {
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      mockFetchResponses({ body: sampleParseResult })
      await act(async () => { await result.current.parse('select 1 from t') })
      const rootId = result.current.result?.tree?.id
      expect(rootId).toBeTruthy()

      mockFetchResponses({ body: {
        node: { id: 'edited-1', type: 'USER_EDITED', label: 'edited', sqlFragment: 'select 1 from t where x',
                executable: true, executionHint: null, nodeCount: 0, ruleName: null, children: [], editParentId: rootId! },
        classification: 'EDIT',
        navigatedToExisting: false,
      } })
      let outcome: Record<string, unknown> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit(rootId!, 'select 1 from t where x', 'add filter', 'postgresql') as Record<string, unknown>
      })

      expect(outcome).toEqual({ status: 'EDIT', nodeId: 'edited-1', sql: 'select 1 from t where x' })
      expect('sourceDegradedReason' in (outcome as object)).toBe(false)
    })

    it('EDIT with an unknown fragId does not throw and carries no reason', async () => {
      const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
      mockFetchResponses({ body: sampleParseResult })
      await act(async () => { await result.current.parse('select 1 from t') })

      mockFetchResponses({ body: {
        node: { id: 'edited-1', type: 'USER_EDITED', label: 'edited', sqlFragment: 'select 1 from t where x',
                executable: true, executionHint: null, nodeCount: 0, ruleName: null, children: [], editParentId: 'no-such-node' },
        classification: 'EDIT',
        navigatedToExisting: false,
      } })
      let outcome: Record<string, unknown> | undefined
      await act(async () => {
        outcome = await result.current.submitUserEdit('no-such-node', 'select 1 from t where x', 'x', 'postgresql') as Record<string, unknown>
      })

      expect(outcome?.status).toBe('EDIT')
      expect('sourceDegradedReason' in (outcome as object)).toBe(false)
    })
  })

  it('selectedNode falls back to the DAG nodeMap for nodes outside the projected tree (Review Focus 5)', async () => {
    const { result } = renderHook(() => useFragmentTree('http://localhost:8080'))
    mockFetchResponses({ body: sampleParseResult })
    await act(async () => { await result.current.parse('select 1 from t') })
    const rootId = result.current.result?.tree?.id
    expect(rootId).toBeTruthy()

    // Decompose first: the success path indexes the children into dag.nodeMap.
    const orphan: FragmentNode = {
      id: 'dag-only', type: 'JOIN_SPLIT', label: 'dag only',
      sqlFragment: 'select 1 from t join u on true', executable: true,
      executionHint: null, nodeCount: 0, ruleName: 'join-split', children: [],
    }
    mockFetchResponses({ body: batch([orphan]) })
    await act(async () => { await result.current.decomposeNode(rootId!, 'join-split') })
    expect(result.current.result?.tree?.children.map((c) => c.id)).toContain('dag-only')

    // Drop it from the PROJECTION while the DAG keeps it — the shape the
    // add-back projection produces: it re-emits real DAG nodes by real id
    // (2026-09-29 spec §4.3.2「新节点用真 DAG 节点、真 id」) and never writes
    // back into `result.tree` (§4.3.7). (Was cited as "spec §7.1" — that
    // section is the test-layering table and states neither half.)
    act(() => {
      result.current.setResult((prev) =>
        prev ? { ...prev, tree: prev.tree ? { ...prev.tree, children: [] } : prev.tree } : prev)
    })
    act(() => { result.current.selectNode('dag-only') })

    // Before the fix this was null and the right panel blanked out.
    expect(result.current.selectedNode?.id).toBe('dag-only')
    expect(result.current.selectedNode?.sqlFragment).toBe('select 1 from t join u on true')
  })
})
