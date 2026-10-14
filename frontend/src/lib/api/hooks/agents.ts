/**
 * Phase 2 agent hooks (WS5): a bot's public run summary and AI journal (§20.6, §20.7), the admin Bots page, the
 * "Recent API calls" views (§17.10), "Run now" and "Run all" (§20.9).
 */
import { type QueryClient, queryOptions, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { RunRequest, RunRequestList } from '../types'

export const agentKeys = {
  summary: (handle: string) => ['player', handle, 'agent-runs'] as const,
  journal: (handle: string) => ['player', handle, 'journal'] as const,
  bots: queryKeys.admin('bots'),
  runs: (filter: { handle?: string; status?: string }) => queryKeys.admin('agent-runs', filter),
  myCalls: (handle: string) => ['apiCalls', 'me', handle] as const,
  allCalls: (handle: string) => queryKeys.admin('api-calls', handle),
  runRequests: (handle: string) => ['runRequests', handle] as const,
}

/** How often the Run now state is polled while the latest request is open (PENDING or CLAIMED). */
export const RUN_REQUEST_POLL_MS = 5_000

const openRequest = (r: RunRequest | null | undefined) => r?.status === 'PENDING' || r?.status === 'CLAIMED'

/**
 * `GET /me/bots/{handle}/run-requests` (§20.9, the bot's owner or the admin): the latest requests, newest first, the
 * bot's status and when a trader last asked for its requests. Polled every 5 s while the latest one waits for the
 * trader or runs, so the button follows it to DONE. Run all reads the same queries.
 */
export const runRequestsQuery = (handle: string) =>
  queryOptions({
    queryKey: agentKeys.runRequests(handle),
    queryFn: () => unwrap(api.GET('/api/v1/me/bots/{handle}/run-requests', { params: { path: { handle }, query: { limit: 5 } } })),
    staleTime: 10_000,
    retry: false,
    refetchInterval: (q) => (openRequest(q.state.data?.requests?.[0]) ? RUN_REQUEST_POLL_MS : false),
  })

export function useRunRequests(handle: string, enabled = true) {
  return useQuery({ ...runRequestsQuery(handle), enabled: enabled && Boolean(handle) })
}

/** `r` as the bot's latest request in its cached list (when the list is cached), so its Run now line follows it. */
function showRequest(qc: QueryClient, r: RunRequest): void {
  if (!r.handle) return
  qc.setQueryData<RunRequestList>(agentKeys.runRequests(r.handle), (prev) =>
    prev ? { ...prev, requests: [r, ...(prev.requests ?? []).filter((x) => x.id !== r.id)] } : prev,
  )
}

/** `POST /me/bots/{handle}/run-requests`: ask the claude-trader service for one of the bot's check-ins now. */
export function useRequestRun() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ handle, dryRun }: { handle: string; dryRun: boolean }) =>
      unwrap(api.POST('/api/v1/me/bots/{handle}/run-requests', { params: { path: { handle } }, body: { dryRun } })),
    onSuccess: (r) => showRequest(qc, r),
    // a 409 means another request is open: show it
    onError: (_e, { handle }) => void qc.invalidateQueries({ queryKey: agentKeys.runRequests(handle) }),
  })
}

/**
 * `POST /me/bots/run-requests` ("Run all", §20.9): a request for every bot the caller may run, by the per-bot rules;
 * the answer says per bot what happened. The new (or already open) requests go into each bot's cached list, so the
 * Run now lines on the page follow them; the lists of skipped bots are fetched again.
 */
export function useRunAll() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ dryRun }: { dryRun: boolean }) => unwrap(api.POST('/api/v1/me/bots/run-requests', { body: { dryRun } })),
    onSuccess: (result) => {
      for (const item of result.results ?? []) {
        if (item.request) showRequest(qc, item.request)
        else if (item.handle) void qc.invalidateQueries({ queryKey: agentKeys.runRequests(item.handle) })
      }
    },
  })
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
