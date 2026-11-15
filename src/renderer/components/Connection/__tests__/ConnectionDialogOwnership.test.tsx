import { act, fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import ConnectionDialog from '../ConnectionDialog'
import { useConnections } from '../../../hooks/useConnections'
import { apiFetch } from '../../../utils/api'
import type { ConnectionConfig } from '../../../types/connection'
vi.mock('../../../utils/api', () => ({ apiFetch: vi.fn() }))
const config: ConnectionConfig = { id: 'a', name: 'Synthetic', host: 'localhost', port: 1,
  database: 'synthetic', username: 'synthetic', dialect: 'sqlserver', readonly: true, createdAt: '', updatedAt: '' }
function Harness({ url }: { url: string | null }) {
  const api = useConnections(url)
  return <ConnectionDialog editingConnection={config} onTest={api.testConnection} onSave={async () => false} onCancel={() => {}} />
}
describe('ConnectionDialog with actual connection hook', () => {
  it('never shows an old-backend success and preserves the draft', async () => {
    let resolve!: (response: Response) => void
    const pending = new Promise<Response>(r => { resolve = r })
    vi.mocked(apiFetch).mockResolvedValueOnce({ ok: true, json: async () => [config] } as Response).mockReturnValueOnce(pending)
    const { rerender } = render(<Harness url="http://synthetic" />)
    fireEvent.click(screen.getByRole('button', { name: 'Test Connection' }))
    rerender(<Harness url={null} />)
    await act(async () => { resolve({ ok: true, json: async () => ({ success: true, serverVersion: 'obsolete' }) } as Response) })
    expect(screen.getByText('Connection context changed. Retry the test.')).toBeInTheDocument()
    expect(document.querySelector('.test-success')).toBeNull()
    expect(screen.getByDisplayValue('Synthetic')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Test Connection' })).toBeEnabled()
  })
})
