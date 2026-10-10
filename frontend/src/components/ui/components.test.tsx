import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Change } from '@/features/market/Change'
import { QualityBadge } from '@/features/market/QualityBadge'
import { Popover } from './Popover'
import { Segmented } from './Segmented'

describe('Change (§22.7: color is never the only signal)', () => {
  it('shows arrow, explicit sign and percentage for gains', () => {
    const { container } = render(<Change change="3.1200" pct="0.0139" reference="227.40" />)
    expect(container.textContent).toBe('▲+3.12(+1.39%)')
    expect(container.firstElementChild?.className).toContain('text-up')
  })

  it('uses a real minus sign and the down color for losses', () => {
    const { container } = render(<Change change="-0.82" pct="-0.0036" />)
    expect(container.textContent).toBe('▼−0.82(−0.36%)')
    expect(container.firstElementChild?.className).toContain('text-down')
  })

  it('renders -- for missing values', () => {
    const { container } = render(<Change change={null} pct={null} />)
    expect(container.textContent).toBe('--(--)')
  })
})

describe('QualityBadge', () => {
  it('labels simulated and estimated data, and hides consolidated real-time data', () => {
    const { rerender, container } = render(<QualityBadge quality="SIMULATED" />)
    expect(screen.getByText('SIM')).toBeTruthy()
    rerender(<QualityBadge quality="SYNTHETIC" />)
    expect(screen.getByText('Est.')).toBeTruthy()
    rerender(<QualityBadge quality="REALTIME_CONSOLIDATED" />)
    expect(container.textContent).toBe('')
  })
})

describe('Segmented', () => {
  it('reports the selected value and ignores disabled options', async () => {
    const onChange = vi.fn<(v: 'BUY' | 'SELL') => void>()
    render(
      <Segmented
        label="Side"
        value="BUY"
        onChange={onChange}
        options={[
          { value: 'BUY', label: 'Buy', tone: 'up' },
          { value: 'SELL', label: 'Sell', tone: 'down', disabled: true },
        ]}
      />,
    )
    const user = userEvent.setup()
    expect(screen.getByRole('radio', { name: 'Buy' }).getAttribute('aria-checked')).toBe('true')
    await user.click(screen.getByRole('radio', { name: 'Sell' }))
    expect(onChange).not.toHaveBeenCalled()
    await user.click(screen.getByRole('radio', { name: 'Buy' }))
    expect(onChange).toHaveBeenCalledWith('BUY')
  })
})

// Review regression: the league's formula popovers opened past the viewport edge (left −78 px) and were cut off
describe('Popover collision handling', () => {
  it('shifts a panel that would open past an edge back inside the viewport', async () => {
    const rect = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return (this.getAttribute('role') === 'dialog' ? { left: -78, right: 302, top: 40, bottom: 300, width: 380, height: 260 } : { left: 0, right: 0, top: 0, bottom: 0, width: 0, height: 0 }) as DOMRect
    })
    try {
      render(
        <Popover label="How the league is scored" renderTrigger={(p) => <button type="button" {...p}>info</button>}>
          formulas
        </Popover>,
      )
      await userEvent.click(screen.getByRole('button', { name: 'info' }))
      expect(screen.getByRole('dialog', { name: 'How the league is scored' }).style.transform).toBe('translateX(86px)')
    } finally {
      rect.mockRestore()
    }
  })
})
