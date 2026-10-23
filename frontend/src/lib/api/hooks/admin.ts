/**
 * Admin console hooks (§21.1). Routes marked R in the spec need a recent authentication: they run through
 * `withRecentAuth`, which prompts for the password on `403 RECENT_AUTH_REQUIRED` and retries once.
 */
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import { withRecentAuth } from '../reauth'
import type {
  CashTransferBody,
  CashTransferRequest,
  CorporateActionStatus,
  CreateCalendarOverrideRequest,
  CreatePlayerRequest,
  PlayerKind,
  ResolveCorporateActionRequest,
  UpdateSettingsRequest,
} from '../types'

const K = {
  players: queryKeys.admin('players'),
  player: (handle: string) => queryKeys.admin('player', handle),
  keys: (handle: string) => queryKeys.admin('player', handle, 'keys'),
  portfolio: (handle: string) => queryKeys.admin('player', handle, 'portfolio'),
  oauthGrants: (handle: string) => queryKeys.admin('player', handle, 'oauth-grants'),
  transfers: (filter: Record<string, unknown>) => queryKeys.admin('transfers', filter),
  settings: queryKeys.admin('settings'),
  invites: queryKeys.admin('invites'),
  audit: (filter: Record<string, unknown>) => queryKeys.admin('audit', filter),
  system: queryKeys.admin('system'),
  calendar: queryKeys.admin('calendar'),
  corporateActions: (status: string) => queryKeys.admin('corporate-actions', status),
}
export const adminKeys = K

// ---------------------------------------------------------------- players

export function useAdminPlayers() {
  return useQuery({ queryKey: K.players, queryFn: () => unwrap(api.GET('/api/v1/admin/players')), staleTime: 10_000 })
}

export function useAdminPlayer(handle: string) {
  return useQuery({
    queryKey: K.player(handle),
    queryFn: () => unwrap(api.GET('/api/v1/admin/players/{handle}', { params: { path: { handle } } })),
    staleTime: 10_000,
  })
}

function useInvalidatePlayers() {
  const qc = useQueryClient()
  return (handle?: string) => {
    void qc.invalidateQueries({ queryKey: K.players })
    if (handle) void qc.invalidateQueries({ queryKey: K.player(handle) })
    void qc.invalidateQueries({ queryKey: ['leaderboard'] })
  }
}

export function useCreatePlayer() {
  const invalidate = useInvalidatePlayers()
  return useMutation({
    mutationFn: (body: CreatePlayerRequest) => withRecentAuth(() => unwrap(api.POST('/api/v1/admin/players', { body }))),
    onSuccess: () => invalidate(),
  })
}

export interface PlayerPatch {
  displayName?: string
  aiModel?: string
  status?: 'ACTIVE' | 'DISABLED'
  role?: 'PLAYER' | 'ADMIN'
  apiTradingAllowed?: boolean
}

export function useUpdatePlayer() {
  const invalidate = useInvalidatePlayers()
  return useMutation({
    mutationFn: ({ handle, patch }: { handle: string; patch: PlayerPatch }) =>
      withRecentAuth(() => unwrap(api.PATCH('/api/v1/admin/players/{handle}', { params: { path: { handle } }, body: patch }))),
    onSuccess: (_p, { handle }) => invalidate(handle),
  })
}

export function useFreezePlayer() {
  const invalidate = useInvalidatePlayers()
  return useMutation({
    mutationFn: ({ handle, reason, revokeKeys }: { handle: string; reason: string; revokeKeys: boolean }) =>
      withRecentAuth(() => unwrap(api.POST('/api/v1/admin/players/{handle}/freeze', { params: { path: { handle } }, body: { reason, revokeKeys } }))),
    onSuccess: (_p, { handle }) => invalidate(handle),
  })
}

export function useUnfreezePlayer() {
  const invalidate = useInvalidatePlayers()
  return useMutation({
    mutationFn: (handle: string) => withRecentAuth(() => unwrap(api.POST('/api/v1/admin/players/{handle}/unfreeze', { params: { path: { handle } } }))),
    onSuccess: (_p, handle) => invalidate(handle),
  })
}

export function useSetPlayerPassword() {
  return useMutation({
    mutationFn: ({ handle, newPassword }: { handle: string; newPassword: string }) =>
      withRecentAuth(() => unwrap(api.POST('/api/v1/admin/players/{handle}/password', { params: { path: { handle } }, body: { newPassword } }))),
  })
}

export function useResetPlayer() {
  const invalidate = useInvalidatePlayers()
  return useMutation({
    mutationFn: ({ handle, memo, startingCash, idempotencyKey }: { handle: string; memo: string; startingCash?: string; idempotencyKey: string }) =>
      withRecentAuth(() =>
        unwrap(
          api.POST('/api/v1/admin/players/{handle}/reset', {
            params: { path: { handle } },
            body: startingCash ? { memo, startingCash } : { memo },
            headers: { 'Idempotency-Key': idempotencyKey },
          }),
        ),
      ),
    onSuccess: (_a, { handle }) => invalidate(handle),
  })
}

/**
 * `GET /admin/players/{handle}/portfolio`: the player's live portfolio for any status (public mode, §16.7; the break
 * glass is gone). Polls every 15 s while the page is open.
 */
export function useAdminPlayerPortfolio(handle: string) {
  return useQuery({
    queryKey: K.portfolio(handle),
    queryFn: () => unwrap(api.GET('/api/v1/admin/players/{handle}/portfolio', { params: { path: { handle } } })),
    staleTime: 5_000,
    refetchInterval: 15_000,
    retry: false,
  })
}

export function useAdminCancelOrder() {
  return useMutation({
    mutationFn: (orderId: string) => withRecentAuth(() => unwrap(api.DELETE('/api/v1/admin/orders/{orderId}', { params: { path: { orderId } } }))),
  })
}

// ---------------------------------------------------------------- API keys

export function useIssueKey() {
  const invalidate = useInvalidatePlayers()
  return useMutation({
    mutationFn: ({ handle, name, scopes, expiresInDays }: { handle: string; name: string; scopes?: string[]; expiresInDays?: number }) =>
      withRecentAuth(() =>
        unwrap(
          api.POST('/api/v1/admin/players/{handle}/api-keys', {
            params: { path: { handle } },
            body: { name, ...(scopes ? { scopes } : {}), ...(expiresInDays ? { expiresInDays } : {}) },
          }),
        ),
      ),
    onSuccess: (_k, { handle }) => invalidate(handle),
  })
}

export function useRevokeKey() {
  const invalidate = useInvalidatePlayers()
  return useMutation({
    mutationFn: ({ keyId }: { keyId: string; handle?: string }) => unwrap(api.DELETE('/api/v1/admin/api-keys/{keyId}', { params: { path: { keyId } } })),
    onSuccess: (_r, { handle }) => invalidate(handle),
  })
}

// ---------------------------------------------------------------- OAuth connectors (§17.9, §21.1)

/** `GET /admin/players/{handle}/oauth-grants`: the player's live connectors (acting as them, or approved by them). */
export function useAdminOAuthGrants(handle: string) {
  return useQuery({
    queryKey: K.oauthGrants(handle),
    queryFn: () => unwrap(api.GET('/api/v1/admin/players/{handle}/oauth-grants', { params: { path: { handle } } })),
    staleTime: 10_000,
  })
}

/** `DELETE /admin/oauth-grants/{grantId}`: ends a connector at once. */
export function useAdminRevokeOAuthGrant() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ grantId }: { grantId: string; handle: string }) =>
      unwrap(api.DELETE('/api/v1/admin/oauth-grants/{grantId}', { params: { path: { grantId } } })),
    onSuccess: (_r, { handle }) => void qc.invalidateQueries({ queryKey: K.oauthGrants(handle) }),
  })
}

// ---------------------------------------------------------------- money (§21.2)

const wire = (body: CashTransferBody) => body as unknown as CashTransferRequest

/** `POST /cash-transfers/preview` (no writes): per-account E⁻, U, mark quality and warnings. */
export function useTransferPreview(body: CashTransferBody | null) {
  return useQuery({
    queryKey: queryKeys.admin('transfer-preview', body),
    queryFn: () => unwrap(api.POST('/api/v1/admin/cash-transfers/preview', { body: wire(body!) })),
    enabled: body !== null,
    staleTime: 5_000,
    placeholderData: (prev) => prev,
    retry: false,
  })
}

export function useApplyTransfer() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: CashTransferBody; idempotencyKey: string }) =>
      withRecentAuth(() =>
        unwrap(api.POST('/api/v1/admin/cash-transfers', { body: wire(body), headers: { 'Idempotency-Key': idempotencyKey } })),
      ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.admin() })
      void qc.invalidateQueries({ queryKey: queryKeys.account })
      void qc.invalidateQueries({ queryKey: ['activities'] })
      void qc.invalidateQueries({ queryKey: ['leaderboard'] })
    },
  })
}

export function useTransfers(filter: { handle?: string } = {}) {
  return useInfiniteQuery({
    queryKey: K.transfers(filter),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/admin/cash-transfers', {
          params: { query: { ...(filter.handle ? { handle: filter.handle } : {}), ...(pageParam ? { cursor: pageParam } : {}), limit: 25 } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 10_000,
  })
}

// ---------------------------------------------------------------- settings, invites, audit, system

export function useLeagueSettings() {
  return useQuery({ queryKey: K.settings, queryFn: () => unwrap(api.GET('/api/v1/admin/settings')), staleTime: 30_000 })
}

export function useUpdateLeagueSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (patch: UpdateSettingsRequest) => withRecentAuth(() => unwrap(api.PUT('/api/v1/admin/settings', { body: patch }))),
    onSuccess: (settings) => {
      qc.setQueryData(K.settings, settings)
      // crypto.enabled shows in the clock and the rulebook; options.enabled in the rulebook and the option chains
      void qc.invalidateQueries({ queryKey: queryKeys.clock })
      void qc.invalidateQueries({ queryKey: queryKeys.rules })
      void qc.invalidateQueries({ queryKey: ['options'] })
    },
  })
}

export function useInvites() {
  return useQuery({ queryKey: K.invites, queryFn: () => unwrap(api.GET('/api/v1/admin/invites')), staleTime: 10_000 })
}

export function useCreateInvite() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { maxUses: number; expiresInHours: number }) => unwrap(api.POST('/api/v1/admin/invites', { body })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: K.invites }),
  })
}

export function useRevokeInvite() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (inviteId: string) => unwrap(api.DELETE('/api/v1/admin/invites/{inviteId}', { params: { path: { inviteId } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: K.invites }),
  })
}

export interface AuditFilter {
  category?: string
  actor?: string
  action?: string
}

export function useAudit(filter: AuditFilter) {
  return useInfiniteQuery({
    queryKey: K.audit({ ...filter }),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/admin/audit', {
          params: {
            query: {
              ...(filter.category ? { category: filter.category } : {}),
              ...(filter.actor ? { actor: filter.actor } : {}),
              ...(filter.action ? { action: filter.action } : {}),
              ...(pageParam ? { cursor: pageParam } : {}),
              limit: 50,
            },
          },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 10_000,
  })
}

export function useSystemStatus() {
  return useQuery({ queryKey: K.system, queryFn: () => unwrap(api.GET('/api/v1/admin/system')), staleTime: 5_000, refetchInterval: 15_000 })
}

export function useRunJob() {
  const qc = useQueryClient()
  return useMutation({
    /** `runKey` defaults (server side) to the latest past occurrence of the job. */
    mutationFn: ({ jobName, runKey }: { jobName: string; runKey?: string }) =>
      withRecentAuth(() =>
        unwrap(api.POST('/api/v1/admin/system/jobs/{jobName}/run', { params: { path: { jobName } }, body: runKey ? { runKey } : {} })),
      ),
    onSuccess: () => void qc.invalidateQueries({ queryKey: K.system }),
  })
}

export function useHaltEngine() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (reason: string) => withRecentAuth(() => unwrap(api.POST('/api/v1/admin/engine/halt', { body: { reason } }))),
    onSuccess: (s) => qc.setQueryData(K.system, s),
  })
}

export function useResumeEngine() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => withRecentAuth(() => unwrap(api.POST('/api/v1/admin/engine/resume'))),
    onSuccess: (s) => qc.setQueryData(K.system, s),
  })
}

export const ALL_KINDS: readonly PlayerKind[] = ['HUMAN', 'AI']

// ---------------------------------------------------------------- calendar overrides (§21.1, R for writes)

/** Overrides from `from` to `to` (ET dates, ≤ 400 days). */
export function useCalendarOverrides(from: string, to: string) {
  return useQuery({
    queryKey: [...K.calendar, from, to],
    queryFn: () => unwrap(api.GET('/api/v1/admin/calendar/overrides', { params: { query: { from, to } } })),
    staleTime: 60_000,
  })
}

export function useCreateCalendarOverride() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateCalendarOverrideRequest) => withRecentAuth(() => unwrap(api.POST('/api/v1/admin/calendar/overrides', { body }))),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: K.calendar })
      void qc.invalidateQueries({ queryKey: queryKeys.clock })
    },
  })
}

export function useDeleteCalendarOverride() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (date: string) => withRecentAuth(() => unwrap(api.DELETE('/api/v1/admin/calendar/overrides/{date}', { params: { path: { date } } }))),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: K.calendar })
      void qc.invalidateQueries({ queryKey: queryKeys.clock })
    },
  })
}

// ---------------------------------------------------------------- corporate actions (§14, §21.1; resolve is R)

export type CorporateActionFilter = CorporateActionStatus | 'ALL'

/** `GET /admin/corporate-actions?status` (newest first, cursor-paged). */
export function useCorporateActions(status: CorporateActionFilter) {
  return useInfiniteQuery({
    queryKey: K.corporateActions(status),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/v1/admin/corporate-actions', {
          params: { query: { ...(status === 'ALL' ? {} : { status }), ...(pageParam ? { cursor: pageParam } : {}), limit: 50 } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 10_000,
  })
}

/**
 * `POST /admin/corporate-actions/{id}/resolve` (§14 split guard, `@RecentAuth`): APPLY a split with confirmed rates,
 * or SKIP it to release the corporate-action hold. The note (1–200 characters) is audited.
 */
export function useResolveCorporateAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: ResolveCorporateActionRequest }) =>
      withRecentAuth(() => unwrap(api.POST('/api/v1/admin/corporate-actions/{id}/resolve', { params: { path: { id } }, body }))),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.admin('corporate-actions') })
      void qc.invalidateQueries({ queryKey: K.system })
      void qc.invalidateQueries({ queryKey: queryKeys.positions })
      void qc.invalidateQueries({ queryKey: queryKeys.account })
      void qc.invalidateQueries({ queryKey: ['activities'] })
      void qc.invalidateQueries({ queryKey: ['orders'] })
    },
  })
}
