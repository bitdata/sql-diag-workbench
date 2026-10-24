import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import TabContextMenu from '../TabContextMenu'

/**
 * TabContextMenu is NOT a Radix ContextMenu: it is an already-open floating
 * menu (the parent, OverlayHost, mounts it at {x, y} on right-click and
 * unmounts it via onDismiss). There is no trigger/children API and no portal,
 * so everything is queryable from the container. Keyboard dismissal attaches
 * immediately; only outside-click registration waits for setTimeout(0).
 *
 * The component consumes no context → plain render() (same precedent as
 * ConnectionDialog.test.tsx).
 */

/** Resolve after one macrotask so the component's deferred
 *  document listener registration (setTimeout 0) has run. Our timer is
 *  scheduled after the component's, so it fires after the listeners attach. */
const flushMacrotask = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

type MenuCallbacks = {
  onRename: () => void
  onClose: () => void
  onCloseOthers: () => void
  onCloseAll: () => void
  onDismiss: () => void
}

function renderMenu(overrides: Partial<MenuCallbacks> = {}) {
  const callbacks: MenuCallbacks = {
    onRename: vi.fn(),
    onClose: vi.fn(),
    onCloseOthers: vi.fn(),
    onCloseAll: vi.fn(),
    onDismiss: vi.fn(),
    ...overrides,
  }
  const user = userEvent.setup()
  const result = render(
    <TabContextMenu x={340} y={120} {...callbacks} />
  )
  return { user, ...result, ...callbacks }
}

describe('TabContextMenu', () => {
  it('renders an open floating menu at (x, y) with the four actions', () => {
    const { container } = renderMenu()
    const menu = screen.getByRole('menu')
    expect(menu).toBeInTheDocument()
    // x maps to left, y maps to top — catches an x/y swap
    expect(menu).toHaveStyle({ left: '340px', top: '120px' })
    // Items are plain buttons (NOT menuitems — component has no Radix)
    expect(screen.getByRole('button', { name: 'Rename…' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Close others' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Close all' })).toBeInTheDocument()
    // All four live inside the floating div (not a detached portal node)
    expect(container).toContainElement(menu)
  })

  it('each action fires exactly its own callback and never onDismiss', async () => {
    const user = userEvent.setup()
    const { onRename, onClose, onCloseOthers, onCloseAll, onDismiss } = renderMenu()

    await user.click(screen.getByRole('button', { name: 'Rename…' }))
    expect(onRename).toHaveBeenCalledTimes(1)
    expect(onClose).not.toHaveBeenCalled()
    expect(onDismiss).not.toHaveBeenCalled() // click target is inside the menu

    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onRename).toHaveBeenCalledTimes(1) // no cross-firing

    await user.click(screen.getByRole('button', { name: 'Close others' }))
    expect(onCloseOthers).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: 'Close all' }))
    expect(onCloseAll).toHaveBeenCalledTimes(1)
    expect(onDismiss).not.toHaveBeenCalled()
  })

  it('Escape dismisses immediately, before deferred outside-click registration', () => {
    const onDismiss = vi.fn()
    renderMenu({ onDismiss })

    // Keyboard dismissal has no relationship to the opening mouse event.
    fireEvent.keyDown(document.body, { key: 'Escape' })
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('outside mousedown dismisses after the defer window, inside mousedown never does', async () => {
    const onDismiss = vi.fn()
    const { container } = renderMenu({ onDismiss })

    // The mousedown that opened the menu must not dismiss it: listeners
    // attach in setTimeout(0), so an immediate outside mousedown is a no-op.
    fireEvent.mouseDown(document.body)
    expect(onDismiss).not.toHaveBeenCalled()
    expect(screen.getByRole('menu')).toBeInTheDocument()

    await flushMacrotask()
    // mousedown on a menu item (bubbles to document) must NOT dismiss
    fireEvent.mouseDown(screen.getByRole('button', { name: 'Close all' }))
    expect(onDismiss).not.toHaveBeenCalled()

    // mousedown outside the menu does dismiss
    fireEvent.mouseDown(document.body)
    expect(onDismiss).toHaveBeenCalledTimes(1)
    expect(container).toContainElement(screen.getByRole('menu')) // parent owns unmount
  })
})
