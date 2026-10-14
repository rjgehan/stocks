import { QueryClient } from '@tanstack/react-query'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RUN_REQUEST_POLL_MS, agentKeys } from '@/lib/api/hooks/agents'
import { queryKeys } from '@/lib/api/queryKeys'
import type { AdminPlayer, Me, PlayerProfile, RunRequest, RunRequestList } from '@/lib/api/types'
import { renderWithRouter } from '@/test/router'
import { PlayerActions } from '@/features/admin/PlayerActions'
import { useToastStore } from '@/features/notifications/toastStore'
import { BotActivityCard } from './BotActivityCard'
import { RunNow } from './RunNow'

const ME = { handle: 'ryan', displayName: 'Ryan', kind: 'HUMAN', role: 'PLAYER', status: 'ACTIVE' } as Me

const req = (over: Partial<RunRequest> = {}): RunRequest => ({
  id: 'arq_1',
  handle: 'claude',
  requestedBy: 'ryan',
  requestedAt: '2026-10-14T14:31:00Z',
  dryRun: false,
  status: 'PENDING',
  claimedAt: null,
  finishedAt: null,
  message: null,
  ...over,
})

// a trader asked for the bot a minute before the fake clock (15:00Z)
const list = (requests: RunRequest[] = [], over: Partial<RunRequestList> = {}): RunRequestList => ({ botStatus: 'ACTIVE', traderSeenAt: '2026-10-14T14:59:00Z', requests, ...over })

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status < 300 ? 'application/json' : 'application/problem+json' } })

function client(me: Me | null = ME): QueryClient {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  qc.setQueryData(queryKeys.me, me)
  return qc
}

describe('Run now (§20.9)', () => {
  let fetchMock: ReturnType<typeof vi.fn<(r: Request) => Promise<Response>>>

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    fetchMock = vi.fn<(r: Request) => Promise<Response>>()
    vi.stubGlobal('fetch', fetchMock)
    // only the clock: the requests below are from "today" (2026-10-14, ET)
    vi.useFakeTimers({ toFake: ['Date'], now: Date.parse('2026-10-14T15:00:00Z') })
    useToastStore.setState({ toasts: [] })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('asks for a run and shows it waiting for the trader', async () => {
    fetchMock.mockImplementation(async (r) => (r.method === 'POST' ? json(req(), 201) : json(list())))
    renderWithRouter(<RunNow handle="claude" />, client())
    const run = await screen.findByRole('button', { name: 'Run now' })
    expect(run.getAttribute('title')).toContain('within about a minute')
    fireEvent.click(run)
    await waitFor(() => expect(fetchMock.mock.calls.some(([r]) => r.method === 'POST')).toBe(true))
    const post = fetchMock.mock.calls.find(([r]) => r.method === 'POST')![0]
    expect(new URL(post.url).pathname).toBe('/api/v1/me/bots/claude/run-requests')
    expect(await post.json()).toEqual({ dryRun: false })
    expect(await screen.findByText('Requested 10:31 AM ET · waiting for the trader')).toBeTruthy()
    expect(screen.getByText('Waiting')).toBeTruthy()
    // one open request per bot: the button waits for it
    expect((screen.getByRole('button', { name: 'Run now' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('offers a dry run from the menu', async () => {
    fetchMock.mockImplementation(async (r) => (r.method === 'POST' ? json(req({ dryRun: true }), 201) : json(list())))
    renderWithRouter(<RunNow handle="claude" />, client())
    fireEvent.click(await screen.findByRole('button', { name: 'More run options' }))
    const menu = screen.getByRole('dialog', { name: 'Run options for @claude' })
    expect(within(menu).getByText(/A dry run blocks the order tools/)).toBeTruthy()
    fireEvent.click(within(menu).getByRole('button', { name: 'Dry run (no orders)' }))
    await waitFor(() => expect(fetchMock.mock.calls.some(([r]) => r.method === 'POST')).toBe(true))
    expect(await fetchMock.mock.calls.find(([r]) => r.method === 'POST')![0].json()).toEqual({ dryRun: true })
    expect(await screen.findByText('Dry run requested 10:31 AM ET · waiting for the trader')).toBeTruthy()
  })

  it('shows the latest request: done with the report, or expired with a hint', async () => {
    const qc = client()
    qc.setQueryData(
      agentKeys.runRequests('claude'),
      list([req({ status: 'DONE', claimedAt: '2026-10-14T14:31:40Z', finishedAt: '2026-10-14T14:35:10Z', message: 'Check-in done in 3 min 30 s, 12 turns.' })]),
    )
    renderWithRouter(<RunNow handle="claude" />, qc)
    expect(await screen.findByText(/picked up 10:31 AM · done 10:35 AM/)).toBeTruthy()
    expect(screen.getByText('Check-in done in 3 min 30 s, 12 turns.')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Run now' }) as HTMLButtonElement).disabled).toBe(false)

    qc.setQueryData(agentKeys.runRequests('claude'), list([req({ status: 'EXPIRED', finishedAt: '2026-10-14T15:01:00Z' })]))
    expect(await screen.findByText('Expired: the trader did not pick it up — is the claude-trader service running?')).toBeTruthy()
  })

  it('polls every 5 s while the latest request is open, and stops once it is final', async () => {
    const qc = client()
    fetchMock.mockImplementation(async () => json(list([req({ status: 'CLAIMED', claimedAt: '2026-10-14T14:31:40Z' })])))
    renderWithRouter(<RunNow handle="claude" />, qc)
    expect(await screen.findByText(/picked up 10:31 AM · running/)).toBeTruthy()
    const query = qc.getQueryCache().find({ queryKey: agentKeys.runRequests('claude') })!
    // the mounted observer's option: a function of the query's latest data
    const interval = query.observers[0]!.options.refetchInterval as unknown as (q: typeof query) => number | false
    expect(interval(query)).toBe(RUN_REQUEST_POLL_MS)
    qc.setQueryData(agentKeys.runRequests('claude'), list([req({ status: 'FAILED', finishedAt: '2026-10-14T14:40:00Z', message: 'The trader did not report back.' })]))
    expect(interval(query)).toBe(false)
  })

  it('names the open request when the server refuses another one (409)', async () => {
    fetchMock.mockImplementation(async (r) =>
      r.method === 'POST'
        ? json({ code: 'RUN_REQUEST_CONFLICT', title: 'Conflict', status: 409, detail: '@claude is already running a requested check-in (picked up at 10:31 ET); wait until it reports back.' }, 409)
        : json(list()),
    )
    renderWithRouter(<RunNow handle="claude" />, client())
    fireEvent.click(await screen.findByRole('button', { name: 'Run now' }))
    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1))
    expect(useToastStore.getState().toasts[0]).toMatchObject({ tone: 'error', title: 'Run not requested', body: expect.stringContaining('already running a requested check-in') })
  })

  it('appears on the Bot activity card for the owner only', async () => {
    fetchMock.mockImplementation(async () => json(list()))
    const profile = { player: { handle: 'claude', kind: 'AI' }, ownerHandle: 'ryan' } as PlayerProfile
    const qc = client()
    qc.setQueryData(queryKeys.player('claude'), profile)
    qc.setQueryData(agentKeys.summary('claude'), { handle: 'claude', runs: 0, runsLast7Days: 0, lastRunAt: null, lastStatus: null, lastRunOrders: null, lastRunToolCalls: null })
    const { unmount } = renderWithRouter(<BotActivityCard handle="claude" />, qc)
    expect(await screen.findByRole('group', { name: 'Run @claude now' })).toBeTruthy()
    unmount()

    const stranger = client({ ...ME, handle: 'alice' })
    stranger.setQueryData(queryKeys.player('claude'), profile)
    stranger.setQueryData(agentKeys.summary('claude'), qc.getQueryData(agentKeys.summary('claude')))
    renderWithRouter(<BotActivityCard handle="claude" />, stranger)
    expect(await screen.findByText(/No runs reported yet/)).toBeTruthy()
    expect(screen.queryByRole('group', { name: 'Run @claude now' })).toBeNull()
  })

  it('is in the admin players menu for an active bot', async () => {
    fetchMock.mockImplementation(async (r) => (r.method === 'POST' ? json(req({ dryRun: true }), 201) : json(list())))
    const bot = { handle: 'claude', kind: 'AI', status: 'ACTIVE', role: 'PLAYER' } as AdminPlayer
    renderWithRouter(<PlayerActions p={bot} myHandle="ryan" onIssueKey={() => {}} />, client({ ...ME, role: 'ADMIN' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Actions for @claude' }))
    const dry = screen.getByRole('button', { name: 'Dry run (no orders)' }) as HTMLButtonElement
    // the menu asks whether a trader serves the bot first
    await waitFor(() => expect(dry.disabled).toBe(false))
    fireEvent.click(dry)
    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1))
    expect(useToastStore.getState().toasts[0]?.title).toBe('Dry run requested for @claude')
    expect(await fetchMock.mock.calls.find(([r]) => r.method === 'POST')![0].json()).toEqual({ dryRun: true })
  })

  it('is disabled, with the reason, for a bot that no trader serves', async () => {
    // e.g. @chatgpt, which the agent runner drives: the claude-trader service never asks for its requests
    fetchMock.mockImplementation(async () => json(list([], { traderSeenAt: null })))
    const { unmount } = renderWithRouter(<RunNow handle="chatgpt" />, client())
    const reason = "No claude-trader service has asked for @chatgpt's runs in the last few minutes"
    expect(await screen.findByText(new RegExp(reason))).toBeTruthy()
    const run = screen.getByRole('button', { name: 'Run now' }) as HTMLButtonElement
    expect(run.disabled).toBe(true)
    expect(run.getAttribute('title')).toContain(reason)
    expect((screen.getByRole('button', { name: 'More run options' }) as HTMLButtonElement).disabled).toBe(true)
    unmount()

    const bot = { handle: 'chatgpt', kind: 'AI', status: 'ACTIVE', role: 'PLAYER' } as AdminPlayer
    renderWithRouter(<PlayerActions p={bot} myHandle="ryan" onIssueKey={() => {}} />, client({ ...ME, role: 'ADMIN' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Actions for @chatgpt' }))
    expect(await screen.findByText(new RegExp(reason))).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Run now' }) as HTMLButtonElement).disabled).toBe(true)
    expect(fetchMock.mock.calls.every(([r]) => r.method === 'GET')).toBe(true)
  })

  it('is disabled on the profile of a frozen bot', async () => {
    fetchMock.mockImplementation(async () => json(list([], { botStatus: 'FROZEN' })))
    const qc = client()
    qc.setQueryData(queryKeys.player('claude'), { player: { handle: 'claude', kind: 'AI' }, ownerHandle: 'ryan' } as PlayerProfile)
    qc.setQueryData(agentKeys.summary('claude'), { handle: 'claude', runs: 0, runsLast7Days: 0, lastRunAt: null, lastStatus: null, lastRunOrders: null, lastRunToolCalls: null })
    renderWithRouter(<BotActivityCard handle="claude" />, qc)
    expect(await screen.findByText('@claude is FROZEN: it cannot trade, so it cannot be run.')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Run now' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('is not offered for a frozen bot in that menu', async () => {
    const frozen = { handle: 'chatgpt', kind: 'AI', status: 'FROZEN', role: 'PLAYER' } as AdminPlayer
    renderWithRouter(<PlayerActions p={frozen} myHandle="ryan" onIssueKey={() => {}} />, client({ ...ME, role: 'ADMIN' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Actions for @chatgpt' }))
    expect(screen.getByRole('button', { name: 'Issue API key…' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Run now' })).toBeNull()
  })
})
