import { QueryClient } from '@tanstack/react-query'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { agentKeys } from '@/lib/api/hooks/agents'
import { queryKeys } from '@/lib/api/queryKeys'
import type { Me, RunAllResult, RunRequest, RunRequestList } from '@/lib/api/types'
import { renderWithRouter } from '@/test/router'
import { useToastStore } from '@/features/notifications/toastStore'
import { RunAll } from './RunAll'
import { RunNow } from './RunNow'

const ADMIN = { handle: 'ryan', displayName: 'Ryan', kind: 'HUMAN', role: 'ADMIN', status: 'ACTIVE' } as Me

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

// a trader asked for @claude a minute before the server's clock; none ever asked for @chatgpt
const lists: Record<string, RunRequestList> = {
  claude: { botStatus: 'ACTIVE', traderSeenAt: '2026-10-14T14:59:00Z', asOf: '2026-10-14T15:00:00Z', requests: [] },
  chatgpt: { botStatus: 'ACTIVE', traderSeenAt: null, asOf: '2026-10-14T15:00:00Z', requests: [] },
}

const BOTS = [{ handle: 'chatgpt', status: 'ACTIVE' }, { handle: 'claude', status: 'ACTIVE' }, { handle: 'iced', status: 'FROZEN' }]

const result = (dryRun: boolean): RunAllResult => ({
  dryRun,
  asOf: '2026-10-14T14:31:00Z',
  requested: 1,
  results: [
    { handle: 'chatgpt', outcome: 'SKIPPED', reason: 'NO_TRADER', message: "No claude-trader service has asked for @chatgpt's runs in the last 5 minutes, so nothing would pick a run up.", request: null },
    { handle: 'claude', outcome: 'REQUESTED', reason: null, message: null, request: req({ dryRun }) },
    { handle: 'iced', outcome: 'SKIPPED', reason: 'NOT_ACTIVE', message: '@iced is FROZEN: it cannot trade, so it cannot be run.', request: null },
  ],
})

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': status < 300 ? 'application/json' : 'application/problem+json' } })

function client(): QueryClient {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  qc.setQueryData(queryKeys.me, ADMIN)
  return qc
}

describe('Run all (§20.9)', () => {
  let fetchMock: ReturnType<typeof vi.fn<(r: Request) => Promise<Response>>>
  const posts = () => fetchMock.mock.calls.map(([r]) => r).filter((r) => r.method === 'POST')

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    fetchMock = vi.fn<(r: Request) => Promise<Response>>()
    vi.stubGlobal('fetch', fetchMock)
    vi.useFakeTimers({ toFake: ['Date'], now: Date.parse('2026-10-14T15:00:00Z') })
    useToastStore.setState({ toasts: [] })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  function serve(dryRun = false) {
    fetchMock.mockImplementation(async (r) => {
      const path = new URL(r.url).pathname
      if (r.method === 'POST' && path === '/api/v1/me/bots/run-requests') return json(result(dryRun))
      const handle = /\/me\/bots\/([^/]+)\/run-requests$/.exec(path)?.[1]
      return handle && lists[handle] ? json(lists[handle]) : json({ code: 'PLAYER_NOT_FOUND', title: 'Not found', status: 404 }, 404)
    })
  }

  it('names the bots it would run and the ones it would not, then runs them and sums up per bot', async () => {
    serve()
    renderWithRouter(
      <>
        <RunAll bots={BOTS} />
        <RunNow handle="claude" />
      </>,
      client(),
    )
    // nothing is fetched for Run all until it is asked for (the Run now button fetches its own list)
    await screen.findByRole('group', { name: 'Run @claude now' })
    fireEvent.click(within(screen.getByRole('group', { name: 'Run all bots' })).getByRole('button', { name: 'Run all' }))
    const dialog = await screen.findByRole('dialog', { name: 'Run all bots now?' })
    const toRun = await within(dialog).findByRole('list', { name: 'Bots to run' })
    expect(within(toRun).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['@claude'])
    expect(within(dialog).getByText(/They trade for real/)).toBeTruthy()
    const notRun = within(dialog).getByRole('list', { name: 'Bots not run' })
    expect(within(notRun).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['@chatgpt: no claude-trader service serves it', '@iced: FROZEN: it cannot trade'])
    // the frozen bot's list is never asked for
    expect(fetchMock.mock.calls.some(([r]) => r.url.includes('/iced/'))).toBe(false)

    fireEvent.click(within(dialog).getByRole('button', { name: 'Run 1 bot' }))
    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(new URL(posts()[0]!.url).pathname).toBe('/api/v1/me/bots/run-requests')
    expect(posts()[0]!.headers.get('X-XSRF-TOKEN')).toBe('test-token')
    expect(await posts()[0]!.json()).toEqual({ dryRun: false })

    const summary = await screen.findByRole('status', { name: 'Run all result' })
    expect(within(summary).getByText('Run all at 10:31 AM ET: 1 requested · 2 skipped')).toBeTruthy()
    expect(within(summary).getByText('Requested')).toBeTruthy()
    expect(within(summary).getByText(/No claude-trader service has asked for @chatgpt's runs/)).toBeTruthy()
    expect(within(summary).getByText('@iced is FROZEN: it cannot trade, so it cannot be run.')).toBeTruthy()
    expect(screen.queryByRole('dialog', { name: 'Run all bots now?' })).toBeNull()
    expect(useToastStore.getState().toasts[0]).toMatchObject({ tone: 'neutral', title: 'Run requested for 1 bot' })
    // the bot's own Run now line follows the new request
    expect(await screen.findByText('Requested 10:31 AM ET · waiting for the trader')).toBeTruthy()
    expect((within(screen.getByRole('group', { name: 'Run @claude now' })).getByRole('button', { name: 'Run now' }) as HTMLButtonElement).disabled).toBe(true)

    fireEvent.click(within(summary).getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByRole('status', { name: 'Run all result' })).toBeNull()
  })

  it('offers Dry run all from its menu', async () => {
    serve(true)
    renderWithRouter(<RunAll bots={BOTS} />, client())
    fireEvent.click(await screen.findByRole('button', { name: 'More Run all options' }))
    const menu = screen.getByRole('dialog', { name: 'Run all options' })
    expect(within(menu).getByText(/A dry run blocks the order tools/)).toBeTruthy()
    fireEvent.click(within(menu).getByRole('button', { name: 'Dry run all (no orders)' }))
    const dialog = await screen.findByRole('dialog', { name: 'Dry run all bots?' })
    expect(await within(dialog).findByText(/each bot only says what it would trade/)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Dry run 1 bot' }))
    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(await posts()[0]!.json()).toEqual({ dryRun: true })
    const summary = await screen.findByRole('status', { name: 'Run all result' })
    expect(within(summary).getByText(/^Dry run all at 10:31 AM ET/)).toBeTruthy()
    expect(within(summary).getByText('Dry run requested')).toBeTruthy()
    expect(useToastStore.getState().toasts[0]?.title).toBe('Dry run requested for 1 bot')
  })

  it('cannot be confirmed while no bot can be run', async () => {
    serve()
    const qc = client()
    qc.setQueryData(agentKeys.runRequests('claude'), { ...lists.claude, requests: [req({ status: 'CLAIMED', claimedAt: '2026-10-14T14:31:40Z' })] })
    renderWithRouter(<RunAll bots={BOTS} />, qc)
    fireEvent.click(await screen.findByRole('button', { name: 'Run all' }))
    const dialog = await screen.findByRole('dialog', { name: 'Run all bots now?' })
    expect(await within(dialog).findByText('No bot can be run right now.')).toBeTruthy()
    expect(within(dialog).getByText('@claude: a run is already on its way')).toBeTruthy()
    expect((within(dialog).getByRole('button', { name: 'Run all' }) as HTMLButtonElement).disabled).toBe(true)
    expect(posts()).toHaveLength(0)
  })

  it('shows a refused Run all in the dialog', async () => {
    fetchMock.mockImplementation(async (r) =>
      r.method === 'POST'
        ? json({ code: 'RATE_LIMITED', title: 'Too many requests', status: 429, detail: 'Rate limit exceeded; retry in 12 s.', retryAfterS: 12 }, 429)
        : json(lists.claude),
    )
    renderWithRouter(<RunAll bots={[{ handle: 'claude', status: 'ACTIVE' }]} />, client())
    fireEvent.click(await screen.findByRole('button', { name: 'Run all' }))
    const dialog = await screen.findByRole('dialog', { name: 'Run all bots now?' })
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Run 1 bot' }))
    expect(await within(dialog).findByRole('alert')).toBeTruthy()
    expect(within(dialog).getByRole('alert').textContent).toContain('retry in 12 s')
    expect(screen.queryByRole('status', { name: 'Run all result' })).toBeNull()
  })
})
