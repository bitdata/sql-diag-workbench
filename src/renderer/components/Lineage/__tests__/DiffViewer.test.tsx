import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import DiffViewer from '../DiffViewer'

const baseProps = {
  parentSql: 'SELECT 1',
  currentSql: 'SELECT 2',
  loading: false,
  error: null,
}

describe('DiffViewer degraded badge (P2-13 close-out)', () => {
  it('does not render degraded badge when degraded=false', () => {
    render(<DiffViewer {...baseProps} ops={[]} degraded={false} />)
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('does not render degraded badge when degraded is omitted (default false)', () => {
    render(<DiffViewer {...baseProps} ops={[]} />)
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('renders degraded badge with method when degraded=true', () => {
    render(<DiffViewer {...baseProps} ops={[]} degraded={true} diffMethod="path-set" />)
    expect(screen.getByRole('status')).toHaveTextContent(/path-set/)
  })

  it('degraded badge has accessible label', () => {
    render(<DiffViewer {...baseProps} ops={[]} degraded={true} diffMethod="ast-lcs" />)
    expect(screen.getByLabelText('Diff precision degraded')).toBeInTheDocument()
  })

  it('still renders SQL sections when degraded', () => {
    render(<DiffViewer {...baseProps} ops={[]} degraded={true} diffMethod="path-set" />)
    expect(screen.getByText('parent SQL')).toBeInTheDocument()
    expect(screen.getByText('current SQL')).toBeInTheDocument()
  })
})
