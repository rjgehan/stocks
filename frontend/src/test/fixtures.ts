import type { PublicOrder } from '@/lib/api/types'

/** Test fixtures shared by the public-mode component tests (§16.7). */

/** A working GTC limit buy of 2 AAPL placed by a bot through MCP, with its note; override any member. */
export function publicOrder(over: Partial<PublicOrder> = {}): PublicOrder {
  return {
    orderId: 'ord_01J9Y4N2QK7D3X8M5R6T1V0WZB',
    runNo: 1,
    symbol: 'AAPL',
    side: 'BUY',
    type: 'LIMIT',
    qty: '2',
    notional: null,
    fractional: false,
    limitPrice: '210.00',
    stopPrice: null,
    trailAmount: null,
    trailPercent: null,
    timeInForce: 'GTC',
    extendedHours: false,
    status: 'WORKING',
    waitReason: 'LIMIT_NOT_REACHED',
    statusReason: null,
    filledQty: '0',
    avgFillPrice: null,
    fees: '0.00',
    receivedAt: '2026-10-13T14:05:00Z',
    expiresAt: '2026-12-04T21:00:00Z',
    doneAt: null,
    replacesOrderId: null,
    replacedByOrderId: null,
    group: null,
    channel: 'MCP_KEY',
    viaApi: true,
    manualSessionId: null,
    fills: [],
    untrusted: { rationale: 'Bid under the 20-day average.' },
    ...over,
  }
}
