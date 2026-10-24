// src/renderer/hooks/__tests__/useSessionLifecycle.test.ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useSessionLifecycle } from '../useSessionLifecycle'
import type { ParseResult, FragmentNode } from '../../types/fragment'
import type { UseTabsApi } from '../useTabs'
import { resetMocks } from '../../test/testUtils'

function makeNode(id: string): FragmentNode {
  return {
    id,
    type: 'ORIGINAL',
    label: id,
    sqlFragment: `SELECT 1 -- ${id}`,
    executable: true,
    executionHint: null,
    nodeCount: 0,
    ruleName: null,
    children: [],
    parentIds: [],
  }
}

function makeTabsApi(overrides: Partial<UseTabsApi> = {}): UseTabsApi {
  return {
    tabs: [],
    activeSessionId: null,
    createTab: vi.fn().mockResolvedValue(undefined),
    closeTab: vi.fn(),
    renameTab: vi.fn(),
    switchTab: vi.fn(),
    markDirty: vi.fn(),
    markClean: vi.fn(),
    openInNewTab: vi.fn(),
    getMode: vi.fn().mockReturnValue('data'),
    setMode: vi.fn(),
    activeWorkspace: null,
    updateWorkspace: vi.fn(),
    // PR-2 (boot race): tests must supply boot state; default = not booting
    // so the existing assertions on session lifecycle keep their meaning.
    booting: false,
    bootError: null,
    bootErrorLabel: 'Could not create the first tab',
    retryBoot: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}

function makeResult(tree: FragmentNode): ParseResult {
  return { tree, options: [], errors: [] }
}

function setup(overrides: Record<string, unknown> = {}) {
  const parse = vi.fn()
  const selectNode = vi.fn()
  const setResult = vi.fn()

  const params = {
    backendUrl: 'http://localhost:8080' as string | null,
    tabsApi: makeTabsApi(),
    activeSessionLoading: false,
    currentSql: '',
    result: null as ParseResult | null,
    selectedNodeId: null as string | null,
    dialect: 'postgresql' as const,
    connectionId: null as string | null,
    setResult,
    parse,
    selectNode,
    ...overrides,
  }

  const utils = renderHook(() => useSessionLifecycle(params))
  return { ...utils, parse, selectNode, setResult, params }
}

describe('useSessionLifecycle', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    resetMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('auto-creates first tab when tabs empty and hydrate done', () => {
    const createTab = vi.fn().mockResolvedValue(undefined)
    setup({
      tabsApi: makeTabsApi({ tabs: [], activeSessionId: null, createTab }),
      activeSessionLoading: false,
    })

    expect(createTab).toHaveBeenCalledTimes(1)
  })

  it('does not auto-create tab when activeSessionLoading is true', () => {
    const createTab = vi.fn().mockResolvedValue(undefined)
    setup({
      tabsApi: makeTabsApi({ tabs: [], activeSessionId: null, createTab }),
      activeSessionLoading: true,
    })

    expect(createTab).not.toHaveBeenCalled()
  })

  it('does not reparse or overwrite a tree already restored by the workspace owner', () => {
    const tree = makeNode('root')
    const workspace = {
      sessionId: 'tab-1',
      tabName: 'Test',
      sql: 'SELECT 42',
      isDirty: false,
      lastSavedAt: null,
      connectionId: null,
      dialect: 'postgresql' as const,
      fragmentTreeJson: JSON.stringify(tree),
      selectedNodeId: 'root',
      workspaceMode: 'data' as const,
      viewerMode: 'input' as const,
    }
    const setResult = vi.fn()

    const { parse } = setup({
      tabsApi: makeTabsApi({ activeWorkspace: workspace, activeSessionId: 'tab-1' }),
      result: makeResult(tree),
      setResult,
    })

    expect(setResult).not.toHaveBeenCalled()
    expect(parse).not.toHaveBeenCalled()
  })

  it('does not create a second backend session for a tab that already has one', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ id: 'sess-new' }),
    })
    globalThis.fetch = fetchMock

    const tree = makeNode('root')
    const updateWorkspace = vi.fn()
    const tabsApi = makeTabsApi({
      activeSessionId: 'tab-1',
      activeWorkspace: {
        sessionId: 'tab-1',
        tabName: 'Test',
        sql: 'SELECT 1',
        isDirty: false,
        lastSavedAt: null,
        connectionId: null,
        dialect: 'postgresql' as const,
        fragmentTreeJson: null,
        selectedNodeId: null,
        workspaceMode: 'data' as const,
        viewerMode: 'input' as const,
      },
      updateWorkspace,
    })

    setup({
      tabsApi,
      result: makeResult(tree),
      currentSql: 'SELECT 1',
      connectionId: 'conn-1',
    })

    // Let the async effect settle
    await vi.advanceTimersByTimeAsync(10)

    expect(fetchMock).not.toHaveBeenCalledWith(
      'http://localhost:8080/api/sessions',
      expect.objectContaining({ method: 'POST' })
    )
  })

  it('does nothing when backendUrl is null', () => {
    const createTab = vi.fn()
    setup({
      backendUrl: null,
      tabsApi: makeTabsApi({ tabs: [], activeSessionId: null, createTab }),
    })

    expect(createTab).not.toHaveBeenCalled()
  })
})
