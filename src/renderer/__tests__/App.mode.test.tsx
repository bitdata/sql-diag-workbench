import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { buildMockTree, mockConnections, mockPlanNode } from '../test/fixtures'
import { server } from '../test/msw-server'

// Monaco-backed components are irrelevant to mode wiring; keep them out of jsdom.
vi.mock('../components/Editor/FragmentEditor', () => ({ __esModule: true, default: () => null }))
vi.mock('../components/Editor/SqlViewer', () => ({ __esModule: true, default: () => null }))

/** In-memory Storage stub — jsdom's localStorage has no working clear()
 * in this project, so tests swap in a fresh stub per case (same approach
 * as useTabs.test.ts / useLocalStorage.test.ts). */
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

const SESSION = {
  id: 'sess-1',
  name: 'Test tab',
  connectionId: 'conn-1',
  dialect: 'postgresql',
  sqlText: 'SELECT * FROM orders JOIN customers ON orders.customer_id = customers.id',
  fragmentTreeJson: null,
  layout: null,
  treePanelVisible: true,
  createdAt: '2026-08-27T00:00:00Z',
  updatedAt: '2026-08-27T00:00:00Z',
}

const COMPLETED_PLAN = {
  executionId: 'exec-1',
  status: 'COMPLETED',
  columns: null,
  rows: null,
  rowCount: 0,
  timing: { durationMs: 5 },
  plan: mockPlanNode,
  planError: null,
  error: null,
}

const COMPLETED_ROWS = {
  executionId: 'exec-1',
  status: 'COMPLETED',
  columns: [{ name: 'id', type: 'integer' }],
  rows: [[1]],
  rowCount: 1,
  timing: { durationMs: 1 },
  plan: null,
  planError: null,
  error: null,
}

/** Boot-time mocks: one hydrated tab whose SQL auto-parses on restore
 * (useSessionLifecycle Effect 2), so the root node is selected and the
 * Execute/Analyze buttons become enabled without touching Monaco. */
function bootMocks() {
  server.use(
    http.get('*/api/connections', () => HttpResponse.json(mockConnections)),
    http.get('*/api/history', () => HttpResponse.json([])),
    http.get('*/api/sessions', () => HttpResponse.json([SESSION])),
    http.get('*/api/sessions/:id', () => HttpResponse.json(SESSION)),
    http.put('*/api/sessions/:id', () => HttpResponse.json({})),
    http.post('*/api/sessions', () => HttpResponse.json(SESSION)),
    http.post('*/api/sql/parse', () =>
      HttpResponse.json({ tree: buildMockTree(), options: [], errors: [] }),
    ),
    http.post('*/api/sql/positions', () => HttpResponse.json({ nodes: [] })),
    http.post('*/api/sql/decompose-options', () => HttpResponse.json({options:[],scan:{status:'COMPLETE',issues:[]}})),
  )
}

/** Performance mode's run control is a split button: clicking the trigger
 * opens a Radix dropdown, and the analyze POST only fires from a menu item. */
async function runAnalyze(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /Analyze/ }))
  await user.click(await screen.findByRole('menuitem', { name: /Estimated Plan/ }))
}

async function renderBootedApp() {
  localStorage.setItem(
    'tabs.v1',
    JSON.stringify({
      tabSessionIds: ['sess-1'],
      activeSessionId: 'sess-1',
      modeBySession: {},
    }),
  )
  localStorage.setItem(
    'sql-workbench-selected-connection',
    JSON.stringify({ selectedConnectionId: 'conn-1' }),
  )
  render(<App />)
  // Gate on the run button being enabled: tab hydrated, SQL auto-parsed,
  // root node selected, connection resolved.
  await waitFor(
    () => expect(screen.getByRole('button', { name: /Execute|Analyze/ })).toBeEnabled(),
    { timeout: 4000 },
  )
}

describe('App workspace mode wiring', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'localStorage', {
      value: makeMemoryStorage(),
      configurable: true,
    })
    bootMocks()
  })

  it(
    'filters right-area tabs by mode and swaps the run button',
    { timeout: 15000 },
    async () => {
      await renderBootedApp()

      // data mode (default): Results + Row Trace visible, Plan/Diagnostics hidden
      expect(await screen.findByRole('tab', { name: 'Results' })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: 'Row Trace' })).toBeInTheDocument()
      expect(screen.queryByRole('tab', { name: 'Plan' })).toBeNull()
      expect(screen.queryByRole('tab', { name: 'Diagnostics' })).toBeNull()
      expect(screen.getByRole('button', { name: /Execute/ })).toBeInTheDocument()

      fireEvent.click(screen.getByRole('radio', { name: 'Performance' }))

      // performance mode: Plan + Diagnostics replace Results + Row Trace
      expect(await screen.findByRole('tab', { name: 'Plan' })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: 'Diagnostics' })).toBeInTheDocument()
      expect(screen.queryByRole('tab', { name: 'Results' })).toBeNull()
      expect(screen.queryByRole('tab', { name: 'Row Trace' })).toBeNull()
      expect(screen.getByRole('button', { name: /Analyze/ })).toBeInTheDocument()
    },
  )

  it(
    'dispatches analyze (not execute) in performance mode',
    { timeout: 15000 },
    async () => {
      let analyzePosts = 0
      let executePosts = 0
      server.use(
        http.post('*/api/execution/analyze', () => {
          analyzePosts++
          return HttpResponse.json({ executionId: 'exec-1', status: 'QUEUED' })
        }),
        http.post('*/api/execution/execute', () => {
          executePosts++
          return HttpResponse.json({ executionId: 'exec-1', status: 'QUEUED' })
        }),
        http.get('*/api/execution/execute/:id', () => HttpResponse.json(COMPLETED_PLAN)),
      )

      const user = userEvent.setup()
      await renderBootedApp()
      fireEvent.click(screen.getByRole('radio', { name: 'Performance' }))
      await runAnalyze(user)

      await waitFor(() => expect(analyzePosts).toBe(1), { timeout: 4000 })
      expect(executePosts).toBe(0)
    },
  )

  it(
    'dispatches execute (not analyze) in data mode',
    { timeout: 15000 },
    async () => {
      let analyzePosts = 0
      let executePosts = 0
      server.use(
        http.post('*/api/execution/analyze', () => {
          analyzePosts++
          return HttpResponse.json({ executionId: 'exec-1', status: 'QUEUED' })
        }),
        http.post('*/api/execution/execute', () => {
          executePosts++
          return HttpResponse.json({ executionId: 'exec-1', status: 'QUEUED' })
        }),
        http.get('*/api/execution/execute/:id', () => HttpResponse.json(COMPLETED_ROWS)),
      )

      await renderBootedApp()
      fireEvent.click(screen.getByRole('button', { name: /Execute/ }))

      await waitFor(() => expect(executePosts).toBe(1), { timeout: 4000 })
      expect(analyzePosts).toBe(0)
    },
  )

  it(
    'auto-triggers diagnostics once the plan is ready in performance mode',
    { timeout: 15000 },
    async () => {
      let diagnosticsPosts = 0
      server.use(
        http.post('*/api/execution/analyze', () =>
          HttpResponse.json({ executionId: 'exec-1', status: 'QUEUED' }),
        ),
        http.get('*/api/execution/execute/:id', () => HttpResponse.json(COMPLETED_PLAN)),
        http.post('*/api/diagnostics/analyze', () => {
          diagnosticsPosts++
          return HttpResponse.json({ findings: [] })
        }),
      )

      const user = userEvent.setup()
      await renderBootedApp()
      fireEvent.click(screen.getByRole('radio', { name: 'Performance' }))
      await runAnalyze(user)

      await waitFor(() => expect(diagnosticsPosts).toBe(1), { timeout: 4000 })
    },
  )

  it(
    'does NOT re-fire diagnostics on data↔performance round-trips (dedup ref)',
    { timeout: 20000 },
    async () => {
      // Bug (issue 04): planResult is double-cached (planResultCache) and
      // keeps its reference across mode switches, so the auto-trigger effect
      // re-ran on every currentMode change and re-POSTed /api/diagnostics/
      // analyze for the same plan. Spec work item 4 requires a ref keyed by
      // (nodeId, plan reference) to break the cycle — never landed until now.
      let diagnosticsPosts = 0
      server.use(
        http.post('*/api/execution/analyze', () =>
          HttpResponse.json({ executionId: 'exec-1', status: 'QUEUED' }),
        ),
        http.get('*/api/execution/execute/:id', () => HttpResponse.json(COMPLETED_PLAN)),
        http.post('*/api/diagnostics/analyze', () => {
          diagnosticsPosts++
          return HttpResponse.json({ findings: [] })
        }),
      )

      const user = userEvent.setup()
      await renderBootedApp()

      // Performance + Analyze → plan arrives → diagnostics fires exactly once.
      fireEvent.click(screen.getByRole('radio', { name: 'Performance' }))
      await runAnalyze(user)
      await waitFor(() => expect(diagnosticsPosts).toBe(1), { timeout: 4000 })

      // Round-trip data↔performance twice. Each return to Performance re-runs
      // the effect (currentMode changed); the dedup ref must suppress the
      // re-POST because the (node, plan) pair is unchanged.
      fireEvent.click(screen.getByRole('radio', { name: 'Data' }))
      await screen.findByRole('button', { name: /Execute/ })
      fireEvent.click(screen.getByRole('radio', { name: 'Performance' }))
      await screen.findByRole('button', { name: /Analyze/ })
      fireEvent.click(screen.getByRole('radio', { name: 'Data' }))
      await screen.findByRole('button', { name: /Execute/ })
      fireEvent.click(screen.getByRole('radio', { name: 'Performance' }))
      await screen.findByRole('button', { name: /Analyze/ })

      // Allow any spurious re-fire a chance to land, then assert still 1.
      await waitFor(() => expect(diagnosticsPosts).toBe(1), { timeout: 2000 })
    },
  )

  it(
    'does not trigger diagnostics in data mode (negative)',
    { timeout: 15000 },
    async () => {
      let diagnosticsPosts = 0
      server.use(
        http.post('*/api/execution/execute', () =>
          HttpResponse.json({ executionId: 'exec-1', status: 'QUEUED' }),
        ),
        http.get('*/api/execution/execute/:id', () => HttpResponse.json(COMPLETED_ROWS)),
        http.post('*/api/diagnostics/analyze', () => {
          diagnosticsPosts++
          return HttpResponse.json({ findings: [] })
        }),
      )

      await renderBootedApp()
      // Data mode is default. Execute → rows arrive, Results tab visible.
      fireEvent.click(screen.getByRole('button', { name: /Execute/ }))
      await waitFor(
        () => expect(screen.getByRole('tab', { name: 'Results' })).toBeInTheDocument(),
        { timeout: 4000 },
      )

      // The auto-trigger effect early-returns in data mode; diagnostics must
      // never fire. (Guards against a future regression moving the trigger
      // out of the performance-mode gate.)
      expect(diagnosticsPosts).toBe(0)
    },
  )
})
