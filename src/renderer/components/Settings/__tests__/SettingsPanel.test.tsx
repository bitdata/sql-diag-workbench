import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import SettingsPanel from '../SettingsPanel'

/** Settings use a Radix modal and emit the full settings snapshot immediately. */

type SettingsPanelProps = React.ComponentProps<typeof SettingsPanel>
type Settings = SettingsPanelProps['settings']

// Values deliberately different from DEFAULT_SETTINGS (SettingsPanel.tsx:19-25)
// so an assertion failure can't be masked by the component's defaults.
const baseSettings: Settings = {
  dialect: 'mysql',
  rowLimit: 500,
  queryTimeout: 15,
  autoCleanupDays: 7,
  theme: 'light',
}

function renderPanel(props: Partial<SettingsPanelProps> = {}) {
  const user = userEvent.setup()
  const onChange = vi.fn()
  const onClose = vi.fn()
  const result = render(
    <SettingsPanel settings={baseSettings} onChange={onChange} onClose={onClose} {...props} />
  )
  return { user, onChange, onClose, ...result }
}

function dialectSelect() {
  return screen.getByRole('combobox', { name: 'Default offline dialect' })
}

describe('SettingsPanel', () => {
  it('renders header and reflects the `settings` prop (not internal defaults)', () => {
    renderPanel()
    expect(screen.getByRole('heading', { level: 2, name: 'Settings' })).toBeInTheDocument()
    expect(screen.getByTitle('Close')).toBeInTheDocument()
    expect(dialectSelect()).toHaveValue('mysql')
    expect(screen.getByRole('combobox', { name: 'Theme' })).toHaveValue('light')
    expect(screen.getByRole('spinbutton', { name: 'Row limit' })).toHaveValue(500)
    expect(screen.getByRole('spinbutton', { name: 'Timeout (seconds)' })).toHaveValue(15)
    expect(screen.getByRole('spinbutton', { name: 'Auto-cleanup after (days)' })).toHaveValue(7)
  })

  it('Escape closes; clicking inside the panel closes nothing and changes nothing', async () => {
    const { user, onChange, onClose } = renderPanel()
    const panel = screen.getByRole('dialog')

    // Interacting inside the modal must not dismiss it.
    await user.click(screen.getByRole('heading', { level: 2, name: 'Settings' }))
    await user.click(panel)
    expect(onClose).not.toHaveBeenCalled()
    expect(onChange).not.toHaveBeenCalled()

    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onChange).not.toHaveBeenCalled()
  })

  it('Close button (✕) fires onClose only', async () => {
    const { user, onChange, onClose } = renderPanel()
    await user.click(screen.getByTitle('Close'))
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onChange).not.toHaveBeenCalled()
  })

  it('changing dialect calls onChange with the complete next settings object', async () => {
    const { user, onChange, onClose } = renderPanel()
    await user.selectOptions(dialectSelect(), 'postgresql')
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith({ ...baseSettings, dialect: 'postgresql' })
    expect(onClose).not.toHaveBeenCalled()
  })

  it('changing theme calls onChange with the complete next settings object', async () => {
    const { user, onChange } = renderPanel()
    await user.selectOptions(screen.getByRole('combobox', { name: 'Theme' }), 'high-contrast')
    expect(onChange).toHaveBeenCalledWith({ ...baseSettings, theme: 'high-contrast' })
  })

  it('editing each numeric field emits the full accumulated settings object', async () => {
    const { user, onChange } = renderPanel()

    // Atomic fill (not clear+type) so the transient ""→clamp(min) re-render
    // doesn't bleed into the next typed digit (e.g. clear → "1" → "42" →
    // "142" instead of "42"). This mirrors how a human would paste/replace a
    // value, which is what these "expect onChange arg shape" tests exercise.
    const rowLimit = screen.getByRole('spinbutton', { name: 'Row limit' })
    await user.clear(rowLimit)
    await user.type(rowLimit, '42')
    expect(onChange).toHaveBeenLastCalledWith({ ...baseSettings, rowLimit: 42 })

    // onChange rebuilds from `local`, so each full object carries prior edits
    const timeout = screen.getByRole('spinbutton', { name: 'Timeout (seconds)' })
    await user.clear(timeout)
    await user.type(timeout, '90')
    expect(onChange).toHaveBeenLastCalledWith({ ...baseSettings, rowLimit: 42, queryTimeout: 90 })

    const cleanup = screen.getByRole('spinbutton', { name: 'Auto-cleanup after (days)' })
    await user.clear(cleanup)
    await user.type(cleanup, '3')
    expect(onChange).toHaveBeenLastCalledWith({
      ...baseSettings,
      rowLimit: 42,
      queryTimeout: 90,
      autoCleanupDays: 3,
    })
  })

  it('an empty numeric field coerces to 0 and the onChange emit is clamped to min', async () => {
    const { user, onChange } = renderPanel()
    const rowLimit = screen.getByRole('spinbutton', { name: 'Row limit' })
    // value '' → parseInt('') || 0 → 0. Local state mirrors the parsed value
    // (so the transient "" → 0 doesn't get re-rendered as "1" and bleed into
    // the next typed digit); the onChange EMIT is clamped to min=1 so the
    // parent never persists an out-of-range value.
    await user.clear(rowLimit)
    expect(onChange).toHaveBeenLastCalledWith({ ...baseSettings, rowLimit: 1 })
    expect(rowLimit).toHaveValue(0) // local state echoes the parsed 0, not the clamped 1
  })

  it('clamps out-of-range numeric input to NUMERIC_BOUNDS (min + max)', async () => {
    const { user, onChange } = renderPanel()

    // rowLimit: above max (100_000) → clamped to 100_000; below min (1) clamped
    // by the empty-string case above, so we focus on the over-max path here.
    const rowLimit = screen.getByRole('spinbutton', { name: 'Row limit' })
    await user.clear(rowLimit)
    await user.type(rowLimit, '999999')
    expect(onChange).toHaveBeenLastCalledWith({ ...baseSettings, rowLimit: 100_000 })

    // autoCleanupDays: above max (365) → clamped to 365
    const cleanup = screen.getByRole('spinbutton', { name: 'Auto-cleanup after (days)' })
    await user.clear(cleanup)
    await user.type(cleanup, '1000')
    expect(onChange).toHaveBeenLastCalledWith({
      ...baseSettings,
      rowLimit: 100_000,
      autoCleanupDays: 365,
    })
  })

  it('sequential edits accumulate in local state (second onChange carries the first edit)', async () => {
    const { user, onChange } = renderPanel()
    await user.selectOptions(dialectSelect(), 'postgresql')
    const rowLimit = screen.getByRole('spinbutton', { name: 'Row limit' })
    await user.clear(rowLimit)
    await user.type(rowLimit, '42')
    // onChange is fed from `local`, not from the original `settings` prop —
    // a regression that rebuilt from props would drop the dialect change
    expect(onChange).toHaveBeenLastCalledWith({
      ...baseSettings,
      dialect: 'postgresql',
      rowLimit: 42,
    })
    // input values follow local state, not the frozen prop
    expect(dialectSelect()).toHaveValue('postgresql')
    expect(rowLimit).toHaveValue(42)
  })
})
