import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { isApiError } from '../problem'
import { queryKeys } from '../queryKeys'
import type { Me, Preferences } from '../types'
import { stopRealtime } from '../../realtime/stomp'

/** The signed-in player, or `null` when the session is anonymous (401). */
export function useMe() {
  return useQuery({
    queryKey: queryKeys.me,
    staleTime: 60_000,
    queryFn: async (): Promise<Me | null> => {
      try {
        return await unwrap(api.GET('/api/v1/me'))
      } catch (e) {
        if (isApiError(e) && e.status === 401) return null
        throw e
      }
    },
  })
}

export function useLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { handle: string; password: string }) => unwrap(api.POST('/api/v1/auth/login', { body })),
    onSuccess: (me) => {
      qc.setQueryData(queryKeys.me, me)
    },
  })
}

export function useLogout() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(api.POST('/api/v1/auth/logout')),
    onSettled: () => {
      // The STOMP session was authenticated as this player at its handshake: close it, so the next sign-in on this
      // tab gets a fresh CONNECT and never another player's /user/queue/* messages (§19).
      stopRealtime()
      qc.clear()
      qc.setQueryData(queryKeys.me, null)
    },
  })
}

export function useUpdatePreferences() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (preferences: Partial<Preferences>) => unwrap(api.PATCH('/api/v1/me', { body: { preferences } })),
    onSuccess: (me) => qc.setQueryData(queryKeys.me, me),
  })
}
