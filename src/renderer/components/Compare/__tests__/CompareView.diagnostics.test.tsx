import userEvent from '@testing-library/user-event'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CompareView from '../CompareView'
import { apiFetch } from '../../../utils/api'
import { mockConnections, mockFinding, mockPlanResult, mockRootNode } from '../../../test/fixtures'
import type { CompareSource, CompareTarget } from '../../../utils/compareSources'

vi.mock('../../../utils/api', () => ({ apiFetch: vi.fn() }))
vi.mock('../../Editor/SqlViewer', () => ({ default: () => null }))
// Keep actual execution/coordinator/results/panel logic; provide a direct estimated-plan control.
vi.mock('../../Editor/AnalyzeSplitButton', () => ({ AnalyzeSplitButton: ({ onAnalyze, disabled }: { onAnalyze: (mode: string) => void; disabled: boolean }) =>
  <button disabled={disabled} onClick={() => onAnalyze('estimated')}>Analyze</button> }))
const response = (body: unknown, ok = true) => ({ ok, status: ok ? 200 : 503, json: async () => body }) as Response
const target: CompareTarget = { sessionId: 's', tabName: 'one', dialect: 'postgresql', version: 'v1', node: mockRootNode }
const other: CompareTarget = { ...target, node: { ...mockRootNode, id: 'other', sqlFragment: 'SELECT 2', label: 'other' } }
const source: CompareSource = { sessionId: 's', tabName: 'one', dialect: 'postgresql', version: 'v1', nodes: [target.node, other.node], errorReason: null, degraded: false }
const receipt = (executionId: string, title: string) => ({ findings: [{ ...mockFinding, title }],
  evaluation: { version: 1, executionId, sourceState: 'VERIFIED', rules: [], degraded: false, errorReason: null } })
describe('A/B diagnostic consumer ownership', () => {
  let executions: number, diagnostics: number, failFirst: boolean
  beforeEach(() => {
    executions = 0; diagnostics = 0; failFirst = false
    vi.mocked(apiFetch).mockReset().mockImplementation(async (url, init) => {
      const path = String(url)
      if (path.endsWith('/execution/analyze')) return response({ executionId: `e${++executions}`, status: 'QUEUED' })
      if (path.includes('/execution/execute/')) return response({ ...mockPlanResult, executionId: path.split('/').pop(), status: 'COMPLETED' })
      if (path.endsWith('/diagnostics/analyze')) {
        diagnostics++
        const body = JSON.parse(init!.body as string)
        return response(receipt(body.executionId, `${body.executionId} finding`), !(failFirst && diagnostics === 1))
      }
      return response({})
    })
  })
  const view = (locked = false, sources = [source]) => <CompareView a={target} b={other} side="A" sources={sources}
    backendUrl="http://backend" connections={mockConnections} connectionId="conn-1" settings={{ maxRows: 10, queryTimeout: 30 }}
    theme="dark" locked={locked} onChoose={vi.fn()} onSide={vi.fn()} onSwap={vi.fn()} onClose={vi.fn()} />
  const pane = (side: string) => within(screen.getByRole('region', { name: `Fragment ${side}` }))
  async function analyze(side: string) {
    fireEvent.click(pane(side).getByRole('radio', { name: 'Performance' }))
    fireEvent.click(pane(side).getByRole('button', { name: 'Analyze' }))
    await userEvent.click(pane(side).getByRole('tab', { name: 'Diagnostics' }))
  }

  it('keeps both sides independent and restores a locked pane without a new plan', async () => {
    const { rerender } = render(view())
    await analyze('A'); await pane('A').findByText('e1 finding')
    expect(pane('B').queryByText('e1 finding')).toBeNull()
    await analyze('B'); await pane('B').findByText('e2 finding')
    expect(pane('A').queryByText('e2 finding')).toBeNull()
    fireEvent.click(pane('A').getByRole('radio', { name: 'Data' }))
    fireEvent.click(pane('A').getByRole('radio', { name: 'Performance' }))
    expect(diagnostics).toBe(2)
    rerender(view(true))
    expect(screen.queryByText('e1 finding')).toBeNull()
    expect(screen.queryByText('e2 finding')).toBeNull()
    rerender(view())
    await waitFor(() => expect(diagnostics).toBe(4))
    expect(executions).toBe(2)
    // A source revision mismatch invalidates both receipts and cannot auto-retry.
    rerender(view(false, [{ ...source, version: 'v2' }]))
    expect(screen.queryByText('e1 finding')).toBeNull()
    expect(screen.queryByText('e2 finding')).toBeNull()
    expect(diagnostics).toBe(4)
  })

  it('retries only the failed side and never submits a new execution', async () => {
    failFirst = true
    render(view())
    await analyze('A')
    await pane('A').findByRole('button', { name: 'Retry diagnostics' })
    await analyze('B'); await pane('B').findByText('e2 finding')
    fireEvent.click(pane('A').getByRole('button', { name: 'Retry diagnostics' }))
    await pane('A').findByText('e1 finding')
    expect(diagnostics).toBe(3)
    expect(executions).toBe(2)
    expect(pane('B').getByText('e2 finding')).toBeInTheDocument()
  })
})
