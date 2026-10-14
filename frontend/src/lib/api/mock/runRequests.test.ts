import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PlayerSummary, RunRequestList } from '../types'
import type { RouteFn } from './agents'
import { MOCK_CLAIM_AFTER_MS, MOCK_DONE_AFTER_MS, registerRunRequestRoutes } from './runRequests'

type Ctx = { url: URL; params: string[]; body?: unknown }
const player = (handle: string, kind: 'HUMAN' | 'AI' = 'AI') => ({ handle, displayName: handle, kind }) as PlayerSummary
const url = (h: string) => new URL(`http://x/api/v1/me/bots/${h}/run-requests`)

function routes(isAdmin: boolean, owned: string[]) {
  const map = new Map<string, (ctx: Ctx) => Response | Promise<Response>>()
  const r: RouteFn = (method, pattern, handler) => map.set(`${method} ${pattern}`, handler)
  const everyone = [player('demo', 'HUMAN'), player('claude'), player('chatgpt'), player('alice', 'HUMAN')]
  registerRunRequestRoutes(r, { players: () => everyone, ownedBots: () => owned, me: () => everyone[0]!, isAdmin: () => isAdmin })
  return {
    post: async (h: string, body: unknown = { dryRun: false }) => map.get('POST /me/bots/{handle}/run-requests')!({ url: url(h), params: [h], body }),
    list: async (h: string) => map.get('GET /me/bots/{handle}/run-requests')!({ url: url(h), params: [h] }),
  }
}

describe('mock Run now (§20.9, §22.9)', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('moves a request PENDING → CLAIMED → DONE as the pretend trader would', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: Date.parse('2026-10-14T14:31:00Z') })
    const api = routes(false, ['claude'])
    const created = await api.post('claude', { dryRun: true })
    expect(created.status).toBe(201)
    expect(((await created.json()) as { status: string }).status).toBe('PENDING')
    // one open request per bot
    const again = await api.post('claude')
    expect(again.status).toBe(409)
    expect(((await again.json()) as { code: string }).code).toBe('RUN_REQUEST_CONFLICT')

    vi.setSystemTime(Date.parse('2026-10-14T14:31:00Z') + MOCK_CLAIM_AFTER_MS)
    expect(((await (await api.list('claude')).json()) as RunRequestList).requests![0]).toMatchObject({ status: 'CLAIMED', requestedBy: 'demo', dryRun: true })
    vi.setSystemTime(Date.parse('2026-10-14T14:31:00Z') + MOCK_DONE_AFTER_MS)
    const [done] = ((await (await api.list('claude')).json()) as RunRequestList).requests!
    expect(done).toMatchObject({ status: 'DONE', finishedAt: '2026-10-14T14:31:09.000Z' })
    expect(done!.message).toContain('Dry run')
    // final: the next one may go
    expect((await api.post('claude')).status).toBe(201)
    expect(((await (await api.list('claude')).json()) as RunRequestList).requests!.map((x) => x.status)).toEqual(['PENDING', 'DONE'])
  })

  it("lists the bot's status and when the pretend trader last asked (every bot but @chatgpt)", async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: Date.parse('2026-10-14T14:31:25Z') })
    const admin = routes(true, [])
    expect((await (await admin.list('claude')).json()) as RunRequestList).toEqual({
      botStatus: 'ACTIVE',
      traderSeenAt: '2026-10-14T14:31:00.000Z',
      asOf: '2026-10-14T14:31:25.000Z',
      requests: [],
    })
    expect(((await (await admin.list('chatgpt')).json()) as RunRequestList).traderSeenAt).toBeNull()
  })

  it('lets only the owner or the admin run a bot, and only bots', async () => {
    const owner = routes(false, ['claude'])
    expect((await owner.post('chatgpt')).status).toBe(403)
    expect((await owner.list('chatgpt')).status).toBe(403)
    expect((await owner.post('nobody')).status).toBe(404)
    const admin = routes(true, [])
    expect((await admin.post('chatgpt')).status).toBe(201)
    expect((await admin.post('alice')).status).toBe(403)
  })
})
