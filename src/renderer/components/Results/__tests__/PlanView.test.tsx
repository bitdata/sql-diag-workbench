import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import PlanView from '../PlanView'
import { mockPlanNode } from '../../../test/fixtures'

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
