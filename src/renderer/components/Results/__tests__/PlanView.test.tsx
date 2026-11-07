import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import PlanView from '../PlanView'
import type { Metric, PlanNode } from '../../../types/execution'
import { mockPlanNode } from '../../../test/fixtures'
import { getLocale, setLocale } from '../../../i18n'

describe('PlanView', () => {
  it('shows the Analyze hint when plan is null and no planError', () => {
    render(<PlanView plan={null} loading={false} />)
    const hint = screen.getByText(/execution plan/i)
    expect(hint).toBeInTheDocument()
    expect(hint.textContent).toMatch(/Analyze/)
    expect(hint.textContent).not.toMatch(/Execute/)
  })

  it('shows the planError reason instead of the hint when EXPLAIN failed', () => {
    render(<PlanView plan={null} loading={false} planError="statement timeout" />)
    expect(screen.getByText(/statement timeout/)).toBeInTheDocument()
    expect(screen.queryByText(/Analyze/)).toBeNull()
  })

  it('renders the plan tree when plan is present', () => {
    render(<PlanView plan={mockPlanNode} loading={false} />)
    expect(screen.getByText(mockPlanNode.operation)).toBeInTheDocument()
    expect(screen.getAllByText(/Unknown/).length).toBeGreaterThan(0)
    expect(screen.getByText(/legacy or unsupported capture/)).toBeInTheDocument()
  })
})

it('localizes mapping explanations while preserving SQL identifiers and diagnostic codes', () => {
  const previous = getLocale()
  setLocale('zh-CN')
  const unknown: Metric = { value: null, state: 'NOT_COLLECTED', unit: 'ROWS', basis: 'UNKNOWN', sourceField: null, errorReason: null }
  const plan: PlanNode = { ...mockPlanNode, children: [], metrics: { version: 1, origin: 'DATABASE_NODE', sourcePath: 'native-path', estimatedRows: unknown, actualRows: unknown, actualDurationMs: unknown, loops: unknown, startupCost: unknown, totalCost: unknown } }
  const { container, unmount } = render(<PlanView plan={plan} loading={false} selectedPath="native-path" mapping={{
    version: 1, executionId: 'e', captureKey: 'key', dialect: 'postgresql', submittedSqlSha256: 'hash',
    degraded: true, errorReason: 'FRAGMENT_IDENTITY_DEGRADED',
    issues: [{ code: 'FRAGMENT_IDENTITY_DEGRADED', planNodePath: 'native-path', message: 'fragment identity or execution is degraded: fragment-raw-id' }],
    edges: [
      { planNodePath: 'native-path', targetKind: 'SQL_SPAN', targetId: null, span: { nodeId: 'submitted_root', type: 'QUERY', label: 'Submitted SQL', startOffset: 0, endOffset: 24 }, confidence: 'EXACT', relation: 'PLAN_TO_SQL', reason: 'submitted query observation; not an operator-to-clause identity' },
      { planNodePath: 'native-path', targetKind: 'SQL_SPAN', targetId: null, span: { nodeId: 'relation_0', type: 'TABLE_REF', label: '"Submitted SQL"', startOffset: 14, endOffset: 29 }, confidence: 'INFERRED', relation: 'PLAN_TO_SQL', reason: 'AST relation or alias candidate; optimizer correspondence is unproven' },
    ],
  }} />)
  try {
    const mapping = container.querySelector('.plan-mapping')!
    expect(mapping).toHaveTextContent('提交的 SQL')
    expect(mapping).toHaveTextContent('对应提交查询的观察结果')
    expect(mapping).toHaveTextContent('片段身份或执行已降级：fragment-raw-id')
    expect(mapping).toHaveTextContent('FRAGMENT_IDENTITY_DEGRADED')
    expect(mapping).toHaveTextContent('"Submitted SQL"')
    expect(mapping).not.toHaveTextContent('optimizer correspondence is unproven')
  } finally {
    unmount()
    setLocale(previous)
  }
})

it('sorts exact large decimals, keeps unknown last, filters and selects native evidence', () => {
  const m = (value: string | null): Metric => ({ value, state: value === null ? 'NOT_COLLECTED' : 'AVAILABLE', unit: 'ROWS', basis: 'PER_LOOP_AVERAGE', sourceField: 'rows', errorReason: null })
  const node = (operation: string, value: string | null): PlanNode => ({ ...mockPlanNode, operation, children: [], metrics: { version: 1, origin: 'DATABASE_NODE', sourcePath: operation, estimatedRows: m(value), actualRows: m(value), actualDurationMs: m(value), loops: m(value), startupCost: m(null), totalCost: m(null) } })
  const root = { ...node('root', null), children: [node('smaller', '9007199254740992'), node('larger', '9007199254740993')] }
  const { container } = render(<PlanView plan={root} loading={false} />)
  fireEvent.change(screen.getByLabelText('Plan order'), { target: { value: 'actualRows' } })
  expect([...container.querySelectorAll('.plan-row .plan-op')].map(n => n.textContent)).toEqual(['larger', 'smaller', 'root'])
  fireEvent.change(screen.getByLabelText('Filter nodes'), { target: { value: 'larger' } })
  expect(container.querySelectorAll('.plan-row')).toHaveLength(1)
  fireEvent.click(container.querySelector('.plan-row')!)
  expect(screen.getByLabelText('Selected plan evidence')).toHaveTextContent('9007199254740993')
  expect(screen.getByLabelText('Selected plan evidence')).toHaveTextContent('PER_LOOP_AVERAGE')
})
