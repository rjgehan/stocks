/**
 * Price alerts (§18.2 `/alerts`, §19.1). One cached list (`['alerts']`) for the whole player: the quote page's alert
 * sheet filters it by symbol, the Alerts screen by status. A `PRICE_ALERT` notification invalidates it
 * (`useUserEvents`), so a fired alert shows as TRIGGERED without a reload.
 */
import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { Alert, CreateAlertRequest, UpdateAlertRequest } from '../types'

export function useAlerts() {
  return useQuery({
    queryKey: queryKeys.alerts,
    queryFn: () => unwrap(api.GET('/api/v1/alerts')),
    staleTime: 30_000,
  })
}

/** Replaces (or adds) one alert in the cached list. */
function upsert(qc: QueryClient, alert: Alert) {
  qc.setQueryData<Alert[]>(queryKeys.alerts, (list) => {
    if (!list) return list
    return list.some((a) => a.id === alert.id) ? list.map((a) => (a.id === alert.id ? alert : a)) : [alert, ...list]
  })
}

function refresh(qc: QueryClient) {
  void qc.invalidateQueries({ queryKey: queryKeys.alerts })
}

export function useCreateAlert() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateAlertRequest) => unwrap(api.POST('/api/v1/alerts', { body })),
    onSuccess: (alert) => {
      upsert(qc, alert)
      refresh(qc)
    },
  })
}

export function useUpdateAlert() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateAlertRequest }) =>
      unwrap(api.PUT('/api/v1/alerts/{alertId}', { params: { path: { alertId: id } }, body })),
    onSuccess: (alert) => {
      upsert(qc, alert)
      refresh(qc)
    },
  })
}

export function usePauseAlert() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, pause }: { id: string; pause: boolean }) =>
      pause
        ? unwrap(api.POST('/api/v1/alerts/{alertId}/pause', { params: { path: { alertId: id } } }))
        : unwrap(api.POST('/api/v1/alerts/{alertId}/resume', { params: { path: { alertId: id } } })),
    onSuccess: (alert) => upsert(qc, alert),
    onSettled: () => refresh(qc),
  })
}

export function useDeleteAlert() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => unwrap(api.DELETE('/api/v1/alerts/{alertId}', { params: { path: { alertId: id } } })),
    onMutate: async (id) => {
      await qc.cancelQueries({ queryKey: queryKeys.alerts })
      const prev = qc.getQueryData<Alert[]>(queryKeys.alerts)
      if (prev) qc.setQueryData<Alert[]>(queryKeys.alerts, prev.filter((a) => a.id !== id))
      return { prev }
    },
    onError: (_e, _id, ctx) => {
      if (ctx?.prev) qc.setQueryData(queryKeys.alerts, ctx.prev)
    },
    onSettled: () => refresh(qc),
  })
}
