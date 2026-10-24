import { describe, it, expect, vi } from 'vitest'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { mockFinding, mockConfirmedFinding, mockRefutedFinding } from '../../../test/fixtures'
import DiagnosticCard from '../DiagnosticCard'

describe('DiagnosticCard', () => {
  it('shows incomplete analysis without confidence or verification for a degraded rule', () => {
    const { getByText, queryByText, queryByRole } = renderWithProviders(
      <DiagnosticCard finding={{ ...mockFinding, degraded: true, errorReason: 'RULE_FAILED' }} />
    )
    expect(getByText('Analysis incomplete')).toBeInTheDocument()
    expect(queryByText('Confidence:')).not.toBeInTheDocument()
    expect(queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument()
  })
  it('renders severity badge and title', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard finding={mockFinding} />
    )
    expect(getByText('HIGH')).toBeInTheDocument()
    expect(getByText(mockFinding.title)).toBeInTheDocument()
  })

  it('renders the finding message', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard finding={mockFinding} />
    )
    expect(getByText(mockFinding.message)).toBeInTheDocument()
  })

  it('renders evidence list', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard finding={mockFinding} />
    )
    for (const item of mockFinding.evidence) {
      expect(getByText(item)).toBeInTheDocument()
    }
  })

  it('shows confidence percentage', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard finding={mockFinding} />
    )
    expect(getByText('85%')).toBeInTheDocument()
  })

  it('shows "Suspected" status for SUSPECTED findings', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard finding={mockFinding} />
    )
    expect(getByText(/Suspected/)).toBeInTheDocument()
  })

  it('shows "Confirmed" status for CONFIRMED findings', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard finding={mockConfirmedFinding} />
    )
    expect(getByText(/Confirmed/)).toBeInTheDocument()
  })

  it('shows "Refuted" status for REFUTED findings', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard finding={mockRefutedFinding} />
    )
    expect(getByText(/Refuted/)).toBeInTheDocument()
  })

  it('renders Verify button when verificationSql exists and status is SUSPECTED', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard finding={mockFinding} onVerify={vi.fn()} />
    )
    expect(getByText('Verify')).toBeInTheDocument()
  })

  it('disables Verify button for non-SUSPECTED findings', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard finding={mockConfirmedFinding} onVerify={vi.fn()} />
    )
    expect(getByText('Verify')).toBeDisabled()
  })

  it('calls onVerify when Verify button clicked', async () => {
    const onVerify = vi.fn()
    const { user, getByText } = renderWithProviders(
      <DiagnosticCard finding={mockFinding} onVerify={onVerify} />
    )
    await user.click(getByText('Verify'))
    expect(onVerify).toHaveBeenCalledWith(mockFinding.id, mockFinding.verificationSql)
  })

  it('shows "Verifying..." when isVerifying is true', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard finding={mockFinding} onVerify={vi.fn()} isVerifying={true} />
    )
    expect(getByText('Verifying...')).toBeInTheDocument()
  })

  it('displays verification result when present', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard finding={mockConfirmedFinding} />
    )
    expect(getByText(mockConfirmedFinding.verificationResult!)).toBeInTheDocument()
  })

  it('displays verify error when present', () => {
    const { getByText } = renderWithProviders(
      <DiagnosticCard
        finding={mockFinding}
        onVerify={vi.fn()}
        verifyError="Connection refused"
      />
    )
    expect(getByText('Connection refused')).toBeInTheDocument()
  })
})
