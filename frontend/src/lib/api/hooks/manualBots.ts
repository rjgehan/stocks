/**
 * Manual bots (§20.8): the owner's console of an AI bot (`/me/bots/{handle}/…`, session only) and the public manual
 * sessions under the bot's History (`/players/{handle}/manual-sessions`). The console reads its sessions through
 * the public routes, so the session keys live under the player's key (`['player', h, 'manualSessions', …]`).
 */
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { ManualSession } from '../types'
import { refreshPlayer } from './league'

export const manualKeys = {
  mine: ['manualBots', 'mine'] as const,
  bot: (handle: string) => ['manualBot', handle] as const,
  sessions: (handle: string) => ['player', handle, 'manualSessions'] as const,
  sessionList: (handle: string) => ['player', handle, 'manualSessions', 'list'] as const,
  session: (handle: string, sessionId: string) => ['player', handle, 'manualSessions', 'detail', sessionId] as const,
}

/** `GET /me/bots/{handle}/manual`: the console state (owner or admin only; FORBIDDEN otherwise). */
export function useManualBot(handle: string) {
  return useQuery({
    queryKey: manualKeys.bot(handle),
    queryFn: () => unwrap(api.GET('/api/v1/me/bots/{handle}/manual', { params: { path: { handle } } })),
    staleTime: 30_000,
    retry: false,
  })
}

/** `GET /me/bots`: the console state of every AI bot I own (the prompts screen). */
export function useMyManualBots(enabled = true) {
  return useQuery({
    queryKey: manualKeys.mine,
    queryFn: () => unwrap(api.GET('/api/v1/me/bots')),
    enabled,
    staleTime: 30_000,
    retry: false,
  })
}

/** `PUT /me/bots/{handle}/manual`: instructions, the model label and/or the name (null = unchanged). */
export function useUpdateManualBot(handle: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { instructions?: string; aiModel?: string; displayName?: string }) =>
      unwrap(api.PUT('/api/v1/me/bots/{handle}/manual', { params: { path: { handle } }, body })),
    onSuccess: (bot) => {
      qc.setQueryData(manualKeys.bot(handle), bot)
      void qc.invalidateQueries({ queryKey: manualKeys.mine })
      // the name and the model label show on the profile, the board and the feed
      void qc.invalidateQueries({ queryKey: queryKeys.player(handle), exact: true })
      void qc.invalidateQueries({ queryKey: queryKeys.players })
      void qc.invalidateQueries({ queryKey: ['leaderboard'] })
    },
  })
}

/** Stores a session (detail shape) and refreshes the lists that show it. */
function useStoreSession(handle: string) {
  const qc = useQueryClient()
  return (s: ManualSession) => {
    if (s.sessionId) qc.setQueryData(manualKeys.session(handle, s.sessionId), s)
    void qc.invalidateQueries({ queryKey: manualKeys.sessionList(handle) })
  }
}

/** `POST /me/bots/{handle}/manual-sessions`: a new prompt (detail shape). */
export function useCreateManualSession(handle: string) {
  const qc = useQueryClient()
  const store = useStoreSession(handle)
  return useMutation({
    mutationFn: () => unwrap(api.POST('/api/v1/me/bots/{handle}/manual-sessions', { params: { path: { handle } } })),
    onSuccess: (s) => {
      store(s)
      void qc.invalidateQueries({ queryKey: manualKeys.bot(handle) }) // prompts made today
    },
  })
}

/** `GET /players/{handle}/manual-sessions/{sessionId}`: one session with its prompt and reply. */
export function useManualSession(handle: string, sessionId: string | null | undefined, enabled = true) {
  return useQuery({
    queryKey: manualKeys.session(handle, sessionId ?? ''),
    queryFn: () =>
      unwrap(api.GET('/api/v1/players/{handle}/manual-sessions/{sessionId}', { params: { path: { handle, sessionId: sessionId ?? '' } } })),
    enabled: enabled && Boolean(sessionId),
    staleTime: 30_000,
    retry: false,
  })
}

/** `POST …/manual-sessions/{sessionId}/reply`: parse and preview a pasted reply. */
export function useCheckManualReply(handle: string, sessionId: string) {
  const store = useStoreSession(handle)
  return useMutation({
    mutationFn: (reply: string) =>
      unwrap(
        api.POST('/api/v1/me/bots/{handle}/manual-sessions/{sessionId}/reply', {
          params: { path: { handle, sessionId } },
          body: { reply },
        }),
      ),
    onSuccess: store,
  })
}

/** `POST …/manual-sessions/{sessionId}/execute`: place the ticked rows as the bot (`[]` posts the summary only). */
export function useExecuteManualSession(handle: string, sessionId: string) {
  const qc = useQueryClient()
  const store = useStoreSession(handle)
  return useMutation({
    mutationFn: (actions: number[]) =>
      unwrap(
        api.POST('/api/v1/me/bots/{handle}/manual-sessions/{sessionId}/execute', {
          params: { path: { handle, sessionId } },
          body: { actions },
        }),
      ),
    onSuccess: (s) => {
      store(s)
      // the profile, trades, portfolio, orders, activity and journal of the bot (as a feed item of it would)
      refreshPlayer(qc, handle, true)
    },
  })
}

/** `GET /players/{handle}/manual-sessions`: the bot's sessions in list shape (no prompt or reply), newest first. */
export function usePlayerManualSessions(handle: string, enabled = true) {
  return useInfiniteQuery({
    queryKey: manualKeys.sessionList(handle),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/players/{handle}/manual-sessions', {
          params: { path: { handle }, query: pageParam ? { cursor: pageParam, limit: 10 } : { limit: 10 } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
    staleTime: 30_000,
    retry: false,
  })
}
