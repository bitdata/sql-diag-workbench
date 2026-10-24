// src/renderer/hooks/__tests__/useSessionLifecycle.bootError.test.ts
//
// PR-2 (boot race): pin that booting/bootError/retryBoot from useTabs
// bubble up through useSessionLifecycle so downstream consumers
// (BootErrorBanner, useEditorActions.viewerReadOnly) can react.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useSessionLifecycle } from '../useSessionLifecycle'
import type { UseTabsApi } from '../useTabs'
import type { ParseResult } from '../../types/fragment'
import { resetMocks } from '../../test/testUtils'

/**
 * Mirror of makeTabsApi in useSessionLifecycle.test.ts, extended with the
 * three new boot fields introduced by PR-2.
 */
function makeTabsApi(overrides: Partial<UseTabsApi> = {}): UseTabsApi {
  return {
    tabs: [],
    activeSessionId: null,
    booting: false,
    bootError: null,
    bootErrorLabel: 'Could not create the first tab',
    retryBoot: vi.fn().mockResolvedValue(undefined),
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
    ...overrides,
  }
}

function setup(overrides: Record<string, unknown> = {}) {
  const parse = vi.fn()
  const selectNode = vi.fn()
  const setResult = vi.fn()
  const tabsApi = makeTabsApi(overrides.tabsApi as Partial<UseTabsApi> | undefined)

  const params = {
    backendUrl: 'http://localhost:8080' as string | null,
    tabsApi,
    activeSessionLoading: false,
    currentSql: '',
    result: null as ParseResult | null,
    selectedNodeId: null as string | null,
    dialect: 'postgresql' as const,
    connectionId: null as string | null,
    setResult,
    parse,
    selectNode,
  }

  const utils = renderHook(() => useSessionLifecycle(params))
  return { ...utils, parse, selectNode, setResult, params, tabsApi }
}

describe('useSessionLifecycle — bootError forwarding (PR-2)', () => {
  beforeEach(() => {
    resetMocks()
  })

  afterEach(() => {
    resetMocks()
  })

  it('exposes booting, bootError, and retryBoot from the tabsApi', () => {
    const retryBoot = vi.fn().mockResolvedValue(undefined)
    const { result } = setup({
      tabsApi: { booting: true, bootError: 'simulated failure', retryBoot },
    })

    expect(result.current.booting).toBe(true)
    expect(result.current.bootError).toBe('simulated failure')
    expect(result.current.retryBoot).toBe(retryBoot)
  })

  it('returns defaults (booting=false, bootError=null) when tabsApi has no boot error', () => {
    const { result } = setup()

    expect(result.current.booting).toBe(false)
    expect(result.current.bootError).toBeNull()
    expect(typeof result.current.retryBoot).toBe('function')
  })
})
