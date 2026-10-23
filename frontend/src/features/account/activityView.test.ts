import { describe, expect, it } from 'vitest'
import type { Activity } from '@/lib/api/types'
import { activityView } from './activityView'

const row = (over: Partial<Activity>): Activity =>
  ({ id: 'led_1', at: '2026-10-09T13:45:00Z', type: 'DEPOSIT', symbol: null, side: null, qty: null, price: null, fees: null, amount: null, balanceAfter: null, tradeDate: '2026-10-09', settleDate: null, memo: null, by: 'SYSTEM', orderId: null, executionId: null, transferId: null, detail: null, ...over }) as Activity

describe('activityView (§22.4 Activity rows)', () => {
  it('shows an accrued dividend as pending, with the payable date', () => {
    const v = activityView(
      row({ type: 'DIVIDEND', symbol: 'KO', amount: '0.51', detail: { type: 'DIVIDEND', status: 'ACCRUED', qty: '1', cashRate: '0.51', exDate: '2026-10-05', payableDate: '2026-10-22' } as unknown as Activity['detail'] }),
    )
    expect(v).toMatchObject({ title: 'Dividend · KO', badge: { label: 'Accrued' }, amount: '+$0.51', amountState: 'pending', icon: 'dividend' })
    expect(v.lines.join(' · ')).toContain('1 sh × $0.51')
    expect(v.lines.join(' · ')).toMatch(/pays Oct 22/)
  })

  it('a paid dividend ledger row without a detail counts as paid; a voided one is struck through', () => {
    expect(activityView(row({ type: 'DIVIDEND', symbol: 'KO', qty: '2', price: '0.0125', amount: '0.03' }))).toMatchObject({ badge: { label: 'Paid' }, amountState: 'posted' })
    expect(activityView(row({ type: 'DIVIDEND', symbol: 'KO', qty: '2', price: '0.0125', amount: '0.03' })).lines[0]).toBe('2 sh × $0.0125')
    expect(activityView(row({ type: 'DIVIDEND', symbol: 'KO', amount: '0.51', detail: { type: 'DIVIDEND', status: 'VOID' } as unknown as Activity['detail'] }))).toMatchObject({ badge: { label: 'Void' }, amountState: 'void' })
  })

  it('names splits new-for-old and explains quantity, cost and cash in lieu', () => {
    const fwd = activityView(row({ type: 'SPLIT', symbol: 'KO', detail: { type: 'SPLIT', oldRate: '1', newRate: '2', qtyBefore: '1', qtyAfter: '2', costBasis: '66.40', cashInLieu: '0.00', dustQty: '0', realizedPnl: '0.00' } as unknown as Activity['detail'] }))
    expect(fwd.title).toBe('KO 2-for-1 split')
    expect(fwd.lines).toEqual(['1 → 2 shares', 'cost basis $66.40 (unchanged)'])
    expect(fwd.amount).toBeNull()
    const rev = activityView(row({ type: 'SPLIT', symbol: 'XYZW', detail: { type: 'SPLIT', oldRate: '2', newRate: '1', qtyBefore: '3', qtyAfter: '1', costBasis: '40.80', cashInLieu: '20.61', dustQty: '0.5', realizedPnl: '0.21' } as unknown as Activity['detail'] }))
    expect(rev.title).toBe('XYZW 1-for-2 reverse split')
    expect(rev.lines).toContain('cash in lieu $20.61 for 0.5 sh · realized +$0.21')
    const odd = activityView(row({ type: 'SPLIT', symbol: 'ABC', detail: { type: 'SPLIT', oldRate: '2.000000', newRate: '3.000000', qtyBefore: '0.33333', qtyAfter: '0.49999', costBasis: '10.00', cashInLieu: '0.00', dustQty: '0.000005', realizedPnl: '0.00' } as unknown as Activity['detail'] }))
    expect(odd.title).toBe('ABC 3-for-2 split')
    expect(odd.lines.at(-1)).toMatch(/dust forfeited/)
  })

  it('symbol changes, admin views and resets read as events with no cash', () => {
    expect(activityView(row({ type: 'NAME_CHANGE', symbol: 'SIMA', detail: { type: 'NAME_CHANGE', oldSymbol: 'SIMX', newSymbol: 'SIMA', qty: '10' } as unknown as Activity['detail'] }))).toMatchObject({
      title: 'SIMX is now SIMA',
      amount: null,
      icon: 'rename',
    })
    const view = activityView(row({ type: 'ADMIN_VIEW', detail: { type: 'ADMIN_VIEW', admin: 'owner', reason: 'Checking a stuck order' } as unknown as Activity['detail'] }))
    expect(view).toMatchObject({ title: 'Admin viewed your live account', badge: { label: 'Audited' } })
    expect(view.lines[0]).toBe('by @owner')
    expect(activityView(row({ type: 'RESET', detail: { type: 'RESET', side: 'OPENED', runNo: 2, startingCash: '500.00' } as unknown as Activity['detail'] })).title).toBe('New run #2 started')
  })

  it('fills and cash in lieu keep their signed amounts', () => {
    expect(activityView(row({ type: 'FILL', side: 'BUY', symbol: 'NVDA', qty: '0.95274', price: '131.2000', amount: '-125.00' }))).toMatchObject({ title: 'Bought 0.95274 NVDA @ 131.20', amount: '−$125.00' })
    expect(activityView(row({ type: 'CASH_IN_LIEU', symbol: 'XYZW', qty: '0.5', price: '41.2200', amount: '20.61' }))).toMatchObject({ title: 'Cash in lieu · XYZW', amount: '+$20.61' })
  })

  it('reads option fills in contracts and the expiration settlement (§30)', () => {
    const occ = 'F261016C00011000'
    expect(activityView(row({ type: 'FILL', side: 'BUY', symbol: occ, qty: '1', price: '0.4500', amount: '-45.00' }))).toMatchObject({
      title: 'Bought to open 1 contract F 16 Oct 26 $11 Call @ 0.45',
      amount: '−$45.00',
    })
    expect(activityView(row({ type: 'FILL', side: 'SELL', symbol: occ, qty: '1', price: '0.6200', amount: '62.00', by: 'SYSTEM' }))).toMatchObject({
      title: 'Expired: 1 contract F 16 Oct 26 $11 Call',
      lines: ['Cash-settled at $0.62'],
      amount: '+$62.00',
    })
  })
})
