// src/renderer/hooks/__tests__/useEditorActions.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { renderHook, act } from '@testing-library/react'
import { useEditorActions } from '../useEditorActions'
import type { FragmentNode, ParseResult } from '../../types/fragment'
import type { UseTabsApi } from '../useTabs'
import type { SessionWorkspaceState } from '../../types/session'
import type { DiagnosticsHook } from '../useDiagnostics'

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

function makeTabsApi(sql = 'SELECT 1', onChange?: () => void): UseTabsApi {
  // viewerMode 迁入 workspace（spec 2026-10-01 §5.2）后，本夹具必须**有状态**：
  // 生产代码通过 tabsApi.updateWorkspace 写模式，再读 tabsApi.activeWorkspace 得到
  // 新值。静态 mock 会让「handleNodeSelect → 模式变 browse」这类断言恒假。
  //
  // 仅「有状态」还不够：真实 useTabs 把 workspace 放在 React state 里，写入会触发
  // 消费者重渲染；纯变量合并对被测钩子不可见（重渲染前 viewerMode 仍是旧值）。所以
  // 写入后回调 `onChange`（由 setup 接到一个 setState 上），与真实 useTabs 同语义。
  // 若照计划原文只做变量合并，「handleNodeSelect sets viewerMode to browse」这条
  // 既有用例会转红（实测：Received 'input'）—— 见 task-11-report。
  let workspace: SessionWorkspaceState = {
    sessionId: 'tab-1',
    hydration: 'ready',
    tabName: 'Test',
    sql,
    isDirty: false,
    lastSavedAt: null,
    connectionId: null,
    dialect: 'postgresql',
    fragmentTreeJson: null,
    selectedNodeId: null,
    workspaceMode: 'data',
    viewerMode: 'input',
  }
  return {
    tabs: [],
    activeSessionId: 'tab-1',
    createTab: vi.fn(),
    closeTab: vi.fn(),
    renameTab: vi.fn(),
    switchTab: vi.fn(),
    markDirty: vi.fn(),
    markClean: vi.fn(),
    openInNewTab: vi.fn(),
    getMode: vi.fn().mockReturnValue('data'),
    setMode: vi.fn(),
    get activeWorkspace() {
      return workspace
    },
    updateWorkspace: vi.fn((_sessionId: string, partial: Partial<SessionWorkspaceState>) => {
      workspace = { ...workspace, ...partial }
      onChange?.()
    }),
    booting: false,
    bootError: null,
    bootErrorLabel: 'Could not create the first tab',
    retryBoot: vi.fn(),
  }
}

function makeExecution() {
  return {
    loading: false,
    execute: vi.fn(),
    analyze: vi.fn(),
    clearResult: vi.fn(),
    reset: vi.fn(),
  }
}

function makeDiagnostics(): DiagnosticsHook {
  return {
    findings: [],
    loading: false,
    error: null,
    verifyingIds: new Set(),
    verifyErrors: {},
    analyze: vi.fn(),
    clear: vi.fn(),
    verify: vi.fn(),
    clearVerifyError: vi.fn(),
  }
}

function setup(overrides: Record<string, unknown> = {}) {
  const parse = vi.fn()
  const clearParseResult = vi.fn()
  const fetchPositions = vi.fn()
  const clearPositions = vi.fn()
  const selectNode = vi.fn()
  // Force a re-render on every workspace write — the fixture stands in for the
  // real useTabs, whose workspace lives in React state. `rerender` is assigned
  // during the harness render below, before any mutation can happen.
  let rerender: () => void = () => {}
  const tabsApi = (overrides.tabsApi as UseTabsApi | undefined) ?? makeTabsApi('SELECT 1', () => rerender())
  const execution = makeExecution()
  const diagnostics = makeDiagnostics()
  const selectedNode = makeNode('node-1')

  const params = {
    tabsApi,
    execution,
    diagnostics,
    selectedNode,
    selectedNodeId: 'node-1' as string | null,
    selectedConnectionId: 'conn-1' as string | null,
    currentMode: 'data' as const,
    dialect: 'postgresql' as const,
    parse,
    clearParseResult,
    fetchPositions,
    clearPositions,
    selectNode,
    ...overrides,
  }

  const utils = renderHook(() => {
    const [, setTick] = useState(0)
    rerender = () => setTick((n) => n + 1)
    return useEditorActions(params)
  })
  return {
    ...utils,
    parse,
    clearParseResult,
    fetchPositions,
    clearPositions,
    selectNode,
    tabsApi,
    execution,
    diagnostics,
  }
}

describe('useEditorActions', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('initial viewerMode is "input", currentSql comes from workspace', () => {
    const { result } = setup()
    expect(result.current.viewerMode).toBe('input')
    expect(result.current.currentSql).toBe('SELECT 1')
    expect(result.current.viewerSql).toBe('SELECT 1')
    expect(result.current.viewerReadOnly).toBe(false)
  })

  it('handleNodeSelect sets viewerMode to "browse" and viewerSql to node SQL', () => {
    const { result, selectNode } = setup()

    act(() => {
      result.current.handleNodeSelect('node-1')
    })

    expect(selectNode).toHaveBeenCalledWith('node-1')
    expect(result.current.viewerMode).toBe('browse')
    expect(result.current.viewerSql).toBe('SELECT 1 -- node-1')
    expect(result.current.viewerReadOnly).toBe(true)
  })

  it('handleNodeSelect writes browse mode into the active tab workspace', () => {
    const { result, tabsApi } = setup()

    act(() => {
      result.current.handleNodeSelect('node-1')
    })

    // 模式写入的是 workspace（per-tab），不是模块级 state。
    expect(tabsApi.updateWorkspace).toHaveBeenCalledWith('tab-1', { viewerMode: 'browse' })
  })

  it('reads viewerMode from the workspace, not from component state', () => {
    // 初始 workspace.viewerMode = 'browse' ⇒ 钩子直接呈现 browse，且编辑器只读。
    // 旧实现下这条会红：viewerMode 是 useState('input')，与 workspace 无关。
    const { result } = setup({
      tabsApi: { ...makeTabsApi(), activeWorkspace: { ...makeTabsApi().activeWorkspace!, viewerMode: 'browse' as const } },
    })
    expect(result.current.viewerMode).toBe('browse')
    expect(result.current.viewerReadOnly).toBe(true)
  })

  it('handleParse calls parse, clearResult, fetchPositions', () => {
    const { result, parse, fetchPositions, execution } = setup()

    act(() => {
      result.current.handleParse()
    })

    expect(parse).toHaveBeenCalledWith('SELECT 1', 'postgresql')
    expect(execution.clearResult).toHaveBeenCalledTimes(1)
    expect(fetchPositions).toHaveBeenCalledWith('SELECT 1', 'postgresql')
  })

  it('handleRunCurrent calls execution.execute in data mode', () => {
    const { result, execution } = setup()

    act(() => {
      result.current.handleRunCurrent()
    })

    expect(execution.execute).toHaveBeenCalledWith('SELECT 1', 'conn-1')
    expect(execution.analyze).not.toHaveBeenCalled()
  })

  it('handleRunCurrent calls execution.analyze in performance mode', () => {
    const { result, execution } = setup({ currentMode: 'performance' })

    act(() => {
      result.current.handleRunCurrent()
    })

    expect(execution.analyze).toHaveBeenCalledWith('SELECT 1', 'conn-1')
    expect(execution.execute).not.toHaveBeenCalled()
  })

  it('executes the visible fragment in browse mode', () => {
    const { result, execution } = setup()
    act(() => result.current.handleNodeSelect('node-1'))
    act(() => result.current.handleRunCurrent())
    expect(execution.execute).toHaveBeenCalledWith('SELECT 1 -- node-1', 'conn-1')
  })

  it('executes edited input and creates a new tab without clearing it', () => {
    const { result, execution, tabsApi } = setup()
    act(() => result.current.handleSqlChange('SELECT 42'))
    act(() => result.current.handleRunCurrent())
    expect(execution.execute).toHaveBeenCalledWith('SELECT 42', 'conn-1')
    act(() => result.current.handleNewSession())
    expect(tabsApi.createTab).toHaveBeenCalledOnce()
    expect(result.current.currentSql).toBe('SELECT 42')
  })

  it('handleVerifyFinding forwards to diagnostics.verify', () => {
    const { result, diagnostics } = setup()

    act(() => {
      result.current.handleVerifyFinding('f1', 'SELECT 1')
    })

    expect(diagnostics.verify).toHaveBeenCalledWith('f1', 'SELECT 1', 'conn-1')
  })
})
