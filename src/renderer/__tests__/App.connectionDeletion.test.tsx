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
  identityPolicy: 'structural-p0p1-v2',
  workspaceRevision: 0,
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


// Keep the real App, session hooks and connection hook; expose the manager callbacks
// so a pending delete can overlap tab/connection changes without a modal trapping input.
const deleteCompleted = vi.hoisted(() => vi.fn())
vi.mock('../components/Connection/ConnectionManager', () => ({ default: (props: any) => <div>
  <output data-testid="connection-owner">{props.selectedConnectionId ?? 'offline'}</output>
  <button onClick={() => { void props.onDelete('conn-1').then(deleteCompleted) }}>Delete captured connection</button>
  <button onClick={() => props.onSelect('conn-2')}>Select another connection</button>
</div> }))
describe('App pending connection deletion', () => {
  beforeEach(() => {
    deleteCompleted.mockReset()
    Object.defineProperty(window, 'localStorage', { value: makeMemoryStorage(), configurable: true })
    bootMocks()
    server.use(http.get('*/api/connections', () => HttpResponse.json([
      ...mockConnections, { ...mockConnections[0], id: 'conn-2', name: 'Other connection' }
    ])))
  })
  for (const change of [false, true]) it(`delete completion ${change ? 'preserves newly selected connection' : 'clears unchanged selection'}`, async () => {
    let release!: () => void
    let entered = false
    const barrier = new Promise<void>(r => { release = r })
    server.use(http.delete('*/api/connections/conn-1', async () => { entered = true; await barrier; return new HttpResponse(null, { status: 204 }) }))
    await renderBootedApp()
    fireEvent.click(screen.getByRole('button', { name: 'Delete captured connection' }))
    await waitFor(() => expect(entered).toBe(true))
    if (change) fireEvent.click(screen.getByRole('button', { name: 'Select another connection' }))
    if (change) await waitFor(() => expect(screen.getByTestId('connection-owner')).toHaveTextContent('conn-2'))
    release()
    await waitFor(() => expect(screen.getByTestId('connection-owner')).toHaveTextContent(change ? 'conn-2' : 'offline'))
    await waitFor(() => expect(deleteCompleted).toHaveBeenCalledWith(true))
    expect(screen.getByTestId('connection-owner')).toHaveTextContent(change ? 'conn-2' : 'offline')
  })
  it('completion cannot clear the connection in a different tab', async () => {
    const second = { ...SESSION, id: 'sess-2', name: 'Other tab' }
    let release!: () => void
    const pending = new Promise<void>(r => { release = r })
    server.use(http.get('*/api/sessions', () => HttpResponse.json([SESSION, second])),
      http.get('*/api/sessions/:id', ({ params }) => HttpResponse.json(params.id === 'sess-2' ? second : SESSION)),
      http.delete('*/api/connections/conn-1', async () => { await pending; return new HttpResponse(null, { status: 204 }) }))
    localStorage.setItem('tabs.v1', JSON.stringify({ tabSessionIds: ['sess-1', 'sess-2'], activeSessionId: 'sess-1', modeBySession: {} }))
    render(<App />)
    await waitFor(() => expect(screen.getByTestId('connection-owner')).toHaveTextContent('conn-1'))
    fireEvent.click(screen.getByRole('button', { name: 'Delete captured connection' }))
    fireEvent.click(await screen.findByRole('tab', { name: /Other tab/ }))
    await waitFor(() => expect(screen.getByRole('tab', { name: /Other tab/ })).toHaveAttribute('aria-selected', 'true'))
    release()
    await waitFor(() => expect(deleteCompleted).toHaveBeenCalledWith(true))
    expect(screen.getByTestId('connection-owner')).toHaveTextContent('conn-1')
  })
})
