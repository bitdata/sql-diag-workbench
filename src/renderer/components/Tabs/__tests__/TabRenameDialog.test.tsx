import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import TabRenameDialog from '../TabRenameDialog'

/**
 * TabRenameDialog is a Radix Dialog.Portal with `open` hard-coded
 * (TabRenameDialog.tsx:20) — always visible while mounted; OverlayHost
 * (OverlayHost.tsx:163-172) owns mount/unmount. Props are
 * { currentName, onConfirm(newName), onCancel } — no `open`/`onSubmit`.
 * The textbox is pre-filled with currentName (TabRenameDialog.tsx:15).
 */

function renderDialog(props: Partial<React.ComponentProps<typeof TabRenameDialog>> = {}) {
  const user = userEvent.setup()
  const result = render(
    <TabRenameDialog currentName="Old name" onConfirm={vi.fn()} onCancel={vi.fn()} {...props} />
  )
  return { user, ...result }
}

describe('TabRenameDialog', () => {
  it('renders title and pre-fills the textbox with currentName', async () => {
    const { getByRole, getByText } = renderDialog({ currentName: 'Quarterly Report' })
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toBeInTheDocument()
    expect(getByText('Rename tab')).toBeInTheDocument()
    const input = getByRole('textbox')
    expect(input).toHaveValue('Quarterly Report')
    expect(input).toHaveFocus() // autoFocus so the user can immediately retype
    expect(getByRole('button', { name: 'Rename' })).toBeEnabled()
    expect(getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('submits the trimmed new name via onConfirm("New name")', async () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    const { user, getByRole } = renderDialog({ onConfirm, onCancel })
    const input = getByRole('textbox')
    await user.clear(input) // real component starts pre-filled — brief assumed empty
    await user.type(input, '  New name  ')
    await user.click(getByRole('button', { name: 'Rename' }))
    expect(onConfirm).toHaveBeenCalledWith('New name') // exact trimmed string
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('Rename is disabled while the value is only whitespace, re-enabled on real input, and a disabled click confirms nothing', async () => {
    const onConfirm = vi.fn()
    const { user, getByRole } = renderDialog({ onConfirm })
    const input = getByRole('textbox')
    const submit = getByRole('button', { name: 'Rename' })

    await user.clear(input)
    expect(submit).toBeDisabled()

    await user.type(input, '   ')
    expect(submit).toBeDisabled() // trim() semantics, not just length
    await user.click(submit)
    expect(onConfirm).not.toHaveBeenCalled()

    await user.type(input, 'x')
    expect(submit).toBeEnabled()
  })

  it('Enter in the textbox submits the trimmed name', async () => {
    const onConfirm = vi.fn()
    const { user, getByRole } = renderDialog({ onConfirm })
    const input = getByRole('textbox')
    await user.clear(input)
    await user.type(input, 'Entered name ')
    await user.keyboard('{Enter}')
    expect(onConfirm).toHaveBeenCalledWith('Entered name')
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it('Enter with only whitespace does not submit', async () => {
    const onConfirm = vi.fn()
    const { user, getByRole } = renderDialog({ onConfirm })
    const input = getByRole('textbox')
    await user.clear(input)
    await user.type(input, '  ')
    await user.keyboard('{Enter}')
    expect(onConfirm).not.toHaveBeenCalled()
    expect(input).toBeInTheDocument() // dialog stays open on rejected submit
  })

  it('Cancel button fires onCancel and never onConfirm', async () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    const { user, getByRole } = renderDialog({ onConfirm, onCancel })
    await user.click(getByRole('button', { name: 'Cancel' }))
    expect(onCancel).toHaveBeenCalled()
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('Escape in the textbox cancels (input onKeyDown + Radix dismiss)', async () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    const { user, getByRole } = renderDialog({ onConfirm, onCancel })
    await user.click(getByRole('textbox'))
    await user.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalled()
    expect(onConfirm).not.toHaveBeenCalled()
    // open is hard-coded — visibility is the parent's job, so the dialog
    // node itself remains until the parent unmounts it
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('syncs the input when the parent swaps currentName, and the synced value still submits', async () => {
    const onConfirm = vi.fn()
    const user = userEvent.setup()
    const { rerender, getByRole } = render(
      <TabRenameDialog currentName="First" onConfirm={onConfirm} onCancel={vi.fn()} />
    )
    expect(getByRole('textbox')).toHaveValue('First')

    rerender(<TabRenameDialog currentName="Second" onConfirm={onConfirm} onCancel={vi.fn()} />)
    const input = getByRole('textbox')
    expect(input).toHaveValue('Second') // prop-sync effect (TabRenameDialog.tsx:17)
    // the synced value is real state, not a stale render: submitting uses it
    await user.clear(input)
    await user.type(input, 'Edited')
    await user.keyboard('{Enter}')
    expect(onConfirm).toHaveBeenCalledWith('Edited')
  })
})
