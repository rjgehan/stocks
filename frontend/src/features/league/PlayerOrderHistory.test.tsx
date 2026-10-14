import { describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, within } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/lib/api/queryKeys'
import type { PublicOrder } from '@/lib/api/types'
import { publicOrder } from '@/test/fixtures'
import { renderWithRouter } from '@/test/router'
import { PlayerOrderHistory, groupOrders } from './PlayerOrderHistory'

function client(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
}

function seed(qc: QueryClient, items: PublicOrder[], status: 'ALL' | 'OPEN' | 'CLOSED' = 'ALL', symbol?: string) {
  qc.setQueryData(queryKeys.playerOrders('claude', status, symbol), { pages: [{ items, nextCursor: null }], pageParams: [undefined] })
}

const filled = publicOrder({
  orderId: 'ord_FILLED',
  type: 'MARKET',
  qty: null,
  notional: '25.00',
  fractional: true,
  limitPrice: null,
  timeInForce: 'DAY',
  status: 'FILLED',
  waitReason: null,
  filledQty: '0.19231',
  avgFillPrice: '130.0000',
  fees: '0.01',
  receivedAt: '2026-10-12T14:00:00Z',
  doneAt: '2026-10-12T14:00:01Z',
  fills: [{ executionId: 'exe_1', executedAt: '2026-10-12T14:00:01Z', qty: '0.19231', price: '130.0000', fees: '0.01', realizedPnl: null }],
  untrusted: { rationale: '<b>Starter</b> <script>alert(1)</script>\nSecond line of the thesis.' },
})

describe("a player's order history (public mode, §16.7)", () => {
  it('shows each order with its status pill, fill, fees and source; tapping one shows the fills and the whole note as text', async () => {
    const qc = client()
    const cancelled = publicOrder({ orderId: 'ord_CANCELLED', status: 'CANCELLED', statusReason: 'USER', waitReason: null, receivedAt: '2026-10-11T15:00:00Z', doneAt: '2026-10-11T16:00:00Z', untrusted: { rationale: null } })
    const manual = publicOrder({ orderId: 'ord_MANUAL', channel: 'WEB', viaApi: false, manualSessionId: 'mbs_01J9Y4N2QK7D3X8M5R6T1V0WZB', receivedAt: '2026-10-13T15:00:00Z' })
    seed(qc, [manual, filled, cancelled])
    const { container } = renderWithRouter(<PlayerOrderHistory handle="claude" onFilter={() => {}} />, qc)
    const list = await screen.findByRole('list', { name: 'Orders' })
    expect(within(list).getByText('Working')).toBeTruthy()
    expect(within(list).getByText('Filled')).toBeTruthy()
    expect(within(list).getByText('Cancelled')).toBeTruthy()
    expect(within(list).getByText('Manual console')).toBeTruthy()
    expect(within(list).getAllByText('via API').length).toBe(2)
    expect(list.textContent).toContain('Filled 0.19231 @ 130.00 · fees $0.01')
    expect(list.textContent).toContain('Cancelled by the player')
    expect(screen.getByText("That's everything.")).toBeTruthy()

    // collapsed: the first line of the note only
    const row = screen.getByRole('button', { name: /\$25\.00 AAPL/ })
    expect(row.getAttribute('aria-expanded')).toBe('false')
    expect(row.textContent).not.toContain('Second line')
    // one line cut with an ellipsis: no padded line-clamp box for the next wrapped line to show through
    const quote = within(row).getByText(/Starter/)
    expect(quote.className).toContain('truncate')
    expect(quote.className).not.toContain('line-clamp')
    fireEvent.click(row)
    expect(row.getAttribute('aria-expanded')).toBe('true')
    const fills = screen.getByRole('table', { name: 'Fills' })
    expect(within(fills).getAllByRole('row')).toHaveLength(2)
    expect(fills.textContent).toContain('0.19231')
    // untrusted text (§17.8): shown as written, never as markup
    expect(screen.getByText(/<b>Starter<\/b> <script>alert\(1\)<\/script>/)).toBeTruthy()
    expect(container.querySelector('b')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    expect(screen.getByText(/Second line of the thesis/)).toBeTruthy()
  })

  it('re-checks after 2 s while an order is on its way to the market, else every 30 s', async () => {
    const qc = client()
    seed(qc, [publicOrder({ orderId: 'ord_NEW', status: 'PENDING', waitReason: 'LATENCY' })])
    renderWithRouter(<PlayerOrderHistory handle="claude" onFilter={() => {}} />, qc)
    await screen.findByRole('list', { name: 'Orders' })
    const query = qc.getQueryCache().find({ queryKey: queryKeys.playerOrders('claude', 'ALL', undefined) })!
    // the mounted observer's option: a function of the query's latest data
    const interval = query.observers[0]!.options.refetchInterval as unknown as (q: typeof query) => number | false
    expect(interval(query)).toBe(2_000)
    seed(qc, [publicOrder({ orderId: 'ord_NEW' })])               // working, waiting for its limit
    expect(interval(query)).toBe(30_000)
  })

  it('says when nothing matches, and asks for the filter it is given', async () => {
    const qc = client()
    seed(qc, [], 'OPEN', 'TSLA')
    const onFilter = vi.fn<(f: { status?: string; symbol?: string }) => void>()
    renderWithRouter(<PlayerOrderHistory handle="claude" status="OPEN" symbol="TSLA" onFilter={onFilter} />, qc)
    expect(await screen.findByText('No orders match.')).toBeTruthy()
    fireEvent.click(screen.getByRole('radio', { name: 'Closed' }))
    expect(onFilter).toHaveBeenCalledWith({ status: 'CLOSED', symbol: 'TSLA' })
  })

  it('says when there are no orders at all', async () => {
    const qc = client()
    seed(qc, [])
    renderWithRouter(<PlayerOrderHistory handle="claude" onFilter={() => {}} />, qc)
    expect(await screen.findByText('No orders yet.')).toBeTruthy()
  })

  it('groups by ET day and marks where a new run starts', () => {
    const now = Date.parse('2026-10-13T18:00:00Z')
    const groups = groupOrders(
      [
        publicOrder({ orderId: 'a', runNo: 2, receivedAt: '2026-10-13T15:00:00Z' }),
        publicOrder({ orderId: 'b', runNo: 2, receivedAt: '2026-10-13T14:00:00Z' }),
        publicOrder({ orderId: 'c', runNo: 1, receivedAt: '2026-10-13T13:45:00Z' }),
        publicOrder({ orderId: 'd', runNo: 1, receivedAt: '2026-10-09T15:00:00Z' }),
      ],
      now,
    )
    expect(groups.map((g) => g.label)).toEqual(['Today', 'Fri, Oct 9'])
    expect(groups[0]!.entries.map((e) => (e.kind === 'run' ? `run ${e.runNo}` : e.o.orderId))).toEqual(['a', 'b', 'run 1', 'c'])
  })
})
