/**
 * Phase 2 agent hooks (WS5): a bot's public run summary and AI journal (§20.6, §20.7), the admin Bots page and the
 * "Recent API calls" views (§17.10).
 */
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'

export const agentKeys = {
  summary: (handle: string) => ['player', handle, 'agent-runs'] as const,
  journal: (handle: string) => ['player', handle, 'journal'] as const,
  bots: queryKeys.admin('bots'),
  runs: (filter: { handle?: string; status?: string }) => queryKeys.admin('agent-runs', filter),
  myCalls: (handle: string) => ['apiCalls', 'me', handle] as const,
  allCalls: (handle: string) => queryKeys.admin('api-calls', handle),
}

/** `GET /players/{handle}/agent-runs`: the public Bot activity card (no cost, no message). */
export function useAgentRunSummary(handle: string, enabled = true) {
  return useQuery({
    queryKey: agentKeys.summary(handle),
    queryFn: () => unwrap(api.GET('/api/v1/players/{handle}/agent-runs', { params: { path: { handle } } })),
    enabled,
    staleTime: 60_000,
    retry: false,
  })
}

/** `GET /players/{handle}/journal`: visible notes, newest first. */
export function usePlayerJournal(handle: string, enabled = true) {
  return useInfiniteQuery({
    queryKey: agentKeys.journal(handle),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/players/{handle}/journal', {
          params: { path: { handle }, query: pageParam ? { cursor: pageParam, limit: 10 } : { limit: 10 } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
    staleTime: 60_000,
    retry: false,
  })
}

/** `GET /admin/bots`: every AI player's runs, spend against the cap and tool calls. */
export function useAdminBots() {
  return useQuery({
    queryKey: agentKeys.bots,
    queryFn: () => unwrap(api.GET('/api/v1/admin/bots')),
    staleTime: 15_000,
    refetchInterval: 60_000,
  })
}

/** `GET /admin/agent-runs`: every reported run, newest first, by bot and status. */
export function useAdminAgentRuns(filter: { handle?: string; status?: string }) {
  return useInfiniteQuery({
    queryKey: agentKeys.runs(filter),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/admin/agent-runs', {
          params: {
            query: {
              limit: 25,
              ...(filter.handle ? { handle: filter.handle } : {}),
              ...(filter.status ? { status: filter.status } : {}),
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 15_000,
  })
}

/**
 * Recent API calls (§17.10): the caller's own keys and bots (`/me/api-calls`), or, for the admin's "Everyone" view,
 * every player's (`/admin/api-calls`). `handle` narrows to one player.
 */
export function useApiCalls(scope: 'mine' | 'everyone', handle: string) {
  return useInfiniteQuery({
    queryKey: scope === 'mine' ? agentKeys.myCalls(handle) : agentKeys.allCalls(handle),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => {
      const query = { limit: 50, ...(handle ? { handle } : {}), ...(pageParam ? { cursor: pageParam } : {}) }
      return scope === 'mine'
        ? unwrap(api.GET('/api/v1/me/api-calls', { params: { query } }))
        : unwrap(api.GET('/api/v1/admin/api-calls', { params: { query } }))
    },
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 10_000,
    retry: false,
  })
}
