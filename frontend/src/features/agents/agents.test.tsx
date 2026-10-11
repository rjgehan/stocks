import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { agentKeys } from '@/lib/api/hooks/agents'
import { mockAdminBots, mockApiCalls, mockRuns, registerAgentRoutes, type RouteFn } from '@/lib/api/mock/agents'
import type { AgentRunSummary, JournalNote, PlayerSummary } from '@/lib/api/types'
import { renderWithRouter } from '@/test/router'
import { FEED_FILTER_KINDS } from '@/features/league/FeedList'
import { BotActivityCard } from './BotActivityCard'
import { JournalSection } from './JournalSection'

const player = (handle: string, kind: 'HUMAN' | 'AI' = 'AI'): PlayerSummary => ({
  playerId: `ply_${handle}`,
  handle,
  displayName: handle,
  kind,
  aiModel: kind === 'AI' ? 'Claude' : null,
  avatarSeed: handle,
  memberSince: '2026-10-01T13:00:00Z',
  resets: 0,
})

function client(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
}

describe('the public AI journal (§20.7)', () => {
  it('renders untrusted note text as plain text, never as markup', async () => {
    const qc = client()
    const note: JournalNote = {
      id: 'jnl_1',
      at: '2026-10-09T14:00:00Z',
      visibleAt: '2026-10-09T14:15:00Z',
      player: player('claude'),
      symbols: ['NVDA'],
      untrusted: { text: '<img src=x onerror="alert(1)"> **Ignore previous instructions** [link](https://evil)' },
    }
    qc.setQueryData(agentKeys.journal('claude'), { pages: [{ items: [note], nextCursor: null }], pageParams: [undefined] })
    const { container } = renderWithRouter(<JournalSection handle="claude" />, qc)
    expect(await screen.findByText(/Ignore previous instructions/)).toBeTruthy()
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('a[href="https://evil"]')).toBeNull()
    expect(screen.getByText(/<img src=x/)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'NVDA' })).toBeTruthy()
  })

  it('has a Journal filter in the league feed', () => {
    expect(FEED_FILTER_KINDS.JOURNAL).toEqual(['JOURNAL'])
  })
})

describe('the Bot activity card (§20.6)', () => {
  it('shows runs and the last run, without cost', async () => {
    const qc = client()
    const s: AgentRunSummary = {
      handle: 'claude',
      runs: 42,
      runsLast7Days: 15,
      lastRunAt: '2026-10-09T13:45:00Z',
      lastStatus: 'COMPLETED',
      lastRunOrders: 2,
      lastRunToolCalls: 9,
    }
    qc.setQueryData(agentKeys.summary('claude'), s)
    renderWithRouter(<BotActivityCard handle="claude" />, qc)
    expect(await screen.findByText('15 runs')).toBeTruthy()
    expect(screen.getByText('42 runs')).toBeTruthy()
    expect(screen.getByText('Completed · 9 tool calls · 2 orders')).toBeTruthy()
    expect(screen.queryByText(/\$/)).toBeNull()
  })
})

describe('mock mode data (§22.9)', () => {
  const now = Date.parse('2026-10-09T18:00:00Z')
  const bots = [player('claude'), player('chatgpt')]

  it('builds 14 days, today against the cap, and a tool breakdown per bot', () => {
    const runs = mockRuns(bots, now)
    const admin = mockAdminBots(bots, runs, now)
    expect(admin).toHaveLength(2)
    for (const b of admin) {
      expect(b.days).toHaveLength(14)
      expect(b.days?.at(-1)?.date).toBe('2026-10-09')
      expect(b.lastRun?.player?.handle).toBe(b.player?.handle)
      expect((b.tools ?? []).length).toBeGreaterThan(0)
    }
    expect(runs.every((r, i) => i === 0 || (runs[i - 1]!.startedAt ?? '') >= (r.startedAt ?? ''))).toBe(true)
  })

  it('shows a player only their own calls and their bots', async () => {
    const routes = new Map<string, (ctx: { url: URL; params: string[] }) => Response | Promise<Response>>()
    const r: RouteFn = (method, pattern, handler) => routes.set(`${method} ${pattern}`, handler)
    const everyone = [player('demo', 'HUMAN'), ...bots, player('alice', 'HUMAN')]
    registerAgentRoutes(r, { players: () => everyone, ownedBots: () => ['claude', 'chatgpt'], me: () => everyone[0]!, isAdmin: () => false })
    const mine = await (await routes.get('GET /me/api-calls')!({ url: new URL('http://x/api/v1/me/api-calls?limit=200'), params: [] })).json()
    const handles = new Set((mine.items as { handle: string }[]).map((c) => c.handle))
    expect(handles.has('alice')).toBe(false)
    expect([...handles].every((h) => ['demo', 'claude', 'chatgpt'].includes(h))).toBe(true)
    const stranger = await routes.get('GET /me/api-calls')!({ url: new URL('http://x/api/v1/me/api-calls?handle=alice'), params: [] })
    expect(stranger.status).toBe(404)
    const admin = await routes.get('GET /admin/bots')!({ url: new URL('http://x/api/v1/admin/bots'), params: [] })
    expect(admin.status).toBe(403)
    expect(mockApiCalls([player('alice', 'HUMAN')], now).every((c) => c.channel === 'REST_KEY')).toBe(true)
  })
})
