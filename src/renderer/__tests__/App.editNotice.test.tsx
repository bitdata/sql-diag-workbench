import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { buildMockTree, mockConnections } from '../test/fixtures'
import { server } from '../test/msw-server'

// Monaco-backed viewer is irrelevant to the edit-notice wiring; keep it out of
// jsdom. FragmentEditor is deliberately NOT mocked — the notice is reached by
// driving the real editor (Radix Dialog + textarea), which is the only path the
// user has.
vi.mock('../components/Editor/SqlViewer', () => ({ __esModule: true, default: () => null }))

// TanStack virtual-core measures the scroll element via offsetHeight at
// subscribe time; jsdom reports 0 for everything, calculateRange() then
// returns null and NO tree rows mount — the context menu that owns "Edit this
// fragment" is therefore unreachable. Same stub (and rationale) as
// TreeView/__tests__/FragmentTree.test.tsx: a non-zero fake viewport height
// lets the fixture tree's rows become real treeitems.
const offsetHeightDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight')
beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
    configurable: true,
    get: () => 400,
  })
})
afterAll(() => {
  if (offsetHeightDesc) {
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', offsetHeightDesc)
  }
})

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

function bootMocks() {
  // `openInNewTab` appends the created session under `created.id`, and TabBar
  // keys its tab list by `sessionId`. A constant create response (the boot
  // `SESSION`, id `sess-1`) therefore duplicated the boot tab's React key —
  // "Encountered two children with the same key" — which is rendering React
  // documents as unsupported, exactly the DOM the tab-count completion signal
  // is asserted against. Hand out a distinct id per create call instead. Boot
  // itself never POSTs here (renderBootedApp hydrates from `tabs.v1` via the
  // GET mocks), so the restored session keeps id `sess-1` untouched.
  let createdSessionSeq = 0
  server.use(
    http.get('*/api/connections', () => HttpResponse.json(mockConnections)),
    http.get('*/api/history', () => HttpResponse.json([])),
    http.get('*/api/sessions', () => HttpResponse.json([SESSION])),
    http.get('*/api/sessions/:id', () => HttpResponse.json(SESSION)),
    http.put('*/api/sessions/:id', () => HttpResponse.json({})),
    http.post('*/api/sessions', async ({ request }) => {
      const body = await request.json() as Record<string, unknown>
      createdSessionSeq += 1
      return HttpResponse.json({ ...SESSION, ...body, id: `sess-created-${createdSessionSeq}` })
    }),
    http.post('*/api/sql/parse', () =>
      HttpResponse.json({ tree: buildMockTree(), options: [], errors: [] }),
    ),
    http.post('*/api/sql/positions', () => HttpResponse.json({ nodes: [] })),
    http.post('*/api/sql/decompose-options', () => HttpResponse.json({options:[],scan:{status:'COMPLETE',issues:[]}})),
  )
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
  await screen.findByRole('button', { name: /Execute|Analyze/ })
}

/** Open the real FragmentEditor for the boot tree's root node (id
 *  `node-root`) via its context menu, replace the SQL, and save. */
async function editRootFragmentAndSave(newSql: string) {
  // Scoped to the tree row, not the text: "Full Query" also labels the SQL
  // viewer breadcrumb. The row's own onContextMenu lives on its wrapping
  // `.tree-node-group`, so firing on the treeitem bubbles up to it.
  const rootRow = (await screen.findAllByRole('treeitem')).find((el) =>
    el.textContent?.includes('Full Query'),
  )
  if (!rootRow) throw new Error('no root treeitem row found')
  fireEvent.contextMenu(rootRow)

  const editItem = await screen.findByRole('menuitem', { name: /Edit this fragment/ })
  fireEvent.click(editItem)

  const textarea = await screen.findByLabelText('SQL fragment editor')
  fireEvent.change(textarea, { target: { value: newSql } })

  fireEvent.click(screen.getByRole('button', { name: 'Save to a new tab' }))
}

/** The awaited element is the banner's message span; return the banner it
 *  lives in so assertions can be scoped to that one alert. */
function bannerFor(messageEl: HTMLElement): HTMLElement {
  const banner = messageEl.closest('[role="alert"], [role="status"]')
  expect(banner).not.toBeNull()
  return banner as HTMLElement
}

describe('App fragment-edit notice', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'localStorage', {
      value: makeMemoryStorage(),
      configurable: true,
    })
    bootMocks()
  })

  it(
    'renders the UNSUPPORTED notice, with the backend reason, when the edit cannot be canonicalized',
    { timeout: 15000 },
    async () => {
      const REASON = 'node type VALUES has no canonical form'
      server.use(
        http.post('*/api/fragment/create-user-edit', () =>
          // `non_null` (application.yml) strips the null `node` key entirely —
          // which is exactly the wire shape submitUserEdit keys UNSUPPORTED off.
          HttpResponse.json({
            classification: 'UNSUPPORTED',
            navigatedToExisting: false,
            errorReason: REASON,
          }),
        ),
      )

      await renderBootedApp()
      await editRootFragmentAndSave('SELECT * FROM customers')

      const banner = bannerFor(
        await screen.findByText(/cannot be canonicalized/, {}, { timeout: 4000 }),
      )
      expect(banner).toHaveTextContent('This SQL edit is not supported')
      expect(banner).toHaveTextContent(
        'this fragment cannot be canonicalized, so it has no stable identity and nothing was saved.',
      )
      expect(banner).toHaveTextContent(`Reason from the backend: ${REASON}`)
      // A stated degradation, not an error: acknowledge, never retry.
      expect(within(banner).getByRole('button', { name: 'Dismiss' })).toBeInTheDocument()
      expect(within(banner).queryByRole('button', { name: 'Retry' })).toBeNull()
    },
  )

  it(
    'renders the NO_CHANGE notice when the edit resolves to the facet being edited',
    { timeout: 15000 },
    async () => {
      server.use(
        http.post('*/api/fragment/create-user-edit', () =>
          // NAVIGATE back to the node we are editing (parentId === node.id)
          // is the NO_CHANGE branch: no other SQL exists to open.
          HttpResponse.json({
            classification: 'NAVIGATE',
            navigatedToExisting: true,
            node: { id: 'node-root' },
          }),
        ),
      )

      await renderBootedApp()
      await editRootFragmentAndSave('SELECT * FROM customers')

      const banner = bannerFor(
        await screen.findByText(/resolves to the very facet you are editing/, {}, { timeout: 4000 }),
      )
      expect(banner).toHaveTextContent('No changes to open')
      expect(banner).toHaveTextContent('Nothing was saved and no tab was opened.')
      expect(within(banner).getByRole('button', { name: 'Dismiss' })).toBeInTheDocument()
      expect(within(banner).queryByRole('button', { name: 'Retry' })).toBeNull()
    },
  )
})

/** Boot tree with a degraded root identity (the marker the backend would
 *  send for an un-dispatched root like EXPLAIN). Key present, non-null. */
function degradedTree() {
  return {
    ...buildMockTree(),
    canonicalizationError: 'unsupported node types: SqlExplain',
  }
}

describe('App fragment-edit notice — degraded source (spec §5)', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'localStorage', {
      value: makeMemoryStorage(),
      configurable: true,
    })
    bootMocks()
  })

  it(
    'EDIT from a degraded source states the degraded identity and its reason',
    { timeout: 15000 },
    async () => {
      server.use(
        // Boot parse returns the degraded root — the tree the edit is submitted
        // against, so `submitUserEdit` looks the marker up on `node-root`.
        http.post('*/api/sql/parse', () =>
          HttpResponse.json({ tree: degradedTree(), options: [], errors: [] }),
        ),
        http.post('*/api/fragment/create-user-edit', () =>
          HttpResponse.json({
            node: {
              id: 'edited-1', type: 'USER_EDITED', label: 'edited', sqlFragment: 'SELECT 2',
              executable: true, executionHint: null, nodeCount: 0, ruleName: null,
              children: [], editParentId: 'node-root',
            },
            classification: 'EDIT',
            navigatedToExisting: false,
          }),
        ),
      )

      await renderBootedApp()
      await editRootFragmentAndSave('SELECT 2')

      const banner = bannerFor(
        await screen.findByText(/carries a degraded identity/, {}, { timeout: 4000 }),
      )
      expect(banner).toHaveTextContent('Edited SQL opened in a new tab')
      expect(banner).toHaveTextContent('the edit was saved as a new fragment and opened in a new tab.')
      expect(banner).toHaveTextContent('Reason: unsupported node types: SqlExplain')
      // Stated degradation, not an error: acknowledge, never retry.
      expect(within(banner).getByRole('button', { name: 'Dismiss' })).toBeInTheDocument()
      expect(within(banner).queryByRole('button', { name: 'Retry' })).toBeNull()

      // Spec §7 ⑨ — Dismiss clears the notice.
      fireEvent.click(within(banner).getByRole('button', { name: 'Dismiss' }))
      expect(screen.queryByText(/carries a degraded identity/)).toBeNull()
      expect(screen.queryByRole('alert')).toBeNull()
      expect(screen.queryAllByRole('status').find(el => el.classList.contains('boot-error-banner')) ?? null).toBeNull()
    },
  )

  it(
    'EDIT from a clean source opens the tab and raises no notice at all',
    { timeout: 15000 },
    async () => {
      // bootMocks' parse handler serves buildMockTree() — clean, key absent.
      server.use(
        http.post('*/api/fragment/create-user-edit', () =>
          HttpResponse.json({
            node: {
              id: 'edited-1', type: 'USER_EDITED', label: 'edited', sqlFragment: 'SELECT 2',
              executable: true, executionHint: null, nodeCount: 0, ruleName: null,
              children: [], editParentId: 'node-root',
            },
            classification: 'EDIT',
            navigatedToExisting: false,
          }),
        ),
      )

      await renderBootedApp()
      await editRootFragmentAndSave('SELECT 2')

      // Positive completion signal FIRST: `runFragmentEdit` awaits
      // `openInNewTab`, which appends a second session tab before it can set
      // any notice. Asserting silence straight after the click would pass even
      // if the notice were merely scheduled a tick later — the async edit has
      // not resolved yet, so the reverse pin would pin nothing.
      const sessionTabs = () =>
        within(screen.getByRole('tablist', { name: 'Open sessions' })).getAllByRole('tab')
      await waitFor(() => expect(sessionTabs()).toHaveLength(2))

      // Reverse: the success path used to be silent and must stay silent.
      expect(screen.queryByText(/carries a degraded identity/)).toBeNull()
      expect(screen.queryByRole('alert')).toBeNull()
    },
  )

  it(
    'UNSUPPORTED keeps its own copy — the degraded notice never stacks on it',
    { timeout: 15000 },
    async () => {
      server.use(
        http.post('*/api/sql/parse', () =>
          HttpResponse.json({ tree: degradedTree(), options: [], errors: [] }),
        ),
        http.post('*/api/fragment/create-user-edit', () =>
          HttpResponse.json({
            classification: 'UNSUPPORTED',
            navigatedToExisting: false,
            errorReason: 'unsupported node types: SqlUpdate',
          }),
        ),
      )

      await renderBootedApp()
      // The edit payload is a SELECT, not the `UPDATE` the brief sketched: the
      // real FragmentEditor refuses any SQL without SELECT/WITH client-side
      // ("Invalid SQL: must contain SELECT or WITH"), so a DML payload never
      // reaches the mocked backend and this pin could never go green. The
      // backend handler is mocked, so the classification under test is
      // unaffected.
      await editRootFragmentAndSave('SELECT * FROM customers')

      const banner = bannerFor(
        await screen.findByText(/cannot be canonicalized/, {}, { timeout: 4000 }),
      )
      expect(banner).toHaveTextContent('Reason from the backend: unsupported node types: SqlUpdate')
      // Branch stacking: the degraded notice is EDIT-only, so no second banner.
      expect(banner).not.toHaveTextContent('carries a degraded identity')
      expect(screen.getAllByRole('status').filter(el => el.classList.contains('boot-error-banner'))).toHaveLength(1)
    },
  )

  it(
    'blank source marker still states the degradation, with no empty Reason tail',
    { timeout: 15000 },
    async () => {
      server.use(
        http.post('*/api/sql/parse', () =>
          HttpResponse.json({
            tree: { ...buildMockTree(), canonicalizationError: '' },
            options: [],
            errors: [],
          }),
        ),
        http.post('*/api/fragment/create-user-edit', () =>
          HttpResponse.json({
            node: {
              id: 'edited-1', type: 'USER_EDITED', label: 'edited', sqlFragment: 'SELECT 2',
              executable: true, executionHint: null, nodeCount: 0, ruleName: null,
              children: [], editParentId: 'node-root',
            },
            classification: 'EDIT',
            navigatedToExisting: false,
          }),
        ),
      )

      await renderBootedApp()
      await editRootFragmentAndSave('SELECT 2')

      const banner = bannerFor(
        await screen.findByText(/carries a degraded identity/, {}, { timeout: 4000 }),
      )
      expect(banner).toHaveTextContent('Edited SQL opened in a new tab')
      // No reason text ⇒ no "Reason:" tail at all (spec §6).
      expect(banner).not.toHaveTextContent('Reason:')
    },
  )
})

describe('App edit recovery', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'localStorage', { value: makeMemoryStorage(), configurable: true })
    bootMocks()
  })

  it('retries only opening the completed edit, without a boot banner or duplicate edit POST', async () => {
    let editPosts = 0
    let sessionPosts = 0
    server.use(
      http.post('*/api/fragment/create-user-edit', () => {
        editPosts++
        return HttpResponse.json({ classification: 'EDIT', node: { id: 'edited-1' } })
      }),
      http.post('*/api/sessions', async ({ request }) => {
        sessionPosts++
        if (sessionPosts === 1) return new HttpResponse(null, { status: 503 })
        const body = await request.json() as Record<string, unknown>
        return HttpResponse.json({ ...SESSION, ...body, id: 'recovered-tab' })
      }),
    )
    await renderBootedApp()
    await editRootFragmentAndSave('SELECT 2')
    const banner = bannerFor(await screen.findByText(/Edit completed; new tab could not be opened/))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(screen.queryByText(/Could not create the first tab/)).toBeNull()
    fireEvent.click(within(banner).getByRole('button', { name: 'Retry opening tab' }))
    await waitFor(() => expect(within(screen.getByRole('tablist', { name: 'Open sessions' })).getAllByRole('tab')).toHaveLength(2))
    expect(editPosts).toBe(1)
    expect(sessionPosts).toBe(2)
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
