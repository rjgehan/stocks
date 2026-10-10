import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { PlaceOrderRequest } from '../types'

export function useAccount() {
  return useQuery({ queryKey: queryKeys.account, queryFn: () => unwrap(api.GET('/api/v1/account')), staleTime: 5_000 })
}

export function usePositions() {
  return useQuery({ queryKey: queryKeys.positions, queryFn: () => unwrap(api.GET('/api/v1/account/positions')), staleTime: 5_000 })
}

export function useOrders(status: 'OPEN' | 'CLOSED' | 'ALL') {
  return useQuery({
    queryKey: queryKeys.orders(status),
    queryFn: () => unwrap(api.GET('/api/v1/orders', { params: { query: { status } } })),
    staleTime: 5_000,
  })
}

/** `POST /orders/preview` (always 200; `valid=false` lists errors). Debounce the request before calling. */
export function useOrderPreview(request: PlaceOrderRequest | null) {
  return useQuery({
    queryKey: queryKeys.orderPreview(request),
    queryFn: () => unwrap(api.POST('/api/v1/orders/preview', { body: request! })),
    enabled: request !== null,
    staleTime: 2_000,
    placeholderData: (prev) => prev,
  })
}

export function usePlaceOrder() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (request: PlaceOrderRequest) => unwrap(api.POST('/api/v1/orders', { body: request })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['orders'] })
      void qc.invalidateQueries({ queryKey: queryKeys.account })
    },
  })
}

export function useCancelOrder() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (orderId: string) => unwrap(api.DELETE('/api/v1/orders/{orderId}', { params: { path: { orderId } } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['orders'] })
      void qc.invalidateQueries({ queryKey: queryKeys.account })
    },
  })
}
