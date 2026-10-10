import { type InfiniteData, type QueryClient, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { type OrdersFilter, queryKeys } from '../queryKeys'
import type { ActivityType, HistoryRange, Order, PlaceOrderRequest } from '../types'
import { usePollWhenOffline } from '../../realtime/status'

/** `GET /account`. Pushed on `/user/queue/account` (§19); polls every 10 s while the socket is down. */
export function useAccount() {
  const poll = usePollWhenOffline(10_000, 60_000)
  return useQuery({ queryKey: queryKeys.account, queryFn: () => unwrap(api.GET('/api/v1/account')), staleTime: 5_000, refetchInterval: poll })
}

export function usePositions() {
  const poll = usePollWhenOffline(10_000, 60_000)
  return useQuery({
    queryKey: queryKeys.positions,
    queryFn: () => unwrap(api.GET('/api/v1/account/positions')),
    staleTime: 5_000,
    refetchInterval: poll,
  })
}

/** First page (≤ 100) of orders; `symbol` narrows server-side. Open orders poll while the socket is down. */
export function useOrders(status: OrdersFilter, symbol?: string) {
  const poll = usePollWhenOffline(status === 'CLOSED' ? 30_000 : 8_000)
  return useQuery({
    queryKey: queryKeys.orders(status, symbol),
    queryFn: () => unwrap(api.GET('/api/v1/orders', { params: { query: symbol ? { status, symbol, limit: 100 } : { status, limit: 100 } } })),
    staleTime: 5_000,
    refetchInterval: poll,
  })
}

/** Cursor-paged orders for the Orders screen. */
export function useOrdersPaged(status: OrdersFilter) {
  return useInfiniteQuery({
    queryKey: [...queryKeys.orders(status), 'paged'],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(api.GET('/api/v1/orders', { params: { query: pageParam ? { status, cursor: pageParam, limit: 50 } : { status, limit: 50 } } })),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 5_000,
  })
}

/** One order with its executions and events (`GET /orders/{id}`). */
export function useOrder(orderId: string) {
  return useQuery({
    queryKey: queryKeys.order(orderId),
    queryFn: () => unwrap(api.GET('/api/v1/orders/{orderId}', { params: { path: { orderId } } })),
    staleTime: 5_000,
    // an armed trailing stop's watermark moves without an order event (TRAIL_UPDATED is throttled to 10 s, §11.5):
    // keep the detail fresh while one is open
    refetchInterval: (q) => {
      const o = q.state.data
      return o && o.type === 'TRAILING_STOP' && !o.triggered && (o.status === 'PENDING' || o.status === 'WORKING') ? 3_000 : false
    },
  })
}

/** `POST /orders/preview` (always 200; `valid=false` lists errors). Debounce the request before calling. */
export function useOrderPreview(request: PlaceOrderRequest | null) {
  return useQuery({
    queryKey: queryKeys.orderPreview(request),
    queryFn: () => unwrap(api.POST('/api/v1/orders/preview', { body: request! })),
    enabled: request !== null,
    staleTime: 2_000,
    // re-quote while the ticket stays open: the estimate follows the market
    refetchInterval: 15_000,
    placeholderData: (prev) => prev,
  })
}

function afterOrderChange(qc: QueryClient) {
  void qc.invalidateQueries({ queryKey: ['orders'] })
  void qc.invalidateQueries({ queryKey: queryKeys.account })
  void qc.invalidateQueries({ queryKey: queryKeys.positions })
}

/** Writes an order into every cached order list and its detail entry (live STOMP updates and mutation results). */
export function patchOrderCaches(qc: QueryClient, order: Order) {
  if (!order.orderId) return
  const replace = (o: Order) => (o.orderId === order.orderId ? { ...o, ...order, executions: o.executions, events: o.events } : o)
  qc.setQueriesData<{ items: Order[] | null; nextCursor: string | null }>({ queryKey: ['orders'], predicate: (q) => q.queryKey[2] !== 'paged' }, (page) =>
    page?.items ? { ...page, items: page.items.map(replace) } : page,
  )
  qc.setQueriesData<InfiniteData<{ items: Order[] | null; nextCursor: string | null }>>(
    { queryKey: ['orders'], predicate: (q) => q.queryKey[2] === 'paged' },
    (data) => (data ? { ...data, pages: data.pages.map((p) => (p.items ? { ...p, items: p.items.map(replace) } : p)) } : data),
  )
  qc.setQueryData<Order>(queryKeys.order(order.orderId), (prev) => (prev ? { ...prev, ...order, executions: prev.executions, events: prev.events } : prev))
}

export function usePlaceOrder() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (request: PlaceOrderRequest) => unwrap(api.POST('/api/v1/orders', { body: request })),
    onSuccess: () => afterOrderChange(qc),
  })
}

export function useCancelOrder() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (orderId: string) => unwrap(api.DELETE('/api/v1/orders/{orderId}', { params: { path: { orderId } } })),
    onSuccess: (order) => {
      patchOrderCaches(qc, order)
      afterOrderChange(qc)
      if (order.orderId) void qc.invalidateQueries({ queryKey: queryKeys.order(order.orderId) })
    },
  })
}

/** `POST /account/positions/{symbol}/close` (§11.8): a market sell of the whole available quantity. */
export function useClosePosition() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ symbol, clientOrderId }: { symbol: string; clientOrderId: string }) =>
      unwrap(api.POST('/api/v1/account/positions/{symbol}/close', { params: { path: { symbol } }, body: { clientOrderId } })),
    onSuccess: () => afterOrderChange(qc),
  })
}

export interface ActivityFilter {
  /** Sent as repeated `types=` parameters (Spring binds them to the `List<ActivityType>`). */
  types?: ActivityType[]
}

/** `GET /account/activities` (newest first, cursor-paged). */
export function useActivities(filter: ActivityFilter = {}, pageSize = 30) {
  return useInfiniteQuery({
    queryKey: queryKeys.activities({ ...filter, pageSize }),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/account/activities', {
          params: { query: { ...(filter.types?.length ? { types: filter.types } : {}), ...(pageParam ? { cursor: pageParam } : {}), limit: pageSize } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 10_000,
  })
}

/** `GET /account/history` (§18.2): equity, P&L and unit value with the SPY benchmark index. */
export function useAccountHistory(range: HistoryRange) {
  return useQuery({
    queryKey: queryKeys.history(range),
    queryFn: () => unwrap(api.GET('/api/v1/account/history', { params: { query: { range } } })),
    staleTime: 60_000,
    placeholderData: (prev) => prev,
    retry: false,
  })
}

export function useRuns() {
  return useQuery({ queryKey: queryKeys.runs, queryFn: () => unwrap(api.GET('/api/v1/account/runs')), staleTime: 5 * 60_000, retry: false })
}

export function useResetMyAccount() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (idempotencyKey: string) =>
      unwrap(api.POST('/api/v1/account/reset', { body: { confirm: 'RESET' }, headers: { 'Idempotency-Key': idempotencyKey } })),
    onSuccess: () => {
      void qc.invalidateQueries()
    },
  })
}
