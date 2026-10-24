import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import CloseTabConfirmDialog from '../CloseTabConfirmDialog'

/**
 * CloseTabConfirmDialog is a Radix Dialog.Portal with `open` hard-coded
 * (CloseTabConfirmDialog.tsx:21) — there is no closed state while mounted.
 * The parent (OverlayHost.tsx:175-188) owns visibility by mounting/unmounting
 * it, so the "closed" contract is pinned with a mount/unmount harness, the
 * same idiom as ConnectionDialog.test.tsx.
 *
 * Role is `dialog`, not `alertdialog` (deliberate — built on react-dialog,
 * no separate AlertDialog dependency).
 */

function MountHarness({ onCancel, ...rest }: { onCancel: () => void; tabName: string; onDiscard: () => void; onSave: () => void }) {
  const [show, setShow] = useState(false)
  return (
    <>
      <button onClick={() => setShow(true)}>Open close confirmation</button>
      {show && (
        <CloseTabConfirmDialog
          {...rest}
          onCancel={() => {
            setShow(false) // parent unmounts → Portal cleanup
            onCancel()
          }}
        />
      )}
    </>
  )
}

function renderDialog(props: Partial<React.ComponentProps<typeof CloseTabConfirmDialog>> = {}) {
  const user = userEvent.setup()
  const result = render(
    <CloseTabConfirmDialog
      tabName="Query 2"
      onCancel={vi.fn()}
      onDiscard={vi.fn()}
      onSave={vi.fn()}
      {...props}
    />
  )
  return { user, ...result }
}

describe('CloseTabConfirmDialog', () => {
  it('renders no dialog while the parent has not mounted it (Radix Portal closed state)', () => {
    const { getByRole, queryByRole } = render(
      <MountHarness onCancel={vi.fn()} tabName="Query 2" onDiscard={vi.fn()} onSave={vi.fn()} />
    )
    expect(getByRole('button', { name: /Open close confirmation/ })).toBeInTheDocument()
    expect(queryByRole('dialog')).toBeNull()
  })

  it('mounts into a body portal showing the tab name, and the parent unmounts it on Cancel', async () => {
    const onCancel = vi.fn()
    const user = userEvent.setup()
    const { getByRole, queryByRole, findByRole, getByText, container } = render(
      <MountHarness
        onCancel={onCancel}
        tabName="Query 2"
        onDiscard={vi.fn()}
        onSave={vi.fn()}
      />
    )
    await user.click(getByRole('button', { name: /Open close confirmation/ }))
    const dialog = await findByRole('dialog')
    expect(getByText('Close "Query 2"?')).toBeInTheDocument()
    // Portal renders inside document.body but outside the mounted React tree
    expect(document.body).toContainElement(dialog)
    expect(container).not.toContainElement(dialog)

    await user.click(getByRole('button', { name: 'Cancel' }))
    // Cancel button no longer carries onClick — Radix Dialog.Close -> onOpenChange
    // is the single path (was double-firing pre-fix). In jsdom Radix fires the
    // open-change handler exactly once on click; assert the count to lock that in.
    expect(onCancel).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(queryByRole('dialog')).toBeNull())
    // harness survives the dialog's unmount
    expect(getByRole('button', { name: /Open close confirmation/ })).toBeInTheDocument()
  })

  it('names the tab and warns about unsaved changes', () => {
    renderDialog({ tabName: 'My Report' })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('Close "My Report"?')).toBeInTheDocument()
    expect(screen.getByText(/unsaved changes/i)).toBeInTheDocument()
  })

  it('Discard fires onDiscard only — not onCancel/onSave', async () => {
    const onDiscard = vi.fn()
    const onCancel = vi.fn()
    const onSave = vi.fn()
    const { user, getByRole } = renderDialog({ onDiscard, onCancel, onSave })
    await user.click(getByRole('button', { name: 'Discard' }))
    expect(onDiscard).toHaveBeenCalledTimes(1)
    expect(onSave).not.toHaveBeenCalled()
    // Cancel's onClick was not hit; Dialog.Close onOpenChange path belongs to Cancel only
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('"Save first" fires onSave only — not onDiscard/onCancel', async () => {
    const onDiscard = vi.fn()
    const onCancel = vi.fn()
    const onSave = vi.fn()
    const { user, getByRole } = renderDialog({ onDiscard, onCancel, onSave })
    await user.click(getByRole('button', { name: 'Save first' }))
    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onDiscard).not.toHaveBeenCalled()
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('Escape routes the Radix dismiss through onOpenChange to onCancel', () => {
    const onCancel = vi.fn()
    const onDiscard = vi.fn()
    renderDialog({ onCancel, onDiscard })
    // DismissableLayer listens for Escape on the document of the modal layer;
    // open is hard-coded, so the dialog itself stays mounted (parent unmounts).
    fireEvent.keyDown(document.body, { key: 'Escape' })
    // Escape triggers Radix Dialog.Close -> onOpenChange(false) -> onCancel
    // exactly once. The route is locked; the exact call count depends on
    // Radix's internal DismissableLayer dispatch (jsdom-stable here).
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(onDiscard).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
