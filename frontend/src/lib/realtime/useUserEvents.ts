import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { patchOrderCaches } from '../api/hooks/account'
import { pushNotification } from '../api/hooks/notifications'
import { queryKeys } from '../api/queryKeys'
import type { Account, Clock, Notification, OrderUpdateEvent, SessionEvent } from '../api/types'
import { formatMoney } from '../format/money'
import { formatPrice, formatQty } from '../format/number'
import { toast } from '@/features/notifications/toastStore'
import { realtime } from './stomp'

/** Notification kinds that also pop a toast (order fills already toast from `/user/queue/orders`). */
const TOAST_KINDS = new Set(['DEPOSIT', 'WITHDRAWAL', 'ADJUSTMENT', 'GFV_ISSUED', 'RESTRICTION_CHANGED', 'ACCOUNT_RESET', 'CORPORATE_ACTION', 'SYSTEM'])

interface AdminAlert {
  level: 'INFO' | 'WARN' | 'ERROR' | null
  title: string | null
  body: string | null
}

/** Toast copy for an order event (§22.5 Result): "Filled 10 AAPL @ 227.38". */
export function orderEventToast(e: OrderUpdateEvent): Parameters<typeof toast>[0] | null {
  const order = e.order
  if (!order) return null
  const sym = order.symbol ?? ''
  const buy = order.side === 'BUY'
  switch (e.event) {
    case 'FILL':
    case 'PARTIAL_FILL': {
      const qty = e.execution?.qty ?? order.filledQty
      const price = e.execution?.price ?? order.avgFillPrice
      return {
        tone: buy ? 'up' : 'down',
        title: `${e.event === 'FILL' ? 'Filled' : 'Partially filled'} ${buy ? 'buy' : 'sell'} ${formatQty(qty)} ${sym} @ ${formatPrice(price)}`,
        body: e.execution?.principal ? `${buy ? 'Cost' : 'Proceeds'} ${formatMoney(e.execution.principal)}` : undefined,
      }
    }
    case 'CANCELLED':
      return { tone: 'neutral', title: `Cancelled: ${buy ? 'Buy' : 'Sell'} ${sym}`, body: order.statusReason && order.statusReason !== 'USER' ? reasonLabel(order.statusReason) : undefined }
    case 'EXPIRED':
      return { tone: 'neutral', title: `Expired: ${buy ? 'Buy' : 'Sell'} ${sym}` }
    default:
      return null
  }
}

function reasonLabel(reason: string): string {
  return reason.replaceAll('_', ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase())
}

/** At most one positions refetch per this many ms from account pushes (which arrive every ~5 s in RTH). */
export const POSITIONS_REFRESH_MS = 5_000

/** A leading-edge throttle: `due(now)` is true at most once per `intervalMs`. */
export function throttle(intervalMs: number): { due: (now: number) => boolean } {
  let last = Number.NEGATIVE_INFINITY
  return {
    due(now: number) {
      if (now - last < intervalMs) return false
      last = now
      return true
    },
  }
}

/**
 * Turns `/user/queue/*` messages into cache updates, invalidations and toasts (§22.6), and resyncs after a
 * reconnect or when the tab becomes visible again (§19 client resync). Mount once, inside the app shell.
 */
export function useUserEvents(isAdmin: boolean): void {
  const qc = useQueryClient()
  useEffect(() => {
    const hub = realtime()
    const positionsThrottle = throttle(POSITIONS_REFRESH_MS)
    const offs = [
      hub.subscribe('/user/queue/orders', (body) => {
        const e = body as OrderUpdateEvent
        if (e.order) patchOrderCaches(qc, e.order)
        void qc.invalidateQueries({ queryKey: ['orders'] })
        if (e.order?.orderId) void qc.invalidateQueries({ queryKey: queryKeys.order(e.order.orderId) })
        if (e.event === 'FILL' || e.event === 'PARTIAL_FILL') {
          void qc.invalidateQueries({ queryKey: queryKeys.positions })
          void qc.invalidateQueries({ queryKey: queryKeys.account })
          void qc.invalidateQueries({ queryKey: ['activities'] })
        }
        const t = orderEventToast(e)
        if (t) toast(t)
      }),
      hub.subscribe('/user/queue/account', (body) => {
        qc.setQueryData(queryKeys.account, body as Account)
        // the account push is the "marks moved" signal: refresh positions too (≤ 1 per 5 s), so the position rows
        // and cards add up to the NAV on screen; the client never computes money itself
        if (positionsThrottle.due(Date.now())) void qc.invalidateQueries({ queryKey: queryKeys.positions })
      }),
      hub.subscribe('/user/queue/notifications', (body) => {
        const n = body as Notification
        pushNotification(qc, n)
        if (n.kind && TOAST_KINDS.has(n.kind)) {
          toast({ tone: n.kind === 'WITHDRAWAL' ? 'neutral' : n.kind === 'DEPOSIT' ? 'up' : 'warn', title: n.title ?? 'Notification', body: n.body ?? undefined })
          void qc.invalidateQueries({ queryKey: ['activities'] })
          void qc.invalidateQueries({ queryKey: queryKeys.account })
        }
      }),
      hub.subscribe('/topic/leaderboard', () => {
        void qc.invalidateQueries({ queryKey: ['leaderboard'] })
      }),
      hub.onResync(() => {
        void qc.invalidateQueries({ queryKey: queryKeys.account })
        void qc.invalidateQueries({ queryKey: queryKeys.positions })
        void qc.invalidateQueries({ queryKey: queryKeys.orders('OPEN') })
        void qc.invalidateQueries({ queryKey: ['leaderboard'] })
        void qc.invalidateQueries({ queryKey: queryKeys.notifications })
      }),
    ]
    if (isAdmin) {
      offs.push(
        hub.subscribe('/user/queue/admin', (body) => {
          const a = body as AdminAlert
          toast({ tone: a.level === 'ERROR' ? 'error' : a.level === 'WARN' ? 'warn' : 'neutral', title: a.title ?? 'Admin alert', body: a.body ?? undefined })
          void qc.invalidateQueries({ queryKey: queryKeys.admin('system') })
        }),
      )
    }
    return () => offs.forEach((off) => off())
  }, [qc, isAdmin])
}

/** `/topic/market.session` keeps `['clock']` current between polls (§22.6). */
export function useSessionTopic(): void {
  const qc = useQueryClient()
  useEffect(
    () =>
      realtime().subscribe('/topic/market.session', (body) => {
        const e = body as SessionEvent
        // Keep the fetch time with the data: `Clock.now` is still the server time of that fetch, and the
        // SessionChip derives the server's current time from the pair (now + time since dataUpdatedAt).
        const updatedAt = qc.getQueryState(queryKeys.clock)?.dataUpdatedAt
        qc.setQueryData<Clock>(
          queryKeys.clock,
          (prev) =>
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
          { updatedAt },
        )
      }),
    [qc],
  )
}
