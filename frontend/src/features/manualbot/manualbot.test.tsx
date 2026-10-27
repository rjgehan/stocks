import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { parseManualReply } from '@/lib/api/mock/manualBots'
import { MockServer } from '@/lib/api/mock/server'
import type { ManualAction, ManualBot, ManualSession } from '@/lib/api/types'
import { renderWithRouter } from '@/test/router'
import { ManualConsole } from './ManualConsole'
import { PromptsScreen, pickUp } from './PromptsScreen'
import { issueLine, placeButton, resultLabel } from './manualView'

const SID = 'mbs_01JA0000000000000000000000'
const SLOW = { timeout: 5000 }

// ------------------------------------------------------------------------------------------- a scripted backend

type Route = (body: unknown) => { status?: number; body: unknown } | Promise<{ status?: number; body: unknown }>
let routes: Record<string, Route>
let calls: Array<{ method: string; path: string; body: unknown }>

beforeEach(() => {
  routes = {}
  calls = []
  document.cookie = 'XSRF-TOKEN=test-token; path=/'
  vi.stubGlobal('fetch', async (req: Request) => {
    const path = new URL(req.url).pathname.replace(/^\/api\/v1/, '')
    const body = req.method === 'GET' ? null : await req.json().catch(() => null)
    calls.push({ method: req.method, path, body })
    const route = routes[`${req.method} ${path}`]
    const out = route ? await route(body) : { status: 404, body: { status: 404, code: 'NOT_FOUND', title: 'Not found', detail: `${req.method} ${path}` } }
    const status = out.status ?? 200
    return new Response(JSON.stringify(out.body), { status, headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' } })
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function bot(over: Partial<ManualBot> = {}): ManualBot {
  return {
    bot: { playerId: 'ply_1', handle: 'gem', displayName: 'Gem', kind: 'AI', aiModel: 'Gemini 3', avatarSeed: 'gem', memberSince: '2026-10-01T13:00:00Z', resets: 0 },
    ownerHandle: 'demo',
    status: 'ACTIVE',
    instructions: 'Buy quality on dips.',
    instructionsUpdatedAt: '2026-10-13T14:00:00Z',
    instructionsUpdatedBy: 'demo',
    activeApiKeys: 0,
    activeConnectors: 0,
    sessionsToday: 1,
    sessionsPerDay: 50,
    limits: { maxInstructionsChars: 4000, maxReplyChars: 20000, maxOrders: 10, expireAfter: 'PT24H' },
    ...over,
  }
}

function row(index: number, over: Partial<ManualAction> = {}): ManualAction {
  return {
    index, line: 10 + index, action: 'BUY', symbol: 'AAPL', type: 'MARKET', qty: '1', notional: null, limitPrice: null, stopPrice: null, trailAmount: null,
    trailPercent: null, timeInForce: 'DAY', extendedHours: false, acknowledge: [], orderId: null, rationale: 'Strong services growth.', executable: true,
    issues: [], estimate: { price: '227.40', qty: '1', principal: '227.40', fees: { sec: '0.00', taf: '0.00', cat: '0.00', total: '0.00' }, hold: '229.67', quoteAsOf: null, quoteQuality: 'SIMULATED' },
    result: null, ...over,
  }
}

function session(over: Partial<ManualSession> = {}): ManualSession {
  return {
    sessionId: SID, botHandle: 'gem', operatorHandle: 'demo', status: 'PROMPTED', aiModel: 'Gemini 3', marketSession: 'REGULAR', createdAt: '2026-10-13T14:05:00Z',
    repliedAt: null, executedAt: null, promptSha256: 'ab', promptChars: 120, replyChars: null,
    untrusted: { summary: null, prompt: 'I am conducting a test of how well chat bots can trade on the stock market.', reply: null },
    issues: [], actions: [], journalNoteId: null, ...over,
  }
}

function serve(m: ManualBot, s?: ManualSession) {
  routes['GET /me/bots/gem/manual'] = () => ({ body: m })
  routes['GET /players/gem/manual-sessions'] = () => ({ body: { items: s ? [{ ...s, untrusted: { ...s.untrusted, prompt: null, reply: null } }] : [], nextCursor: null } })
  if (s) routes[`GET /players/gem/manual-sessions/${SID}`] = () => ({ body: s })
}

const open = (sessionId?: string) => renderWithRouter(<ManualConsole handle="gem" sessionId={sessionId} onSession={() => {}} />)

// ------------------------------------------------------------------------------------------- the console

describe('the manual console (§20.8)', { timeout: 20_000 }, () => {
  it('shows a loading state, then the bot with its settings', async () => {
    const gate: { open?: () => void } = {}
    const opened = new Promise<void>((r) => (gate.open = r))
    routes['GET /me/bots/gem/manual'] = async () => {
      await opened
      return { body: bot() }
    }
    routes['GET /players/gem/manual-sessions'] = () => ({ body: { items: [], nextCursor: null } })
    open()
    expect(await screen.findByLabelText('Loading the console', {}, SLOW)).toBeTruthy()
    gate.open?.()
    expect(await screen.findByRole('heading', { name: 'Gem' }, SLOW)).toBeTruthy()
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('Gem')
    expect((screen.getByLabelText('Instructions (public, part of every prompt)') as HTMLTextAreaElement).value).toBe('Buy quality on dips.')
    expect(screen.getByText('20 / 4,000')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByRole('button', { name: 'New prompt' })).toBeTruthy()
    expect(await screen.findByText('No prompts yet.')).toBeTruthy()
  })

  it('saves only what changed', async () => {
    serve(bot())
    routes['PUT /me/bots/gem/manual'] = (body) => ({ body: bot({ instructions: (body as { instructions: string }).instructions }) })
    open()
    const text = await screen.findByLabelText('Instructions (public, part of every prompt)', {}, SLOW)
    await userEvent.clear(text)
    await userEvent.type(text, 'Only ETFs.')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ instructions: 'Only ETFs.' })
  })

  it('explains who may operate the bot when the server refuses', async () => {
    routes['GET /me/bots/gem/manual'] = () => ({ status: 403, body: { status: 403, code: 'FORBIDDEN', title: 'Forbidden', detail: 'Only @alice (or the admin) can operate @gem.' } })
    open()
    expect(await screen.findByText('Only @alice (or the admin) can operate @gem.', {}, SLOW)).toBeTruthy()
    expect(screen.getByRole('link', { name: "Open @gem's profile" })).toBeTruthy()
  })

  it('names an unknown bot', async () => {
    routes['GET /me/bots/gem/manual'] = () => ({ status: 404, body: { status: 404, code: 'PLAYER_NOT_FOUND', title: 'Player not found', detail: 'No player @gem.' } })
    open()
    expect(await screen.findByText('No bot @gem', {}, SLOW)).toBeTruthy()
  })

  it('disables every write for a frozen bot', async () => {
    serve(bot({ status: 'FROZEN' }), session())
    open(SID)
    expect(await screen.findByText(/@gem is frozen: read only/, {}, SLOW)).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: 'New prompt' }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByLabelText('Instructions (public, part of every prompt)') as HTMLTextAreaElement).disabled).toBe(true)
  })

  it('marks a bot that also trades with a key or connector', async () => {
    serve(bot({ activeConnectors: 1 }))
    open()
    expect(await screen.findByTitle('Also trades with an API key or connector', {}, SLOW)).toBeTruthy()
  })

  it('renames the bot', async () => {
    serve(bot())
    routes['PUT /me/bots/gem/manual'] = () => ({ body: bot() })
    open()
    const name = await screen.findByLabelText('Name', {}, SLOW)
    await userEvent.clear(name)
    await userEvent.type(name, 'Gem Two')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ displayName: 'Gem Two' })
  })

  it('copies the prompt to the clipboard', async () => {
    const writeText = vi.fn<(text: string) => Promise<void>>(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    serve(bot(), session())
    open(SID)
    fireEvent.click(await screen.findByRole('button', { name: 'Copy prompt' }, SLOW))
    expect(writeText).toHaveBeenCalledWith('I am conducting a test of how well chat bots can trade on the stock market.')
    expect(await screen.findByRole('button', { name: 'Copied ✓' })).toBeTruthy()
  })

  it('selects the prompt when the clipboard is unavailable', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
    serve(bot(), session())
    open(SID)
    fireEvent.click(await screen.findByRole('button', { name: 'Copy prompt' }, SLOW))
    expect(await screen.findByText('Press Ctrl/⌘+C')).toBeTruthy()
  })

  it('checks a reply and lists its issues with line numbers, errors first', async () => {
    serve(bot(), session())
    routes[`POST /me/bots/gem/manual-sessions/${SID}/reply`] = () => ({
      body: session({
        status: 'REPLIED',
        repliedAt: '2026-10-13T14:07:00Z',
        untrusted: { summary: null, prompt: 'p', reply: 'r' },
        issues: [
          { severity: 'WARNING', code: 'SUMMARY_MISSING', field: 'summary', line: null, column: null, message: 'No summary: no journal note will be posted.', hint: null },
          { severity: 'ERROR', code: 'JSON_SYNTAX', field: null, line: 14, column: 7, message: "Line 14, column 7: Unexpected character ('}')", hint: null },
        ],
      }),
    })
    open(SID)
    const reply = await screen.findByLabelText("Paste the AI's reply", {}, SLOW)
    expect((screen.getByRole('button', { name: 'Check reply' }) as HTMLButtonElement).disabled).toBe(true)
    await userEvent.click(reply)
    await userEvent.paste('Here you go:\n```json\n{"orders": [}\n```')
    await userEvent.click(screen.getByRole('button', { name: 'Check reply' }))
    const list = await screen.findByRole('list', { name: 'Reply check' })
    const items = within(list).getAllByRole('listitem')
    expect(items[0]!.textContent).toContain("Line 14, col 7 · JSON_SYNTAX · Line 14, column 7: Unexpected character ('}')")
    expect(items[1]!.textContent).toContain('SUMMARY_MISSING')
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ reply: 'Here you go:\n```json\n{"orders": [}\n```' })
    expect(screen.getByText('Fix the reply (or ask the AI again) and check it again.')).toBeTruthy()
    expect(screen.queryByRole('table', { name: 'Orders in the reply' })).toBeNull()
  })

  it('places only the ticked rows, then shows the results and locks the reply', async () => {
    const replied = session({
      status: 'REPLIED',
      repliedAt: '2026-10-13T14:07:00Z',
      untrusted: { summary: 'Bought one AAPL.', prompt: 'p', reply: 'the reply' },
      actions: [row(1), row(2, { type: 'LIMIT', limitPrice: '220.00', issues: [{ severity: 'WARNING', code: 'FAT_FINGER', field: null, line: 12, column: null, message: 'More than 5% away.', hint: null }] })],
    })
    serve(bot(), replied)
    routes[`POST /me/bots/gem/manual-sessions/${SID}/execute`] = () => ({
      body: {
        ...replied,
        status: 'EXECUTED',
        executedAt: '2026-10-13T14:08:00Z',
        journalNoteId: 'jnl_1',
        actions: [row(1, { result: { status: 'PLACED', orderId: 'ord_1', orderStatus: 'WORKING', code: null, message: null, at: '2026-10-13T14:08:00Z' } }), replied.actions![1]!],
      },
    })
    open(SID)
    const table = await screen.findByRole('table', { name: 'Orders in the reply' }, SLOW)
    expect(within(table).getAllByText('OK')).toHaveLength(1)
    expect(within(table).getByText('FAT_FINGER')).toBeTruthy()
    expect(screen.getByText('Bought one AAPL.')).toBeTruthy()
    expect(screen.getByText('Goes to the public journal')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Place 2 orders as @gem' })).toBeTruthy()
    await userEvent.click(screen.getByRole('checkbox', { name: 'Place row 2' }))
    await userEvent.click(screen.getByRole('button', { name: 'Place 1 order as @gem' }))
    await waitFor(() => expect(calls.some((c) => c.path.endsWith('/execute'))).toBe(true))
    expect(calls.find((c) => c.path.endsWith('/execute'))!.body).toEqual({ actions: [1] })
    expect(await screen.findByText('PLACED · Working')).toBeTruthy()
    expect(screen.getByText('Summary posted to the journal')).toBeTruthy()
    expect((screen.getByLabelText("Paste the AI's reply") as HTMLTextAreaElement).readOnly).toBe(true)
    expect((screen.getByRole('checkbox', { name: 'Place row 1' }) as HTMLInputElement).disabled).toBe(true)
    expect((screen.getByRole('checkbox', { name: 'Place row 2' }) as HTMLInputElement).disabled).toBe(false)
    await userEvent.click(screen.getByRole('checkbox', { name: 'Place row 2' }))
    expect(screen.getByRole('button', { name: 'Place remaining' })).toBeTruthy()
  })

  it('renders every result, CANCEL REQUESTED and FAILED included, and a skipped journal note', async () => {
    const at = '2026-10-13T14:08:00Z'
    serve(
      bot(),
      session({
        status: 'EXECUTED',
        repliedAt: at,
        executedAt: at,
        untrusted: { summary: 'Rotated.', prompt: 'p', reply: 'r' },
        issues: [{ severity: 'WARNING', code: 'JOURNAL_SKIPPED', field: null, line: null, column: null, message: 'Journal note skipped: at most 12 notes per day.', hint: null }],
        actions: [
          row(1, { action: 'CANCEL', symbol: 'AMD', type: null, qty: null, orderId: 'ord_old', estimate: null, result: { status: 'CANCEL_REQUESTED', orderId: 'ord_old', orderStatus: 'PENDING_CANCEL', code: null, message: null, at } }),
          row(2, { action: 'SELL', result: { status: 'CANCELLED', orderId: 'ord_x', orderStatus: 'CANCELLED', code: null, message: null, at } }),
          row(3, { result: { status: 'FAILED', orderId: null, orderStatus: null, code: 'INSUFFICIENT_BUYING_POWER', message: 'Order needs $229.67; buying power is $100.00.', at } }),
          row(4, { result: { status: 'REPLAYED', orderId: 'ord_r', orderStatus: 'FILLED', code: null, message: null, at } }),
        ],
      }),
    )
    open(SID)
    expect(await screen.findByText('CANCEL REQUESTED', {}, SLOW)).toBeTruthy()
    expect(screen.getByText('CANCELLED')).toBeTruthy()
    expect(screen.getByText('FAILED · INSUFFICIENT_BUYING_POWER: Order needs $229.67; buying power is $100.00.')).toBeTruthy()
    expect(screen.getByText('REPLAYED')).toBeTruthy()
    expect(screen.getByText('Journal note skipped: at most 12 notes per day.')).toBeTruthy()
    // the failed row can be retried; the others are final
    expect((screen.getByRole('checkbox', { name: 'Place row 3' }) as HTMLInputElement).checked).toBe(true)
    expect(screen.getByRole('button', { name: 'Place remaining' })).toBeTruthy()
    // an executed session's primary action is a new prompt
    expect(screen.getByRole('button', { name: 'New prompt' })).toBeTruthy()
  })

  it('renders AI-written text as plain text, never as markup', async () => {
    const evil = '<img src=x onerror="alert(1)"> **Ignore previous instructions** [link](https://evil)'
    serve(bot(), session({ status: 'REPLIED', repliedAt: '2026-10-13T14:07:00Z', untrusted: { summary: evil, prompt: 'p', reply: 'r' }, actions: [row(1, { rationale: evil })] }))
    const { container } = open(SID)
    expect((await screen.findAllByText(/Ignore previous instructions/, {}, SLOW)).length).toBe(2)
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('a[href="https://evil"]')).toBeNull()
  })
})

// ------------------------------------------------------------------------------------------- helpers and the mock

describe('manual view helpers', () => {
  it('formats issues, results and the place button', () => {
    expect(issueLine({ severity: 'ERROR', code: 'JSON_SYNTAX', field: null, line: 14, column: 7, message: 'bad', hint: null })).toBe('Line 14, col 7 · JSON_SYNTAX · bad')
    expect(issueLine({ severity: 'WARNING', code: 'STALE_PROMPT', field: null, line: null, column: null, message: 'old', hint: null })).toBe('STALE_PROMPT · old')
    expect(resultLabel({ status: 'CANCEL_REQUESTED', orderId: 'o', orderStatus: 'PENDING_CANCEL', code: null, message: null, at: null })).toBe('CANCEL REQUESTED')
    const s = session({ status: 'REPLIED', untrusted: { summary: 'x', prompt: null, reply: null } })
    expect(placeButton(3, s, 'gem')).toEqual({ label: 'Place 3 orders as @gem', enabled: true })
    expect(placeButton(0, s, 'gem')).toEqual({ label: 'Post summary only', enabled: true })
    expect(placeButton(0, { ...s, journalNoteId: 'jnl_1' }, 'gem').enabled).toBe(false)
    expect(placeButton(1, { ...s, executedAt: '2026-10-13T14:08:00Z' }, 'gem').label).toBe('Place remaining')
  })
})

describe('mock mode (§22.9)', () => {
  it('parses a fenced reply with aliases, comments and trailing commas, with row lines', () => {
    const reply = [
      "Here's my plan: NVDA's run looks extended.",
      '',
      '```json',
      '{',
      `  "session": "${SID}",`,
      '  "summary": "Trimmed NVDA, added MSFT.",',
      '  "orders": [',
      '    {"action": "SELL", "symbol": "NVDA", "type": "MKT", "quantity": "2", "rationale": "Locking in gains."}, // trim',
      '    {"side": "buy", "ticker": "$msft", "type": "LMT", "qty": 1, "price": "$412.50", "tif": "GTC", "rationale": "Pullback."},',
      '    {"action": "BUY", "symbol": "VTI", "notional": "150"},',
      '  ],',
      '}',
      '```',
    ].join('\n')
    const p = parseManualReply(reply, SID)
    expect(p.issues).toEqual([])
    expect(p.summary).toBe('Trimmed NVDA, added MSFT.')
    expect(p.actions.map((a) => [a.action, a.symbol, a.type, a.qty ?? a.notional, a.limitPrice, a.timeInForce, a.line, a.executable])).toEqual([
      ['SELL', 'NVDA', 'MARKET', '2', null, 'DAY', 8, true],
      ['BUY', 'MSFT', 'LIMIT', '1', '412.50', 'GTC', 9, true],
      ['BUY', 'VTI', 'MARKET', '150', null, 'DAY', 10, false],
    ])
    expect(p.actions[2]!.issues!.map((i) => i.code)).toEqual(['TYPE_ASSUMED', 'RATIONALE_REQUIRED'])
  })

  it('reports a syntax error with its line, and a reply for another session', () => {
    const broken = parseManualReply('one\ntwo\n```json\n{\n  "orders": [\n    {"action": "BUY",, }\n  ]\n}\n```', SID)
    expect(broken.issues[0]!.code).toBe('JSON_SYNTAX')
    expect(broken.issues[0]!.line).toBe(6)
    const other = parseManualReply('```json\n{"session": "mbs_01JB0000000000000000000000", "summary": "s", "orders": []}\n```', SID)
    expect(other.issues.map((i) => [i.severity, i.code])).toEqual([['ERROR', 'SESSION_MISMATCH']])
  })

  it('round-trips a session: prompt, reply, execute, public list and detail', async () => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
    const server = new MockServer()
    await server.handle(new Request('http://localhost/api/v1/auth/csrf'))
    const csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? ''
    const call = async (method: string, path: string, body?: unknown) => {
      const res = await server.handle(new Request(`http://localhost/api/v1${path}`, { method, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf }, body: body === undefined ? undefined : JSON.stringify(body) }))
      return { status: res.status, body: await res.json() }
    }
    expect((await call('GET', '/me/bots/demo/manual')).status).toBe(403)
    expect((await call('PUT', '/me/bots/claude/manual', { instructions: 'Use key stk_abcdefgh_0123456789abcdef please', aiModel: 'Claude 5' })).body.instructions).toBe('Use key [redacted] please')
    const made = await call('POST', '/me/bots/claude/manual-sessions')
    expect(made.status).toBe(201)
    const id = made.body.sessionId as string
    expect(made.body.untrusted.prompt).toContain('I am conducting a test of how well chat bots can trade')
    expect(made.body.untrusted.prompt).toContain(id)
    const reply = `ok\n\`\`\`json\n{"session": "${id}", "summary": "One share.", "orders": [{"action": "BUY", "symbol": "AAPL", "type": "MARKET", "qty": "1", "rationale": "Test."}]}\n\`\`\``
    const checked = await call('POST', `/me/bots/claude/manual-sessions/${id}/reply`, { reply })
    expect(checked.body.status).toBe('REPLIED')
    expect(checked.body.actions[0].executable).toBe(true)
    const placed = await call('POST', `/me/bots/claude/manual-sessions/${id}/execute`, { actions: [1] })
    expect(placed.body.actions[0].result.status).toBe('PLACED')
    expect(placed.body.journalNoteId).toMatch(/^jnl_/)
    expect((await call('POST', `/me/bots/claude/manual-sessions/${id}/reply`, { reply })).status).toBe(400)
    const list = await call('GET', '/players/claude/manual-sessions')
    expect(list.body.items[0].sessionId).toBe(id)
    expect(list.body.items[0].untrusted.prompt).toBeNull()
    expect((await call('GET', `/players/claude/manual-sessions/${id}`)).body.untrusted.reply).toContain('One share.')
    expect((await call('GET', `/players/chatgpt/manual-sessions/${id}`)).status).toBe(404)
  })
})

// ------------------------------------------------------------------------------------------- the prompts screen

describe('pickUp: what a bot\'s latest session means when the prompts screen opens', () => {
  const now = Date.parse('2026-10-13T15:00:00Z')
  it('is done when the latest session was placed today (ET)', () => {
    expect(pickUp(session({ status: 'EXECUTED', executedAt: '2026-10-13T14:30:00Z' }), now)).toEqual({ done: true, reuse: null })
    expect(pickUp(session({ status: 'EXECUTED', executedAt: '2026-10-12T19:00:00Z' }), now)).toEqual({ done: false, reuse: null })
  })
  it('keeps working on a recent prompt, never a stale one', () => {
    expect(pickUp(session({ createdAt: '2026-10-13T14:45:00Z' }), now)).toEqual({ done: false, reuse: SID })
    expect(pickUp(session({ status: 'REPLIED', createdAt: '2026-10-13T14:45:00Z' }), now)).toEqual({ done: false, reuse: SID })
    expect(pickUp(session({ createdAt: '2026-10-13T13:00:00Z' }), now)).toEqual({ done: false, reuse: null })
    expect(pickUp(undefined, now)).toEqual({ done: false, reuse: null })
  })
})

describe('the prompts screen', { timeout: 20_000 }, () => {
  const api = bot({ bot: { ...bot().bot!, handle: 'claude', displayName: 'Claude', playerId: 'ply_2' }, activeApiKeys: 1 })

  it('lists the manual bots, the API ones on request', async () => {
    routes['GET /me/bots'] = () => ({ body: [api, bot()] })
    routes['GET /players/gem/manual-sessions'] = () => ({ body: { items: [], nextCursor: null } })
    routes['GET /players/claude/manual-sessions'] = () => ({ body: { items: [], nextCursor: null } })
    renderWithRouter(<PromptsScreen />)
    expect(await screen.findByRole('listitem', { name: '@gem' }, SLOW)).toBeTruthy()
    expect(screen.queryByRole('listitem', { name: '@claude' })).toBeNull()
    expect(screen.getByText('0 of 1 done')).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Show 1 API bot' }))
    expect(screen.getByRole('listitem', { name: '@claude' })).toBeTruthy()
  })

  it('copies a fresh prompt, reads the pasted trades, executes them and marks the bot done', async () => {
    const writeText = vi.fn<(text: string) => Promise<void>>(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const prompted = session({ createdAt: new Date().toISOString() })
    const replied = session({ ...prompted, status: 'REPLIED', repliedAt: new Date().toISOString(), actions: [row(1), row(2, { executable: false, issues: [{ severity: 'ERROR', code: 'FIELD_INVALID', message: 'bad qty', hint: null, line: 12, column: null, field: 'qty' }] })] })
    const executed = session({ ...replied, status: 'EXECUTED', executedAt: new Date().toISOString(), actions: [row(1, { result: { status: 'PLACED', orderId: 'ord_1', orderStatus: 'FILLED', code: null, message: null, at: null } }), replied.actions![1]!] })
    routes['GET /me/bots'] = () => ({ body: [bot()] })
    routes['GET /players/gem/manual-sessions'] = () => ({ body: { items: [], nextCursor: null } })
    routes['POST /me/bots/gem/manual-sessions'] = () => ({ status: 201, body: prompted })
    routes[`GET /players/gem/manual-sessions/${SID}`] = () => ({ body: prompted })
    routes[`POST /me/bots/gem/manual-sessions/${SID}/reply`] = () => ({ body: replied })
    routes[`POST /me/bots/gem/manual-sessions/${SID}/execute`] = () => ({ body: executed })
    renderWithRouter(<PromptsScreen />)

    await userEvent.click(await screen.findByRole('button', { name: 'Copy prompt' }, SLOW))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(prompted.untrusted!.prompt))
    const reply = await screen.findByLabelText('Reply for @gem', {}, SLOW)
    fireEvent.change(reply, { target: { value: 'my reply ```json {} ```' } })
    const trades = await screen.findByRole('list', { name: 'Trades for @gem' }, SLOW)
    expect(within(trades).getAllByRole('listitem')).toHaveLength(2)
    expect(within(trades).getByText(/bad qty/)).toBeTruthy()
    expect(calls.find((c) => c.path.endsWith('/reply'))!.body).toEqual({ reply: 'my reply ```json {} ```' })

    await userEvent.click(screen.getByRole('button', { name: 'Execute 1 trade' }))
    expect(await screen.findByLabelText('Done', {}, SLOW)).toBeTruthy()
    expect(calls.find((c) => c.path.endsWith('/execute'))!.body).toEqual({ actions: [1] })
    expect(screen.getByText('1 of 1 done')).toBeTruthy()
  })
})

describe('all bots at once', { timeout: 30_000 }, () => {
  const SID2 = 'mbs_01JB0000000000000000000000'
  const nova = bot({ bot: { ...bot().bot!, handle: 'nova', displayName: 'Nova', playerId: 'ply_3' } })

  it('copies every prompt as one message, splits one answer back to each bot and executes them all', async () => {
    const writeText = vi.fn<(text: string) => Promise<void>>(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const now = new Date().toISOString()
    const s1 = session({ createdAt: now, untrusted: { summary: null, prompt: 'PROMPT FOR GEM', reply: null } })
    const s2 = session({ sessionId: SID2, botHandle: 'nova', createdAt: now, untrusted: { summary: null, prompt: 'PROMPT FOR NOVA', reply: null } })
    const replied = (s: typeof s1) => session({ ...s, status: 'REPLIED', repliedAt: now, actions: [row(1)] })
    const executed = (s: typeof s1) => session({ ...s, status: 'EXECUTED', executedAt: now, actions: [row(1, { result: { status: 'PLACED', orderId: 'ord_1', orderStatus: 'FILLED', code: null, message: null, at: null } })] })
    routes['GET /me/bots'] = () => ({ body: [bot(), nova] })
    for (const [h, s] of [['gem', s1], ['nova', s2]] as const) {
      routes[`GET /players/${h}/manual-sessions`] = () => ({ body: { items: [], nextCursor: null } })
      routes[`POST /me/bots/${h}/manual-sessions`] = () => ({ status: 201, body: s })
      routes[`GET /players/${h}/manual-sessions/${s.sessionId}`] = () => ({ body: s })
      routes[`POST /me/bots/${h}/manual-sessions/${s.sessionId}/reply`] = () => ({ body: replied(s) })
      routes[`POST /me/bots/${h}/manual-sessions/${s.sessionId}/execute`] = () => ({ body: executed(s) })
    }
    renderWithRouter(<PromptsScreen />)

    await userEvent.click(await screen.findByRole('button', { name: 'Copy all 2 prompts' }, SLOW))
    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1))
    const copied = writeText.mock.calls[0]![0]
    expect(copied).toContain('This message holds 2 separate prompts')
    expect(copied).toContain(`@gem (Gem), session ${SID}`)
    expect(copied).toContain('PROMPT FOR NOVA')

    const answer = `Gem buys.\n\`\`\`json\n{"session": "${SID}", "orders": [{"action": "BUY"}]}\n\`\`\`\nNova buys.\n\`\`\`json\n{"session": "${SID2}", "orders": [{"action": "BUY"}]}\n\`\`\``
    fireEvent.change(screen.getByLabelText("The AI's whole answer"), { target: { value: answer } })
    expect(await screen.findByText('Found replies for 2 of 2 bots.', {}, SLOW)).toBeTruthy()
    await waitFor(() => expect(calls.filter((c) => c.path.endsWith('/reply'))).toHaveLength(2), SLOW)
    const gemReply = calls.find((c) => c.path === `/me/bots/gem/manual-sessions/${SID}/reply`)!.body as { reply: string }
    expect(gemReply.reply).toContain(SID)
    expect(gemReply.reply).not.toContain(SID2)   // each bot gets only its own block

    const executeAll = await screen.findByRole('button', { name: 'Execute all ready (2)' }, SLOW)
    await userEvent.click(executeAll)
    await waitFor(() => expect(calls.filter((c) => c.path.endsWith('/execute'))).toHaveLength(2), SLOW)
    expect(await screen.findByText('2 of 2 done', {}, SLOW)).toBeTruthy()
  })
})
