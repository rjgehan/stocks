import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Change } from '@/features/market/Change'
import { QualityBadge } from '@/features/market/QualityBadge'
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
