import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { isApiError } from '../problem'
import { queryKeys } from '../queryKeys'
import { withRecentAuth } from '../reauth'
import type { Me, Preferences } from '../types'
import { stopRealtime } from '../../realtime/stomp'
import { signOutThisDevice } from '../../push/pushDevice'

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

/** A new session: drop every cached query of the previous player, keep the new `Me`. */
function startSession(qc: ReturnType<typeof useQueryClient>, me: Me) {
  stopRealtime()
  qc.clear()
  qc.setQueryData(queryKeys.me, me)
}

export function useLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { handle: string; password: string }) => unwrap(api.POST('/api/v1/auth/login', { body })),
    onSuccess: (me) => startSession(qc, me),
  })
}

export interface SignupInput {
  inviteCode: string
  handle: string
  displayName: string
  password: string
}

/** `POST /auth/signup` (§17.3): invite-only; creates the player, funds the account and signs in (201 `Me`). */
export function useSignup() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: SignupInput) => unwrap(api.POST('/api/v1/auth/signup', { body })),
    onSuccess: (me) => startSession(qc, me),
  })
}

export function useLogout() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      // Phase 2 (§19.2): this device stops getting the player's pushes (the server also forgets it at logout)
      await signOutThisDevice(qc.getQueryData<Me | null>(queryKeys.me)?.playerId)
      return unwrap(api.POST('/api/v1/auth/logout'))
    },
    onSettled: () => {
      // The STOMP session was authenticated as this player at its handshake: close it, so the next sign-in on this
      // tab gets a fresh CONNECT and never another player's /user/queue/* messages (§19).
      stopRealtime()
      qc.clear()
      qc.setQueryData(queryKeys.me, null)
    },
  })
}

/** `POST /auth/reauth` (§17.1): refreshes `authenticatedAt` for `@RecentAuth` routes. */
export async function reauthenticate(password: string): Promise<void> {
  await unwrap(api.POST('/api/v1/auth/reauth', { body: { password } }))
}

export function useUpdatePreferences() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (preferences: Partial<NonNullable<Preferences>>) => unwrap(api.PATCH('/api/v1/me', { body: { preferences } })),
    onMutate: async (preferences) => {
      await qc.cancelQueries({ queryKey: queryKeys.me })
      const prev = qc.getQueryData<Me | null>(queryKeys.me)
      if (prev?.preferences) {
        const next: Me = { ...prev, preferences: Object.assign({}, prev.preferences, preferences) }
        qc.setQueryData(queryKeys.me, next)
      }
      return { prev }
    },
    onError: (_e, _v, ctx) => {
      if (ctx?.prev !== undefined) qc.setQueryData(queryKeys.me, ctx.prev)
    },
    onSuccess: (me) => qc.setQueryData(queryKeys.me, me),
  })
}

export function useUpdateDisplayName() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (displayName: string) => unwrap(api.PATCH('/api/v1/me', { body: { displayName } })),
    onSuccess: (me) => qc.setQueryData(queryKeys.me, me),
  })
}

export function useChangePassword() {
  return useMutation({
    mutationFn: (body: { currentPassword: string; newPassword: string }) => unwrap(api.POST('/api/v1/me/password', { body })),
  })
}

// ---- personal API keys (§17.2): read-only unless the admin allows API trading

export function useMyApiKeys() {
  return useQuery({ queryKey: queryKeys.apiKeys, queryFn: () => unwrap(api.GET('/api/v1/me/api-keys')) })
}

export function useCreateMyApiKey() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { name: string; scopes?: string[]; expiresInDays?: number }) =>
      withRecentAuth(() => unwrap(api.POST('/api/v1/me/api-keys', { body }))),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.apiKeys }),
  })
}

export function useRevokeMyApiKey() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (keyId: string) => unwrap(api.DELETE('/api/v1/me/api-keys/{keyId}', { params: { path: { keyId } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.apiKeys }),
  })
}
