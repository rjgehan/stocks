import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/lib/api/queryKeys'
import type { Position, PublicPortfolio as Portfolio } from '@/lib/api/types'
import { publicOrder } from '@/test/fixtures'
import { renderWithRouter } from '@/test/router'
import { PublicPortfolio, byMarketValue, topHoldings } from './PublicPortfolio'

const position = (symbol: string, marketValue: string, weight: string): Position => ({
  symbol,
  name: `${symbol} Inc.`,
  qty: '1.5',
  availableQty: '1.5',
  avgCost: '100.0000',
  costBasis: '150.00',
  last: '110.00',
  mark: '110.00',
  markQuality: 'REALTIME_CONSOLIDATED',
  marketValue,
  unrealizedPnl: '15.00',
  unrealizedReturn: '0.1',
  dayPnl: '1.20',
  dayReturn: '0.008',
  weight,
  fractionable: true,
  openedAt: '2026-10-09T14:00:00Z',
})

function portfolio(over: Partial<Portfolio> = {}): Portfolio {
  return {
    player: { playerId: 'ply_1', handle: 'claude', displayName: 'Claude', kind: 'AI', aiModel: 'Claude', avatarSeed: 'claude', memberSince: '2026-10-01T13:00:00Z', resets: 0 },
    runNo: 1,
    asOf: '2026-10-13T18:30:05Z',
    marksQuality: 'REALTIME_CONSOLIDATED',
    equity: '520.60',
    longMarketValue: '330.00',
    dividendsReceivable: '0.00',
    cash: '190.60',
    settledCash: '180.60',
    unsettledCash: '10.00',
    buyingPower: '170.60',
    openBuyHolds: '20.00',
    netContributions: '500.00',
    totalPnl: '20.60',
    totalReturn: '0.0412',
    dayPnl: '2.40',
    dayReturn: '0.0046',
    unrealizedPnl: '30.00',
    restriction: 'NONE',
    restrictionUntil: null,
    gfvActive: 0,
    positions: [position('MSFT', '110.00', '0.2113'), position('NVDA', '220.00', '0.4226')],
    openOrders: [publicOrder()],
    ...over,
  }
}

function client(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
}

afterEach(() => vi.unstubAllGlobals())

describe("a player's public portfolio (public mode, §16.7)", () => {
  it('shows the live summary, the holdings by market value and the open orders with notes, without Cancel', async () => {
    const qc = client()
    qc.setQueryData(queryKeys.playerPortfolio('claude'), portfolio())
    renderWithRouter(<PublicPortfolio handle="claude" />, qc)
    const summary = await screen.findByLabelText('Portfolio summary')
    expect(summary.textContent).toContain('Cash')
    expect(summary.textContent).toContain('$190.60')
    expect(summary.textContent).toContain('$180.60 settled · $10.00 unsettled')
    expect(summary.textContent).toContain('Buying power')
    expect(summary.textContent).toContain('Open buy holds')
    expect(screen.getByText(/^Live · updated/)).toBeTruthy()

    // a table from tablet up, cards on phones (both are in the DOM; CSS shows one), largest first
    const table = screen.getByRole('table', { name: 'Holdings' })
    expect(within(table).getAllByRole('link').map((a) => a.textContent)).toEqual(['NVDA', 'MSFT'])
    expect(within(table).getByRole('link', { name: 'NVDA' }).getAttribute('href')).toBe('/stock/NVDA')
    expect(screen.getByRole('list', { name: 'Holdings' })).toBeTruthy()

    const open = screen.getByRole('list', { name: 'Open orders' })
    expect(open.textContent).toContain('2 sh AAPL · Limit 210.00 · GTC')
    expect(open.textContent).toContain('Working')
    expect(open.textContent).toContain('waiting for its price')
    expect(open.textContent).toContain('via API')
    expect(open.textContent).toContain('“Bid under the 20-day average.”')
    expect(screen.queryByRole('button', { name: /cancel/i })).toBeNull()
  })

  it('labels group legs and manual-console orders', async () => {
    const qc = client()
    const held = publicOrder({
      orderId: 'ord_2',
      side: 'SELL',
      type: 'STOP',
      limitPrice: null,
      stopPrice: '180.00',
      status: 'PENDING',
      waitReason: 'AWAITING_PARENT',
      group: { groupId: 'grp_1', type: 'BRACKET', role: 'STOP_LOSS', parentOrderId: 'ord_1', held: true },
    })
    const manual = publicOrder({ orderId: 'ord_3', channel: 'WEB', viaApi: false, manualSessionId: 'mbs_01J9Y4N2QK7D3X8M5R6T1V0WZB' })
    qc.setQueryData(queryKeys.playerPortfolio('claude'), portfolio({ openOrders: [held, manual] }))
    renderWithRouter(<PublicPortfolio handle="claude" />, qc)
    const open = await screen.findByRole('list', { name: 'Open orders' })
    expect(within(open).getByText('Bracket · stop loss · held')).toBeTruthy()
    expect(open.textContent).toContain('Waiting for entry')
    expect(within(open).getByText('Manual console')).toBeTruthy()
  })

  it('has its empty states: cash only, no open orders, no active run', async () => {
    const qc = client()
    qc.setQueryData(queryKeys.playerPortfolio('alice'), portfolio({ positions: [], openOrders: [] }))
    const { unmount } = renderWithRouter(<PublicPortfolio handle="alice" />, qc)
    expect(await screen.findByText('No positions: @alice holds only cash.')).toBeTruthy()
    expect(screen.getByText('No open orders.')).toBeTruthy()
    unmount()
    qc.setQueryData(queryKeys.playerPortfolio('bob'), portfolio({ runNo: 0, positions: [], openOrders: [] }))
    renderWithRouter(<PublicPortfolio handle="bob" />, qc)
    expect(await screen.findByText('No active run.')).toBeTruthy()
  })

  it('offers a retry when the portfolio cannot load', async () => {
    vi.stubGlobal('fetch', vi.fn<() => Promise<Response>>(async () => new Response(JSON.stringify({ status: 503, code: 'UNAVAILABLE', title: 'Unavailable' }), { status: 503, headers: { 'Content-Type': 'application/problem+json' } })))
    renderWithRouter(<PublicPortfolio handle="carol" />, client())
    expect(await screen.findByText('Portfolio unavailable right now')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy()
  })

  it('orders holdings by market value and picks the top holdings by weight', () => {
    const ps = [position('A', '10.00', '0.10'), position('B', '30.00', '0.30'), position('C', '20.00', '0.20')]
    expect(byMarketValue(ps).map((p) => p.symbol)).toEqual(['B', 'C', 'A'])
    expect(topHoldings(ps, 2).map((p) => p.symbol)).toEqual(['B', 'C'])
  })
})
