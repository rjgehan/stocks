import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { queryKeys } from '../api/queryKeys'
import type { Account, Clock, OrderUpdateEvent, SessionEvent } from '../api/types'
import { formatPrice, formatQty } from '../format/number'
import { toast } from '@/features/notifications/toastStore'
import { realtime } from './stomp'

/**
 * Turns `/user/queue/*` messages into query invalidations and toasts (§22.6), and resyncs after a
 * reconnect or when the tab becomes visible again (§19 client resync). Mount once, inside the app shell.
 */
export function useUserEvents(): void {
  const qc = useQueryClient()
  useEffect(() => {
    const hub = realtime()
    const offs = [
      hub.subscribe('/user/queue/orders', (body) => {
        const { event, order, execution } = body as OrderUpdateEvent
        void qc.invalidateQueries({ queryKey: ['orders'] })
        void qc.invalidateQueries({ queryKey: queryKeys.order(order.orderId) })
        void qc.invalidateQueries({ queryKey: queryKeys.positions })
        if (event === 'FILL' || event === 'PARTIAL_FILL') {
          const verb = order.side === 'BUY' ? 'Bought' : 'Sold'
          const label = event === 'FILL' ? 'Filled' : 'Partially filled'
          toast({
            tone: order.side === 'BUY' ? 'up' : 'down',
            title: `${label} ${formatQty(execution?.qty ?? order.filledQty)} ${order.symbol} @ ${formatPrice(execution?.price ?? order.avgFillPrice)}`,
            body: `${verb} via ${order.type.replace('_', ' ').toLowerCase()} order`,
          })
        } else if (event === 'CANCELLED' || event === 'EXPIRED') {
          toast({ tone: 'neutral', title: `${event === 'CANCELLED' ? 'Cancelled' : 'Expired'}: ${order.side === 'BUY' ? 'Buy' : 'Sell'} ${order.symbol}` })
        }
      }),
      hub.subscribe('/user/queue/account', (body) => {
        qc.setQueryData(queryKeys.account, body as Account)
      }),
      hub.subscribe('/user/queue/notifications', () => {
        void qc.invalidateQueries({ queryKey: queryKeys.notifications })
      }),
      hub.subscribe('/topic/leaderboard', () => {
        void qc.invalidateQueries({ queryKey: ['leaderboard'] })
      }),
      hub.onResync(() => {
        void qc.invalidateQueries({ queryKey: queryKeys.account })
        void qc.invalidateQueries({ queryKey: queryKeys.positions })
        void qc.invalidateQueries({ queryKey: queryKeys.orders('OPEN') })
        void qc.invalidateQueries({ queryKey: ['leaderboard'] })
      }),
    ]
    return () => offs.forEach((off) => off())
  }, [qc])
}

/** `/topic/market.session` keeps `['clock']` current between polls (§22.6). */
export function useSessionTopic(): void {
  const qc = useQueryClient()
  useEffect(
    () =>
      realtime().subscribe('/topic/market.session', (body) => {
        const e = body as SessionEvent
        qc.setQueryData<Clock>(queryKeys.clock, (prev) =>
          prev
            ? {
                ...prev,
                session: e.session,
                tradingDate: e.tradingDate,
                sessionEndsAt: e.endsAt,
                nextSession: e.nextSession,
                nextSessionAt: e.nextSessionAt,
                earlyClose: e.earlyClose,
              }
            : prev,
        )
      }),
    [qc],
  )
}
