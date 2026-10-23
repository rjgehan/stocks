import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/lib/api/queryKeys'
import type { OptionChain as Chain, OptionContract, OptionExpirations } from '@/lib/api/types'
import { formatOption } from '@/lib/options'
import { renderWithRouter } from '@/test/router'
import { OptionChain } from './OptionChain'

const EXP = '2026-11-20'

function contract(right: 'CALL' | 'PUT', strike: string, bid: string, ask: string): OptionContract {
  return {
    symbol: formatOption({ underlying: 'AAPL', expiration: EXP, right, strike }),
    underlying: 'AAPL',
    expiration: EXP,
    right,
    strike,
    bid,
    bidSize: 10,
    ask,
    askSize: 12,
    mid: null,
    last: bid,
    volume: 120,
    openInterest: 3400,
    impliedVolatility: '0.2500',
    delta: null,
    quoteAt: '2026-10-23T15:00:00Z',
    quality: 'INDICATIVE',
  }
}

function seed(feed: string, enabled = true): QueryClient {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  const expirations: OptionExpirations = { underlying: 'AAPL', enabled, feed, multiplier: 100, expirations: [EXP, '2026-12-18'] }
  const chain: Chain = {
    underlying: 'AAPL',
    expiration: EXP,
    enabled,
    feed,
    multiplier: 100,
    underlyingPrice: '252.10',
    asOf: '2026-10-23T15:00:00Z',
    calls: [contract('CALL', '245', '9.10', '9.30'), contract('CALL', '250', '5.60', '5.80'), contract('CALL', '255', '3.10', '3.25')],
    puts: [contract('PUT', '245', '1.90', '2.00'), contract('PUT', '250', '3.40', '3.55'), contract('PUT', '255', '5.95', '6.10')],
  }
  qc.setQueryData(queryKeys.optionExpirations('AAPL'), expirations)
  qc.setQueryData(queryKeys.optionChain('AAPL', EXP), chain)
  qc.setQueryData(queryKeys.positions, [])
  return qc
}

describe('the Options tab (§30)', () => {
  it('shows the nearest expiration with days to expiry, shades in-the-money strikes, marks the money and selects a row', async () => {
    const onSelect = vi.fn<(contract: string) => void>()
    renderWithRouter(<OptionChain underlying="AAPL" selected={undefined} onSelect={onSelect} />, seed('indicative'))
    const table = await screen.findByRole('table', { name: /AAPL calls expiring Nov 20/ })
    expect(screen.getByRole('radio', { name: /^Nov 20, \d+ days to expiry$/ }).getAttribute('aria-checked')).toBe('true')
    const rows = within(table).getAllByRole('row').slice(1)
    // calls: the 245 and 250 strikes are below 252.10, so in the money; 250 is the strike nearest the money
    expect(rows.map((r) => r.hasAttribute('data-itm'))).toEqual([true, true, false])
    expect(within(rows[1]!).getByText('ATM')).toBeTruthy()
    expect(rows[2]!.textContent).toContain('3.10')
    fireEvent.click(within(rows[2]!).getByRole('button', { name: 'Trade AAPL 20 Nov 26 $255 Call' }))
    expect(onSelect).toHaveBeenCalledWith('AAPL261120C00255000')
    // the indicative feed is called out
    expect(screen.getByTestId('indicative-note').textContent).toContain('not the consolidated OPRA quotes')

    fireEvent.click(screen.getByRole('radio', { name: 'Puts' }))
    const puts = screen.getByRole('table', { name: /AAPL puts/ })
    expect(within(puts).getAllByRole('row').slice(1).map((r) => r.hasAttribute('data-itm'))).toEqual([false, false, true])
  })

  it('says when options trading is off, and has no indicative note on another feed', async () => {
    renderWithRouter(<OptionChain underlying="AAPL" selected="AAPL261120P00250000" onSelect={() => {}} />, seed('opra', false))
    expect((await screen.findByRole('status')).textContent).toContain('Options trading is off')
    expect(screen.queryByTestId('indicative-note')).toBeNull()
    // the selected contract opens its side and marks its row
    const puts = screen.getByRole('table', { name: /AAPL puts/ })
    expect(within(puts).getAllByRole('row')[2]!.getAttribute('aria-selected')).toBe('true')
  })
})
