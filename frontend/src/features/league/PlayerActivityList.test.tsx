import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/lib/api/queryKeys'
import type { Activity } from '@/lib/api/types'
import { renderWithRouter } from '@/test/router'
import { PlayerActivityList } from './PlayerActivityList'

const row = (over: Partial<Activity>): Activity => ({
  id: 'led_1',
  at: '2026-10-12T14:00:01Z',
  type: 'FILL',
  symbol: 'AAPL',
  side: 'BUY',
  qty: '1',
  price: '220.00',
  fees: { sec: '0.00', taf: '0.00', cat: '0.00', total: '0.00' },
  amount: '-220.00',
  balanceAfter: '280.00',
  tradeDate: '2026-10-12',
  settleDate: '2026-10-13',
  memo: null,
  by: 'SELF',
  orderId: 'ord_1',
  executionId: 'exe_1',
  transferId: null,
  detail: null,
  ...over,
})

describe("a player's public activity (public mode, §16.7)", () => {
  it("lists the run's fills and funding without links to the owner's order pages", async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    const items = [row({}), row({ id: 'led_0', at: '2026-10-05T13:00:00Z', type: 'INITIAL_FUNDING', symbol: null, side: null, qty: null, price: null, fees: null, amount: '500.00', balanceAfter: '500.00', by: 'SYSTEM', memo: 'Starting cash', orderId: null, executionId: null })]
    qc.setQueryData(queryKeys.playerActivity('alice'), { pages: [{ items, nextCursor: null }], pageParams: [undefined] })
    const { container } = renderWithRouter(<PlayerActivityList handle="alice" />, qc)
    const list = await screen.findByRole('list', { name: 'Activity' })
    expect(list.textContent).toContain('AAPL')
    expect(list.textContent).toContain('Starting cash')
    expect(container.querySelector('a[href^="/account/orders"]')).toBeNull()
    expect(screen.queryAllByRole('link')).toHaveLength(0)
  })

  it('says when there is nothing yet', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    qc.setQueryData(queryKeys.playerActivity('bob'), { pages: [{ items: [], nextCursor: null }], pageParams: [undefined] })
    renderWithRouter(<PlayerActivityList handle="bob" />, qc)
    expect(await screen.findByText('No activity yet.')).toBeTruthy()
  })
})
