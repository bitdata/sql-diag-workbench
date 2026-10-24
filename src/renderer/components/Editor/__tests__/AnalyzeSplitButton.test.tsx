import { describe, it, expect, vi } from 'vitest'
import { render, waitFor, within, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AnalyzeSplitButton } from '../AnalyzeSplitButton'

const DEFAULT_TITLE = 'Analyze fragment (open menu to choose estimated or actual)'

describe('AnalyzeSplitButton', () => {
  it('renders the trigger button with the default title and no menu in the DOM (Radix Portal closed state)', () => {
    const { getByRole, queryByRole } = render(
      <AnalyzeSplitButton backendUrl="http://localhost:8080" connectionId="conn-1" onAnalyze={vi.fn()} disabled={false} />
    )
    const trigger = getByRole('button', { name: /Analyze/ })
    expect(trigger).toBeEnabled()
    expect(trigger).toHaveAttribute('title', DEFAULT_TITLE)
    // DropdownMenu.Portal renders nothing while closed
    expect(queryByRole('menu')).toBeNull()
    expect(queryByRole('menuitem')).toBeNull()
  })

  it('opens the dropdown into a body portal on trigger click, listing both plan modes', async () => {
    const onAnalyze = vi.fn()
    const user = userEvent.setup()
    const { getByRole, findByRole } = render(
      <AnalyzeSplitButton backendUrl="http://localhost:8080" connectionId="conn-1" onAnalyze={onAnalyze} disabled={false} />
    )
    await user.click(getByRole('button', { name: /Analyze/ }))
    const menu = await findByRole('menu')
    expect(document.body).toContainElement(menu)
    expect(within(menu).getByRole('menuitem', { name: /Estimated Plan/ })).toBeInTheDocument()
    expect(
      within(menu).getByRole('menuitem', { name: /Actual Plan \(ANALYZE\)/ })
    ).toBeInTheDocument()
    // component doc: clicking the trigger alone does NOT start analysis
    expect(onAnalyze).not.toHaveBeenCalled()
  })

  it('fires onAnalyze("estimated") — and only that — when Estimated Plan is selected', async () => {
    const onAnalyze = vi.fn()
    const user = userEvent.setup()
    const { getByRole, findByRole, queryByRole } = render(
      <AnalyzeSplitButton backendUrl="http://localhost:8080" connectionId="conn-1" onAnalyze={onAnalyze} disabled={false} />
    )
    await user.click(getByRole('button', { name: /Analyze/ }))
    await waitFor(() => expect(getByRole('menuitem', { name: /Estimated Plan/ })).not.toHaveAttribute('data-disabled'))
    await user.click(await findByRole('menuitem', { name: /Estimated Plan/ }))
    expect(onAnalyze).toHaveBeenCalledTimes(1)
    expect(onAnalyze).toHaveBeenCalledWith('estimated')
    // menu closes after selection
    await waitFor(() => expect(queryByRole('menu')).toBeNull())
  })

  it('fires onAnalyze("actual") when Actual Plan is selected', async () => {
    const onAnalyze = vi.fn()
    const user = userEvent.setup()
    const { getByRole, findByRole, queryByRole } = render(
      <AnalyzeSplitButton backendUrl="http://localhost:8080" connectionId="conn-1" onAnalyze={onAnalyze} disabled={false} />
    )
    await user.click(getByRole('button', { name: /Analyze/ }))
    await waitFor(() => expect(getByRole('menuitem', { name: /Actual Plan/ })).not.toHaveAttribute('data-disabled'))
    await user.click(await findByRole('menuitem', { name: /Actual Plan \(ANALYZE\)/ }))
    expect(onAnalyze).toHaveBeenCalledTimes(1)
    expect(onAnalyze).toHaveBeenCalledWith('actual')
    await waitFor(() => expect(queryByRole('menu')).toBeNull())
  })

  it('disables the trigger and keeps the menu closed when disabled=true', () => {
    const onAnalyze = vi.fn()
    const { getByRole, queryByRole } = render(
      <AnalyzeSplitButton backendUrl="http://localhost:8080" connectionId="conn-1" onAnalyze={onAnalyze} disabled={true} />
    )
    const trigger = getByRole('button', { name: /Analyze/ })
    // The `disabled` attribute is the real-browser guarantee (Chrome suppresses
    // pointer/keyboard events on disabled buttons). userEvent v14 is not faithful
    // here — it still dispatches pointerdown, which Radix opens on — so drive the
    // click via fireEvent instead.
    expect(trigger).toBeDisabled()
    fireEvent.click(trigger)
    expect(queryByRole('menu')).toBeNull()
    expect(onAnalyze).not.toHaveBeenCalled()
  })

  it('honors a custom title prop', () => {
    const { getByRole } = render(
      <AnalyzeSplitButton backendUrl="http://localhost:8080" connectionId="conn-1" onAnalyze={vi.fn()} disabled={false} title="Analyze selected fragment" />
    )
    expect(getByRole('button', { name: /Analyze/ })).toHaveAttribute(
      'title',
      'Analyze selected fragment'
    )
  })
})
