import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, waitFor, type RenderResult } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ConnectionDialog from '../ConnectionDialog'
import type { ConnectionConfig, ConnectionRequest, ConnectionTestResult } from '../../../types/connection'

type ConnectionDialogProps = React.ComponentProps<typeof ConnectionDialog>
type User = ReturnType<typeof userEvent.setup>

/**
 * Mirrors how OverlayHost.tsx controls ConnectionDialog: the dialog has no
 * `open` prop of its own — Dialog.Root is always `open` and the parent
 * mounts/unmounts it. This harness reproduces that mount/unmount ownership so
 * we can pin the Radix-Portal closed state (not mounted → no dialog node in
 * the document, including no detached portal leftovers).
 */
function DialogMountHarness({
  onCancel,
  ...dialogProps
}: Omit<ConnectionDialogProps, 'onCancel'> & { onCancel: () => void }) {
  const [show, setShow] = useState(false)
  return (
    <>
      <button onClick={() => setShow(true)}>Open connection dialog</button>
      {show && (
        <ConnectionDialog
          {...dialogProps}
          onCancel={() => {
            setShow(false) // parent unmounts → Portal cleanup
            onCancel()
          }}
        />
      )}
    </>
  )
}

/** Defaults of the unedited new-connection form (postgresql/localhost/5432)
 *  with the three required text fields filled in. */
const baseRequest: ConnectionRequest = {
  name: 'Test PG',
  host: 'localhost',
  port: 5432,
  database: 'testdb',
  username: 'test',
  password: '',
  dialect: 'postgresql',
  connectionType: undefined,
  trustServerCertificate: undefined,
}

// The form's <label>s are not associated with their inputs (no htmlFor/id),
// so fields are addressed by placeholder; port is a type=number spinbutton.
async function fillRequiredFields(
  user: User,
  q: Pick<RenderResult, 'getByPlaceholderText'>
) {
  await user.type(q.getByPlaceholderText('My Database'), 'Test PG')
  await user.type(q.getByPlaceholderText('mydb'), 'testdb')
  await user.type(q.getByPlaceholderText('username'), 'test')
}

// ConnectionDialog consumes no context (parent owns persistence), so a plain
// render() suffices — no providers needed.
function renderDialog(props: Partial<ConnectionDialogProps> = {}) {
  const user = userEvent.setup()
  const result = render(
    <ConnectionDialog
      onSave={vi.fn().mockResolvedValue(true)}
      onCancel={vi.fn()}
      onTest={vi.fn()}
      {...props}
    />
  )
  return { user, ...result }
}

describe('ConnectionDialog', () => {
  it('renders no dialog while the parent has not mounted it (Radix Portal closed state)', () => {
    const { getByRole, queryByRole } = render(
      <DialogMountHarness onCancel={vi.fn()} onSave={vi.fn()} onTest={vi.fn()} />
    )
    expect(getByRole('button', { name: /Open connection dialog/ })).toBeInTheDocument()
    expect(queryByRole('dialog')).toBeNull()
  })

  it('mounts the form into a body portal on open and removes the portal on Cancel (onCancel)', async () => {
    const onCancel = vi.fn()
    const user = userEvent.setup()
    const { getByRole, queryByRole, findByRole, getByPlaceholderText, container } =
      render(
        <DialogMountHarness
          onCancel={onCancel}
          onSave={vi.fn().mockResolvedValue(true)}
          onTest={vi.fn()}
        />
      )
    await user.click(getByRole('button', { name: /Open connection dialog/ }))
    const dialog = await findByRole('dialog')
    expect(getByPlaceholderText('My Database')).toBeInTheDocument()
    // Radix Dialog.Portal renders outside the React tree we mounted into,
    // but inside document.body
    expect(document.body).toContainElement(dialog)
    expect(container).not.toContainElement(dialog)

    await user.click(getByRole('button', { name: 'Cancel' }))
    expect(onCancel).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(queryByRole('dialog')).toBeNull())
    // portal node fully removed (no detached .dialog left in body)
    expect(document.querySelectorAll('.dialog')).toHaveLength(0)
    // harness itself survives
    expect(getByRole('button', { name: /Open connection dialog/ })).toBeInTheDocument()
  })

  it('shows form defaults and keeps Save/Test disabled until required fields are filled', async () => {
    const { user, getByRole, getByPlaceholderText, getByText, queryByRole } = renderDialog()
    expect(getByText('New Connection')).toBeInTheDocument()
    expect(getByPlaceholderText('My Database')).toHaveValue('')
    expect(getByPlaceholderText('localhost')).toHaveValue('localhost')
    expect(getByRole('spinbutton')).toHaveValue(5432)
    expect(getByRole('combobox')).toHaveValue('postgresql')
    expect(getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(getByRole('button', { name: 'Test Connection' })).toBeDisabled()
    expect(queryByRole('button', { name: 'Delete' })).toBeNull()

    await fillRequiredFields(user, { getByPlaceholderText })
    expect(getByRole('button', { name: 'Save' })).toBeEnabled()
    expect(getByRole('button', { name: 'Test Connection' })).toBeEnabled()
  })

  it('submits the complete ConnectionRequest via onSave for a new connection', async () => {
    const onSave = vi.fn().mockResolvedValue(true)
    const { user, getByRole, getByPlaceholderText } = renderDialog({ onSave })
    await fillRequiredFields(user, { getByPlaceholderText })
    await user.type(getByPlaceholderText('Enter password'), 'secret')
    await user.click(getByRole('button', { name: 'Save' }))
    expect(onSave).toHaveBeenCalledWith(undefined, { ...baseRequest, password: 'secret' })
  })

  it('stays open with an inline error when onSave resolves false (degraded path)', async () => {
    const onSave = vi.fn().mockResolvedValue(false)
    const onCancel = vi.fn()
    const { user, getByRole, getByPlaceholderText, queryByRole, findByText } = renderDialog({
      onSave,
      onCancel,
    })
    await fillRequiredFields(user, { getByPlaceholderText })
    await user.click(getByRole('button', { name: 'Save' }))

    expect(await findByText(/Could not confirm the connection was saved/)).toBeInTheDocument()
    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onCancel).not.toHaveBeenCalled()
    expect(queryByRole('dialog')).not.toBeNull()
    expect(getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('surfaces the parent-provided saveError prop inside the dialog', () => {
    const { getByText } = renderDialog({
      saveError: "A connection named 'Prod' already exists",
    })
    expect(getByText("A connection named 'Prod' already exists")).toBeInTheDocument()
  })

  it('calls onTest with the current request, shows a pending state, then success feedback', async () => {
    let resolveTest: (r: ConnectionTestResult) => void = () => {}
    const onTest = vi.fn(
      () =>
        new Promise<ConnectionTestResult>((res) => {
          resolveTest = res
        })
    )
    const { user, getByRole, getByPlaceholderText, findByText } = renderDialog({ onTest })
    await fillRequiredFields(user, { getByPlaceholderText })
    await user.click(getByRole('button', { name: 'Test Connection' }))
    expect(onTest).toHaveBeenCalledWith(baseRequest)
    expect(getByRole('button', { name: 'Testing...' })).toBeDisabled()

    resolveTest({ success: true, latency: 12, serverVersion: '15.2' })
    expect(await findByText('Connected (12ms) — PostgreSQL 15.2')).toBeInTheDocument()
    await waitFor(() =>
      expect(getByRole('button', { name: 'Test Connection' })).toBeEnabled()
    )
  })

  it('shows the failure message when the connection test fails', async () => {
    const onTest = vi.fn().mockResolvedValue({ success: false, error: 'ECONNREFUSED' })
    const { user, getByRole, getByPlaceholderText, findByText } = renderDialog({ onTest })
    await fillRequiredFields(user, { getByPlaceholderText })
    await user.click(getByRole('button', { name: 'Test Connection' }))
    expect(await findByText('Connection failed: ECONNREFUSED')).toBeInTheDocument()
  })

  it('renders the application timeout message for a connect-timeout failure', async () => {
    const onTest = vi.fn().mockResolvedValue({
      success: false,
      error: 'Connection timed out after 5s',
      errorReason: 'connect-timeout',
    })
    const { user, getByRole, getByPlaceholderText, findByText, queryByText } = renderDialog({ onTest })
    await fillRequiredFields(user, { getByPlaceholderText })
    await user.click(getByRole('button', { name: 'Test Connection' }))

    expect(await findByText('Connection timed out after 5s')).toBeInTheDocument()
    expect(queryByText(/^Connection failed:/)).toBeNull()
  })

  it('keeps the Connection failed shape for an other-classified failure', async () => {
    const onTest = vi.fn().mockResolvedValue({
      success: false,
      error: 'FATAL: password authentication failed',
      errorReason: 'other',
    })
    const { user, getByRole, getByPlaceholderText, findByText } = renderDialog({ onTest })
    await fillRequiredFields(user, { getByPlaceholderText })
    await user.click(getByRole('button', { name: 'Test Connection' }))

    expect(
      await findByText('Connection failed: FATAL: password authentication failed')
    ).toBeInTheDocument()
  })

  it('prefills an existing connection, saves it back with its id, and offers Delete', async () => {
    const editing: ConnectionConfig = {
      id: 'conn-1',
      name: 'Prod PG',
      host: 'db.example.com',
      port: 5433,
      database: 'prod',
      username: 'admin',
      dialect: 'postgresql',
      keychainRef: 'keychain:conn-1',
      readonly: false,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    }
    const onSave = vi.fn().mockResolvedValue(true)
    const onDelete = vi.fn()
    const { user, getByRole, getByPlaceholderText, getByText } = renderDialog({
      editingConnection: editing,
      onSave,
      onDelete,
    })
    expect(getByText('Edit Connection')).toBeInTheDocument()
    const nameInput = getByPlaceholderText('My Database')
    expect(nameInput).toHaveValue('Prod PG')
    expect(getByRole('spinbutton')).toHaveValue(5433) // editingConnection.port is kept, not reset

    await user.clear(nameInput)
    await user.type(nameInput, 'Prod PG v2')
    await user.click(getByRole('button', { name: 'Save' }))
    expect(onSave).toHaveBeenCalledWith('conn-1', {
      connectionId: 'conn-1',
      ...baseRequest,
      name: 'Prod PG v2',
      host: 'db.example.com',
      port: 5433,
      database: 'prod',
      username: 'admin',
    })

    await user.click(getByRole('button', { name: 'Delete' }))
    expect(onDelete).toHaveBeenCalledWith('conn-1')
  })

  it('switching database type updates the default port and reveals the sqlserver trust-certificate field', async () => {
    const onSave = vi.fn().mockResolvedValue(true)
    const { user, getByRole, getByPlaceholderText, queryByRole } = renderDialog({ onSave })
    const dialectSelect = getByRole('combobox')
    expect(queryByRole('checkbox', { name: 'Trust server certificate (skip TLS certificate verification)' })).toBeNull()

    await user.selectOptions(dialectSelect, 'mysql')
    expect(getByRole('spinbutton')).toHaveValue(3306)
    expect(queryByRole('checkbox', { name: 'Trust server certificate (skip TLS certificate verification)' })).toBeNull()

    await user.selectOptions(dialectSelect, 'sqlserver')
    expect(getByRole('spinbutton')).toHaveValue(1433)
    const trust = getByRole('checkbox', { name: /Trust server certificate/ })
    await user.click(trust)

    await fillRequiredFields(user, { getByPlaceholderText })
    await user.click(getByRole('button', { name: 'Save' }))
    expect(onSave).toHaveBeenCalledWith(undefined, {
      ...baseRequest,
      dialect: 'sqlserver',
      port: 1433,
      trustServerCertificate: true,
    })
  })

  it('oracle reveals the Connection Type selector and sends connectionType in the saved request', async () => {
    const onSave = vi.fn().mockResolvedValue(true)
    const { user, getByRole, getByPlaceholderText, getAllByRole } = renderDialog({ onSave })
    await user.selectOptions(getByRole('combobox'), 'oracle')
    expect(getByRole('spinbutton')).toHaveValue(1521)
    expect(getAllByRole('combobox')).toHaveLength(2) // dialect + connection type

    await user.selectOptions(getAllByRole('combobox')[1], 'SID')
    const databaseInput = getByPlaceholderText('SID') // placeholder flips from 'mydb' to 'SID'
    await user.type(getByPlaceholderText('My Database'), 'Test Ora')
    await user.type(databaseInput, 'orcl')
    await user.type(getByPlaceholderText('username'), 'scott')
    await user.click(getByRole('button', { name: 'Save' }))

    expect(onSave).toHaveBeenCalledWith(undefined, {
      name: 'Test Ora',
      host: 'localhost',
      port: 1521,
      database: 'orcl',
      username: 'scott',
      password: '',
      dialect: 'oracle',
      connectionType: 'SID',
      trustServerCertificate: undefined,
    })
  })
})
