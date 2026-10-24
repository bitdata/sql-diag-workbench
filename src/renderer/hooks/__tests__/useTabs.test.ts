// src/renderer/hooks/__tests__/useTabs.test.ts
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useTabs } from '../useTabs'
import { mockFetchResponses, lastFetchBody, resetMocks } from '../../test/testUtils'

const BACKEND = 'http://localhost:8080'
const UPDATED_AT = '2026-01-01T00:00:00.000Z'

/** Build a minimal backend "create session" response body for a given id. */
function createdSession(id: string, sqlText = '', name = id === 'sess-2' ? 'Untitled 2' : 'Untitled 1') {
  return { id, name, sqlText, dialect: 'postgresql', updatedAt: UPDATED_AT }
}

/** Render useTabs with a backend URL. localStorage is isolated per-test in beforeEach. */
function setup(backendUrl: string | null = BACKEND, dialect?: string) {
  return renderHook(() => useTabs(backendUrl, dialect ? { dialect } : {}))
}

/**
 * In-memory Storage stub. The jsdom env in this project does not expose a
 * working `localStorage.clear`, so we replace `window.localStorage` with a
 * fresh stub per test. This both provides the full Storage surface the hook's
 * `useLocalStorage` dependency relies on and guarantees zero state leakage
 * between cases.
 */
function makeMemoryStorage(): Storage {
  let store: Record<string, string> = {}
  return {
    getItem: (k: string) => (k in store ? store[k] : null),
    setItem: (k: string, v: string) => {
      store[k] = String(v)
    },
    removeItem: (k: string) => {
      delete store[k]
    },
    clear: () => {
      store = {}
    },
    key: (i: number) => Object.keys(store)[i] ?? null,
    get length() {
      return Object.keys(store).length
    },
  }
}

describe('useTabs', () => {
  beforeEach(() => {
    // useTabs persists to localStorage under "tabs.v1" — isolate every test.
    Object.defineProperty(window, 'localStorage', {
      value: makeMemoryStorage(),
      configurable: true,
    })
    resetMocks()
  })

  afterEach(() => {
    resetMocks()
  })

  it('starts with no tabs, no active tab, and a null workspace', () => {
    const { result } = setup()

    expect(result.current.tabs).toEqual([])
    expect(result.current.activeSessionId).toBeNull()
    expect(result.current.activeWorkspace).toBeNull()
    expect(typeof result.current.createTab).toBe('function')
    expect(typeof result.current.closeTab).toBe('function')
    expect(typeof result.current.openInNewTab).toBe('function')
  })

  it('createTab() POSTs a new session, appends a tab, and makes it active', async () => {
    mockFetchResponses({ body: createdSession('sess-1') })

    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })

    expect(result.current.tabs).toHaveLength(1)
    const tab = result.current.tabs[0]
    expect(tab.sessionId).toBe('sess-1')
    expect(tab.name).toBe('Untitled 1')
    expect(tab.isDirty).toBe(false)
    expect(result.current.activeSessionId).toBe('sess-1')

    // Workspace is seeded with defaults.
    expect(result.current.activeWorkspace).toMatchObject({
      sessionId: 'sess-1',
      tabName: 'Untitled 1',
      sql: '',
      isDirty: false,
      dialect: 'postgresql',
      workspaceMode: 'data',
      selectedNodeId: null,
    })

    // POST body sent to the backend.
    const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    expect(calls[0][0]).toBe(`${BACKEND}/api/sessions`)
    expect(calls[0][1].method).toBe('POST')
    expect(lastFetchBody()).toMatchObject({
      name: 'Untitled 1',
      sqlText: '',
      dialect: 'postgresql',
      connectionId: null,
    })
  })

  it('createTab() is a no-op when backendUrl is null', async () => {
    const { result } = setup(null)

    await act(async () => {
      await result.current.createTab()
    })

    expect(result.current.tabs).toEqual([])
    expect(result.current.activeSessionId).toBeNull()
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })

  it('createTab() increments the default name for each new tab', async () => {
    mockFetchResponses(
      { body: createdSession('sess-1') },
      { body: createdSession('sess-2') }
    )

    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })
    await act(async () => {
      await result.current.createTab()
    })

    expect(result.current.tabs.map((t) => t.name)).toEqual(['Untitled 1', 'Untitled 2'])
    expect(result.current.tabs.map((t) => t.sessionId)).toEqual(['sess-1', 'sess-2'])
    // The most recently created tab is active.
    expect(result.current.activeSessionId).toBe('sess-2')
  })

  it('switchTab() changes the active tab and its derived workspace', async () => {
    mockFetchResponses(
      { body: createdSession('sess-1') },
      { body: createdSession('sess-2') }
    )

    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })
    await act(async () => {
      await result.current.createTab()
    })
    expect(result.current.activeSessionId).toBe('sess-2')

    act(() => {
      result.current.switchTab('sess-1')
    })

    expect(result.current.activeSessionId).toBe('sess-1')
    expect(result.current.activeWorkspace?.sessionId).toBe('sess-1')
  })

  it('closeTab() on the active tab selects a remaining neighbor', async () => {
    mockFetchResponses(
      { body: createdSession('sess-1') },
      { body: createdSession('sess-2') },
      { body: {} } // DELETE response (body unused)
    )

    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })
    await act(async () => {
      await result.current.createTab()
    })
    expect(result.current.activeSessionId).toBe('sess-2')

    await act(async () => {
      await result.current.closeTab('sess-2')
    })

    expect(result.current.tabs.map((t) => t.sessionId)).toEqual(['sess-1'])
    expect(result.current.activeSessionId).toBe('sess-1')
    expect(result.current.activeWorkspace?.sessionId).toBe('sess-1')
  })

  it('closeTab() on the only tab clears the active tab and workspace', async () => {
    mockFetchResponses(
      { body: createdSession('sess-1') },
      { body: {} } // DELETE
    )

    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })

    await act(async () => {
      await result.current.closeTab('sess-1')
    })

    expect(result.current.tabs).toEqual([])
    expect(result.current.activeSessionId).toBeNull()
    expect(result.current.activeWorkspace).toBeNull()
  })

  it('closeTab() refuses a dirty tab without force, but removes it with force=true', async () => {
    mockFetchResponses(
      { body: createdSession('sess-1') },
      { body: {} } // DELETE (only consumed by the forced close)
    )

    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })

    act(() => {
      result.current.markDirty('sess-1')
    })
    expect(result.current.tabs[0].isDirty).toBe(true)

    // Without force — refused, no backend DELETE issued.
    await act(async () => {
      await result.current.closeTab('sess-1')
    })
    expect(result.current.tabs).toHaveLength(1)
    const callsAfterRefuse = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    expect(callsAfterRefuse.every((c) => (c[1] as RequestInit)?.method !== 'DELETE')).toBe(true)

    // With force — removed and DELETE sent.
    await act(async () => {
      await result.current.closeTab('sess-1', { force: true })
    })
    expect(result.current.tabs).toEqual([])
    const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    expect(calls.some((c) => (c[1] as RequestInit)?.method === 'DELETE')).toBe(true)
  })

  it('closeTab() on a non-active tab leaves the active tab unchanged', async () => {
    mockFetchResponses(
      { body: createdSession('sess-1') },
      { body: createdSession('sess-2') },
      { body: {} } // DELETE
    )

    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })
    await act(async () => {
      await result.current.createTab()
    })
    expect(result.current.activeSessionId).toBe('sess-2')

    await act(async () => {
      await result.current.closeTab('sess-1')
    })

    expect(result.current.tabs.map((t) => t.sessionId)).toEqual(['sess-2'])
    expect(result.current.activeSessionId).toBe('sess-2')
  })

  it('renameTab() updates the tab name locally and PUTs to the backend', async () => {
    mockFetchResponses(
      { body: createdSession('sess-1') },
      { body: {} } // PUT response (unused)
    )

    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })

    await act(async () => {
      await result.current.renameTab('sess-1', 'My Query')
    })

    expect(result.current.tabs[0].name).toBe('My Query')

    const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    const put = calls[calls.length - 1]
    expect(put[0]).toBe(`${BACKEND}/api/sessions/sess-1`)
    expect((put[1] as RequestInit).method).toBe('PUT')
    expect(lastFetchBody()).toEqual({ name: 'My Query' })
  })

  it('markDirty()/markClean() toggle the dirty flag', async () => {
    mockFetchResponses({ body: createdSession('sess-1') })

    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })
    expect(result.current.tabs[0].isDirty).toBe(false)

    act(() => {
      result.current.markDirty('sess-1')
    })
    expect(result.current.tabs[0].isDirty).toBe(true)

    act(() => {
      result.current.markClean('sess-1')
    })
    expect(result.current.tabs[0].isDirty).toBe(false)
    expect(typeof result.current.tabs[0].lastSavedAt).toBe('number')
  })

  it('updateWorkspace() merges the partial and syncs top-level fields', async () => {
    mockFetchResponses({ body: createdSession('sess-1') })

    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })

    act(() => {
      result.current.updateWorkspace('sess-1', {
        sql: 'SELECT 1',
        tabName: 'Renamed',
        isDirty: true,
      })
    })

    const tab = result.current.tabs[0]
    expect(tab.workspace.sql).toBe('SELECT 1')
    expect(tab.workspace.tabName).toBe('Renamed')
    expect(tab.workspace.isDirty).toBe(true)
    // Top-level fields synced from the workspace update.
    expect(tab.name).toBe('Renamed')
    expect(tab.isDirty).toBe(true)
    // Untouched workspace fields preserved.
    expect(tab.workspace.dialect).toBe('postgresql')
    expect(tab.workspace.workspaceMode).toBe('data')
  })

  it('getMode() defaults to "data" and setMode() persists per tab only', async () => {
    mockFetchResponses(
      { body: createdSession('sess-1') },
      { body: createdSession('sess-2') }
    )

    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })
    await act(async () => {
      await result.current.createTab()
    })

    expect(result.current.getMode('sess-1')).toBe('data')
    expect(result.current.getMode('sess-2')).toBe('data')

    act(() => {
      result.current.setMode('sess-1', 'performance')
    })

    expect(result.current.getMode('sess-1')).toBe('performance')
    // Other tab unaffected.
    expect(result.current.getMode('sess-2')).toBe('data')
  })

  it('openInNewTab() creates a tab seeded with the given SQL and activates it', async () => {
    mockFetchResponses({ body: createdSession('sess-9', 'SELECT * FROM users', 'From history') })

    const { result } = setup()

    await act(async () => {
      await result.current.openInNewTab('SELECT * FROM users')
    })

    expect(result.current.tabs).toHaveLength(1)
    expect(result.current.activeSessionId).toBe('sess-9')
    expect(result.current.activeWorkspace?.sql).toBe('SELECT * FROM users')

    const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
    expect(calls[0][0]).toBe(`${BACKEND}/api/sessions`)
    expect(lastFetchBody()).toMatchObject({
      name: 'From history',
      sqlText: 'SELECT * FROM users',
      dialect: 'postgresql',
    })
  })

  it('openInNewTab(sql, { name }) names the tab and returns its session id', async () => {
    const { result } = renderHook(() => useTabs('http://localhost:8080'))
    mockFetchResponses({ body: { id: 'sess-new', name: 'Edited filter', sqlText: 'select 1 from t where x', dialect: 'postgresql', connectionId: null,
                                 fragmentTreeJson: null, updatedAt: new Date().toISOString() } })

    let sessionId: string | null = null
    await act(async () => {
      sessionId = await result.current.openInNewTab('select 1 from t where x', { name: 'Edited filter' })
    })

    expect(sessionId).toBe('sess-new')
    expect(result.current.tabs.map((t) => t.name)).toContain('Edited filter')
    expect(result.current.activeSessionId).toBe('sess-new')
  })

  // NOT a re-statement of the pre-existing 'creates a tab seeded with the given
  // SQL and activates it' case: that one calls `openInNewTab` with no options,
  // so it stays green whether or not the new `opts?.name?.trim() ||` is there
  // (review probe: dropping `.trim()` left the whole suite green). A
  // whitespace-only name is the input that discriminates — trimmed to `''` it
  // must fall back to the default; untrimmed it is truthy and leaks through.
  it('openInNewTab falls back to "From history" for a whitespace-only name', async () => {
    const { result } = renderHook(() => useTabs('http://localhost:8080'))
    const fetchSpy = mockFetchResponses({ body: { id: 'sess-ws', name: 'From history', sqlText: 'select 1', dialect: 'postgresql', connectionId: null,
                                                 fragmentTreeJson: null, updatedAt: new Date().toISOString() } }) as ReturnType<typeof vi.fn>
    await act(async () => { await result.current.openInNewTab('select 1', { name: '   ' }) })
    expect(JSON.parse(fetchSpy.mock.calls[0][1]?.body as string).name).toBe('From history')
  })

  it('a new tab workspace starts in input mode (spec §5.2)', async () => {
    mockFetchResponses({ body: createdSession('s-new') })
    const { result } = setup()

    await act(async () => {
      await result.current.createTab()
    })

    expect(result.current.activeWorkspace?.sessionId).toBe('s-new')
    expect(result.current.activeWorkspace?.viewerMode).toBe('input')
  })
  it('does not mark an unchanged selection or viewer mode as a pending save', async () => {
    mockFetchResponses({ body: createdSession('sess-noop') })
    const { result } = setup()
    await act(async () => { await result.current.createTab() })
    act(() => result.current.updateWorkspace('sess-noop', { viewerMode: 'input', selectedNodeId: null }))
    expect(result.current.tabs[0].isDirty).toBe(false)
    expect(result.current.activeWorkspace?.syncStatus).toBe('saved')
  })

  it('a late close response preserves edits and activation of another tab', async () => {
    mockFetchResponses({ body: createdSession('a') }, { body: createdSession('b') })
    const { result } = setup()
    await act(async () => { await result.current.createTab(); await result.current.createTab() })
    act(() => result.current.switchTab('a'))
    let release!: (response: Response) => void
    vi.mocked(globalThis.fetch).mockImplementationOnce(() => new Promise(resolve => { release = resolve }))
    let closing!: Promise<void>
    act(() => { closing = result.current.closeTab('a', { force: true }) })
    act(() => { result.current.switchTab('b'); result.current.updateWorkspace('b', { sql: "SELECT 'F'" }) })
    await act(async () => { release(new Response(null, { status: 204 })); await closing })
    expect(result.current.activeSessionId).toBe('b')
    expect(result.current.activeWorkspace?.sql).toBe("SELECT 'F'")
  })

})
