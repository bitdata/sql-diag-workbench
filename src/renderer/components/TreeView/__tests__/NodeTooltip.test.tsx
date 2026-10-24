import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { buildMockTree } from '../../../test/fixtures'
import { NodeTooltipBody } from '../NodeTooltip'

describe('NodeTooltipBody — degraded-identity row', () => {
  it('shows Identity degraded with the reason tail and the equivalence note', () => {
    const node = { ...buildMockTree(), canonicalizationError: 'unsupported node types: SqlExplain' }
    render(<NodeTooltipBody node={node} />)
    expect(screen.getByText(/Identity degraded — unsupported node types: SqlExplain/)).toBeInTheDocument()
    expect(
      screen.getByText(/the id was derived from raw text, so equivalent rewrites will not be recognized/),
    ).toBeInTheDocument()
  })

  it('omits the degraded row entirely when the marker is clean', () => {
    render(<NodeTooltipBody node={buildMockTree()} />)
    expect(screen.queryByText(/Identity degraded/)).toBeNull()
    expect(screen.queryByText(/equivalent rewrites will not be recognized/)).toBeNull()
  })

  it('shows the bare label with no " — reason" tail for a blank marker (spec §6)', () => {
    const node = { ...buildMockTree(), canonicalizationError: '' }
    render(<NodeTooltipBody node={node} />)
    expect(screen.getByText('Identity degraded')).toBeInTheDocument()
    expect(screen.queryByText(/Identity degraded —/)).toBeNull()
    expect(screen.getByText(/equivalent rewrites will not be recognized/)).toBeInTheDocument()
  })

  it('shows the bare label with no " — reason" tail for a whitespace-only marker (spec §6, trim pinned)', () => {
    const node = { ...buildMockTree(), canonicalizationError: '   ' }
    render(<NodeTooltipBody node={node} />)
    expect(screen.getByText('Identity degraded')).toBeInTheDocument()
    expect(screen.queryByText(/Identity degraded —/)).toBeNull()
  })

  it('still shows the runnable/view-only line for clean nodes', () => {
    render(<NodeTooltipBody node={buildMockTree()} />)
    expect(screen.getByText('Runnable')).toBeInTheDocument()
  })
})
