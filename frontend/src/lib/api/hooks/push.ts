/**
 * Web Push settings (§18.1 `/me/push`, §19.2): the server's VAPID key, the player's category preferences and devices.
 * Session only (bots have no browser).
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { PushPreferences, PushSettings } from '../types'

export function usePushSettings(enabled = true) {
  return useQuery({
    queryKey: queryKeys.push,
    queryFn: () => unwrap(api.GET('/api/v1/me/push')),
    staleTime: 60_000,
    enabled,
  })
}

export function useUpdatePushPreferences() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (patch: Partial<PushPreferences>) => unwrap(api.PATCH('/api/v1/me/push/preferences', { body: patch })),
    onMutate: async (patch) => {
      await qc.cancelQueries({ queryKey: queryKeys.push })
      const prev = qc.getQueryData<PushSettings>(queryKeys.push)
      if (prev?.preferences) qc.setQueryData<PushSettings>(queryKeys.push, { ...prev, preferences: { ...prev.preferences, ...patch } })
      return { prev }
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(queryKeys.push, ctx.prev)
    },
    onSuccess: (preferences) =>
      qc.setQueryData<PushSettings>(queryKeys.push, (s) => (s ? { ...s, preferences } : s)),
  })
}

/** `POST /me/push/subscriptions`: register this browser. */
export function registerPushSubscription(body: { endpoint: string; p256dh: string; auth: string }) {
  return unwrap(api.POST('/api/v1/me/push/subscriptions', { body }))
}

/** `POST /me/push/sync`: does the server know this browser's subscription (for this player)? */
export function syncPushSubscription(endpoint: string) {
  return unwrap(api.POST('/api/v1/me/push/sync', { body: { endpoint } }))
}

/** `POST /me/push/unsubscribe`: forget this browser. */
export function unregisterPushSubscription(endpoint: string) {
  return unwrap(api.POST('/api/v1/me/push/unsubscribe', { body: { endpoint } }))
}

export function useDeletePushDevice() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (deviceId: string) => unwrap(api.DELETE('/api/v1/me/push/subscriptions/{deviceId}', { params: { path: { deviceId } } })),
    onMutate: async (deviceId) => {
      await qc.cancelQueries({ queryKey: queryKeys.push })
      const prev = qc.getQueryData<PushSettings>(queryKeys.push)
      if (prev?.devices) qc.setQueryData<PushSettings>(queryKeys.push, { ...prev, devices: prev.devices.filter((d) => d.id !== deviceId) })
      return { prev }
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(queryKeys.push, ctx.prev)
    },
    onSettled: () => void qc.invalidateQueries({ queryKey: queryKeys.push }),
  })
}

export function useTestPush() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(api.POST('/api/v1/me/push/test')),
    onSettled: () => void qc.invalidateQueries({ queryKey: queryKeys.push }),
  })
}
