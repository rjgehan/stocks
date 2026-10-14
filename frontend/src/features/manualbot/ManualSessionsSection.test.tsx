import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createMemoryHistory, createRootRoute, createRouter } from '@tanstack/react-router'
import type { ManualSession } from '@/lib/api/types'
import { ManualSessionsSection } from './ManualSessionsSection'

const A = 'mbs_01JA0000000000000000000000'
const B = 'mbs_01JB0000000000000000000000'
const OLD = 'mbs_01J90000000000000000000000'
const SLOW = { timeout: 5000 }

let pages: Record<string, ManualSession[]>
let details: Record<string, ManualSession>
let calls: string[]

function session(id: string, over: Partial<ManualSession> = {}): ManualSession {
  return {
    sessionId: id, botHandle: 'gem', operatorHandle: 'demo', status: 'EXECUTED', aiModel: 'Gemini 3', marketSession: 'REGULAR', createdAt: '2026-10-13T14:05:00Z',
    repliedAt: '2026-10-13T14:07:00Z', executedAt: '2026-10-13T14:08:00Z', promptSha256: 'ab', promptChars: 100, replyChars: 50,
    untrusted: { summary: `Summary of ${id}`, prompt: null, reply: null },
    issues: [],
    actions: [
      {
        index: 1, line: 12, action: 'BUY', symbol: 'AAPL', type: 'MARKET', qty: '1', notional: null, limitPrice: null, stopPrice: null, trailAmount: null, trailPercent: null,
        timeInForce: 'DAY', extendedHours: false, acknowledge: [], orderId: null, rationale: 'r', executable: true, issues: [], estimate: null,
        result: { status: 'PLACED', orderId: `ord_${id.slice(-4)}`, orderStatus: 'FILLED', code: null, message: null, at: '2026-10-13T14:08:00Z' },
      },
    ],
    journalNoteId: 'jnl_1',
    ...over,
  }
}
const detail = (s: ManualSession): ManualSession => ({ ...s, untrusted: { summary: s.untrusted?.summary ?? null, prompt: `PROMPT of ${s.sessionId}`, reply: `REPLY of ${s.sessionId}` } })

beforeEach(() => {
  pages = {}
  details = {}
  calls = []
  vi.stubGlobal('fetch', async (req: Request) => {
    const url = new URL(req.url)
    const path = url.pathname.replace(/^\/api\/v1/, '')
    calls.push(path)
    const listMatch = /^\/players\/([^/]+)\/manual-sessions$/.exec(path)
    const one = /^\/players\/[^/]+\/manual-sessions\/([^/]+)$/.exec(path)
    const body = listMatch ? { items: pages[listMatch[1]!] ?? [], nextCursor: null } : one && details[one[1]!] ? details[one[1]!] : null
    return body
      ? new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
      : new Response(JSON.stringify({ status: 404, code: 'NOT_FOUND', title: 'Not found' }), { status: 404, headers: { 'Content-Type': 'application/problem+json' } })
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** Renders the section at `url` (the History page's search string), so `?session=` reaches it. */
function renderAt(url: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const rootRoute = createRootRoute({ component: () => <ManualSessionsSection handle="gem" /> })
  const router = createRouter({ routeTree: rootRoute, history: createMemoryHistory({ initialEntries: [url] }) })
  return render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>,
  )
}

describe('ManualSessionsSection (§20.8)', { timeout: 20_000 }, () => {
  it('renders nothing for a bot never driven by hand', async () => {
    pages.gem = []
    const { container } = renderAt('/')
    await waitFor(() => expect(calls).toContain('/players/gem/manual-sessions'))
    await new Promise((r) => setTimeout(r, 50))
    expect(container.querySelector('section')).toBeNull()
  })

  it('lists sessions with their orders and results, and fetches the prompt only when opened', async () => {
    pages.gem = [session(B), session(A, { status: 'REPLIED', executedAt: null, journalNoteId: null })]
    details[B] = detail(session(B))
    renderAt('/')
    const section = await screen.findByRole('region', { name: 'Manual sessions' }, SLOW)
    expect(within(section).getByRole('heading', { name: 'Manual sessions' })).toBeTruthy()
    const card = within(section).getByRole('listitem', { name: `Manual session ${B}` })
    expect(card.textContent).toContain('· Gemini 3 · pasted by @demo · Executed')
    expect(within(card).getByText(`Summary of ${B}`)).toBeTruthy()
    expect(within(card).getByText('PLACED · Filled')).toBeTruthy()
    expect(within(card).getByText('ord_0000')).toBeTruthy()
    expect(card.id).toBe(B)
    expect(calls.some((c) => c.endsWith(`/manual-sessions/${B}`))).toBe(false)
    await userEvent.click(within(card).getByText('Prompt'))
    expect(await within(card).findByText(`PROMPT of ${B}`)).toBeTruthy()
    expect(calls.some((c) => c.endsWith(`/manual-sessions/${B}`))).toBe(true)
  })

  it('puts the linked session (?session=) first, expanded and highlighted, even when it is not on the first page', async () => {
    pages.gem = [session(B), session(A)]
    details[OLD] = detail(session(OLD, { untrusted: { summary: 'An old session', prompt: null, reply: null } }))
    renderAt(`/?session=${OLD}`)
    const section = await screen.findByRole('region', { name: 'Manual sessions' }, SLOW)
    await waitFor(() => expect(within(section).getAllByRole('listitem').length).toBe(3))
    const cards = within(section).getAllByRole('listitem')
    expect(cards[0]!.getAttribute('aria-label')).toBe(`Manual session ${OLD}`)
    expect(cards[0]!.getAttribute('aria-current')).toBe('true')
    expect(await within(cards[0]!).findByText(`REPLY of ${OLD}`)).toBeTruthy()
    expect(cards[1]!.getAttribute('aria-current')).toBeNull()
  })

  it('renders the pasted reply as plain text', async () => {
    const evil = '<img src=x onerror="alert(1)"> [link](https://evil)'
    pages.gem = [session(A)]
    details[A] = { ...detail(session(A)), untrusted: { summary: evil, prompt: 'p', reply: evil } }
    const { container } = renderAt(`/?session=${A}`)
    expect((await screen.findAllByText(/onerror/, {}, SLOW)).length).toBeGreaterThan(0)
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('a[href="https://evil"]')).toBeNull()
  })
})
