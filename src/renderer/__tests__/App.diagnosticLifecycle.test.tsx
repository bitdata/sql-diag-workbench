import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { buildMockTree, mockConnections, mockPlanNode, mockFinding } from '../test/fixtures'
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

// The real virtual tree has no viewport in jsdom; exercise App's real selection handler.
vi.mock('../components/TreeView/FragmentTree', () => ({ default: ({ tree, onSelectNode }: { tree: { id: string } | null; onSelectNode: (id: string) => void }) =>
  tree ? <button data-fragment-id={tree.id} onClick={() => onSelectNode(tree.id)}>RECON browse root</button> : null }))

describe('diagnostic lifecycle ownership', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'localStorage', { value: makeMemoryStorage(), configurable: true })
    bootMocks()
  })

  it('restores diagnostics after input -> no-plan browse -> cached input without executing the plan again', async () => {
    let posts = 0, executions = 0
    server.use(
      http.post('*/api/execution/analyze', () => { executions++; return HttpResponse.json({ executionId: 'exec-1', status: 'QUEUED' }) }),
      http.get('*/api/execution/execute/:id', () => HttpResponse.json(COMPLETED_PLAN)),
      http.post('*/api/diagnostics/plan', () => {
        posts++
        return HttpResponse.json({ findings: [{ ...mockFinding, title: 'RECON evidence sentinel' }], evaluation: { version: 1, executionId: 'exec-1', sourceState: 'VERIFIED', rules: [], degraded: false, errorReason: null } })
      }),
    )
    const user = userEvent.setup()
    await renderBootedApp()
    fireEvent.click(screen.getByRole('radio', { name: 'Performance' }))
    await runAnalyze(user)
    await waitFor(() => expect(posts).toBe(1))
    fireEvent.click(screen.getByRole('tab', { name: 'Diagnostics' }))
    await screen.findByText('RECON evidence sentinel')
    const child = document.querySelector<HTMLElement>('[data-fragment-id="node-root"]')
    expect(child).not.toBeNull()
    fireEvent.click(child!)
    await screen.findByText('Read-only fragment')
    fireEvent.click(screen.getByRole('button', { name: /Edit original SQL/ }))
    await screen.findByText('Current SQL')
    await waitFor(() => expect(posts).toBe(2))
    await screen.findByText('RECON evidence sentinel')
    expect(executions).toBe(1)
  }, 15000)

  it('hides the previous receipt during a new Analyze and binds the replacement execution', async () => {
    let executions = 0
    let release!: () => void
    const secondSubmission = new Promise<void>(resolve => { release = resolve })
    server.use(
      http.post('*/api/execution/analyze', async () => {
        const id = `exec-${++executions}`
        if (executions === 2) await secondSubmission
        return HttpResponse.json({ executionId: id, status: 'QUEUED' })
      }),
      http.get('*/api/execution/execute/:id', ({ params }) => HttpResponse.json({ ...COMPLETED_PLAN, executionId: params.id })),
      http.post('*/api/diagnostics/plan', async ({ request }) => {
        const body = await request.json() as { executionId: string }
        return HttpResponse.json({ findings: [{ ...mockFinding, title: `${body.executionId} receipt` }],
          evaluation: { version: 1, executionId: body.executionId, sourceState: 'VERIFIED', rules: [], degraded: false, errorReason: null } })
      }),
    )
    const user = userEvent.setup()
    await renderBootedApp()
    fireEvent.click(screen.getByRole('radio', { name: 'Performance' }))
    await runAnalyze(user)
    await user.click(screen.getByRole('tab', { name: 'Diagnostics' }))
    await screen.findByText('exec-1 receipt')
    await runAnalyze(user)
    expect(screen.queryByText('exec-1 receipt')).toBeNull()
    release()
    await screen.findByText('exec-2 receipt')
    expect(executions).toBe(2)
  }, 15000)
})
