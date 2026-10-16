/**
 * Mock mode (§22.9) for manual bots (§20.8): the owner's console (`/me/bots/{handle}/…`) and the public manual
 * sessions. Instructions and sessions live in the mock state (`manualBots`), so they survive a reload. The prompt is
 * a shortened template (the framing sentence, the session id and the reply format), the reply parser a small
 * approximation of `ManualReplyParser` (the first fenced block with an orders list, else the first `{…}`; `//`
 * comments and trailing commas removed; the common key aliases), and rows are previewed with the mock order preview.
 */
import type { Clock, ManualAction, ManualBot, ManualIssue, ManualSession, PlaceOrderRequest, PlayerSummary, Preview } from '../types'

type Handler = (ctx: { url: URL; params: string[]; body: unknown }) => Response | Promise<Response>
export type ManualRouteFn = (method: string, pattern: string, handler: Handler) => void

/** One bot's console state in the mock (`MockState.manualBots[handle]`); sessions oldest first, in detail shape. */
export interface MockManualBot {
  instructions: string
  updatedAt: string | null
  updatedBy: string | null
  sessions: ManualSession[]
}

export interface ManualMockDeps {
  me: () => { handle: string; isAdmin: boolean; active: boolean }
  /** Any player, DISABLED ones included; null when unknown. */
  player: (handle: string) => { summary: PlayerSummary; ownerHandle: string | null; status: 'ACTIVE' | 'FROZEN' | 'DISABLED' } | null
  setAiModel: (handle: string, label: string) => void
  setDisplayName: (handle: string, name: string) => void
  /** The handles of the AI bots I own, any status. */
  owned: () => string[]
  activeKeys: (handle: string) => number
  clock: () => Clock
  preview: (req: PlaceOrderRequest) => Preview
  store: () => Record<string, MockManualBot>
  /** A row placed as the bot joins its public orders (a market row fills at the mark); the order's status. */
  placed: (handle: string, row: ManualAction, sessionId: string, orderId: string, at: string) => 'FILLED' | 'WORKING'
  /** A CANCEL row against one of the bot's public orders. */
  cancelled: (handle: string, orderId: string, at: string) => void
}

const LIMITS = { maxInstructionsChars: 4000, maxReplyChars: 20_000, maxOrders: 10, expireAfter: 'PT24H' }
const SESSIONS_PER_DAY = 50
const KEY_SHAPED_INLINE = /stk_[A-Za-z0-9]{6,}_[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9_-]{12,}|[Bb][Ee][Aa][Rr][Ee][Rr][ \t]+[A-Za-z0-9._~+/-]{16,}=*/g
const SYMBOL = /^[A-Z][A-Z0-9.-]{0,11}$/
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
function problem(status: number, code: string, title: string, detail: string, field?: string, retryAfterS: number | null = null): Response {
  return new Response(
    JSON.stringify({
      type: 'about:blank',
      title,
      status,
      code,
      detail,
      hint: null,
      fields: field ? [{ field, code: 'Invalid', message: detail }] : [],
      retryAfterS,
      requestId: 'req_mock',
    }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}
const invalid = (detail: string, field?: string) => problem(400, 'VALIDATION_FAILED', 'Validation failed', detail, field)

function ulid(): string {
  let s = ''
  for (let i = 0; i < 26; i++) s += CROCKFORD[Math.floor(Math.random() * 32)]
  return s
}
const etDate = (ms: number) => new Date(ms).toLocaleDateString('en-CA', { timeZone: 'America/New_York' })
const etTime = (ms: number) => new Date(ms).toLocaleString('en-CA', { timeZone: 'America/New_York', hour12: false }).replace(',', '').slice(0, 16)

/** A stand-in for the prompt's SHA-256 (the mock never checks it). */
function fakeSha(text: string): string {
  let out = ''
  let h = 2166136261
  for (let round = 0; round < 8; round++) {
    for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619) >>> 0
    h = Math.imul(h ^ round, 16777619) >>> 0
    out += h.toString(16).padStart(8, '0')
  }
  return out
}

// ------------------------------------------------------------------------------------------- the prompt

function buildPrompt(bot: PlayerSummary, owner: string, sessionId: string, instructions: string, clock: Clock, now: number): string {
  return [
    `I am conducting a test of how well chat bots can trade on the stock market. You are the AI trader @${bot.handle} ("${bot.displayName}", ${bot.aiModel ?? 'AI'}) in "stocks", a paper-trading league where friends and AI bots trade the live US stock market with fake money. No real money is involved and no order reaches a real exchange. The league is a public experiment: every player can see your holdings, orders, fills, rationales, this prompt and your reply.`,
    '',
    `You cannot call any tools. Your owner @${owner} will paste your whole reply into the league app, which reads only the JSON block described under "How to reply", checks every order and places them as @${bot.handle}.`,
    '',
    `Session: ${sessionId}`,
    `Prompt made: ${etTime(now)} ET`,
    `Market now: ${clock.session ?? 'CLOSED'} session (mock mode: a shortened prompt without your account, holdings and the full rules)`,
    '',
    `=== Instructions from your owner @${owner} ===`,
    instructions || '(No instructions: trade with your own judgment within the league rules.)',
    '=== End of instructions ===',
    '',
    '=== How to reply (strict) ===',
    'Think it through in prose first if you want. Then put exactly ONE fenced JSON block in your reply, with this shape:',
    '',
    '```json',
    '{',
    '  "format": "stocks.manual.v1",',
    `  "session": "${sessionId}",`,
    '  "summary": "1-3 sentences for the public journal: what you did and why (at most 500 characters).",',
    '  "orders": []',
    '}',
    '```',
    '',
    `"orders" is a list of at most ${LIMITS.maxOrders} actions: {"action": "BUY" or "SELL", "symbol", "type": "MARKET" | "LIMIT" | "STOP" | "STOP_LIMIT" | "TRAILING_STOP", exactly one of "qty" and "notional", "limit_price", "stop_price", "trail_percent" or "trail_amount", "time_in_force": "DAY" | "GTC", "extended_hours", "rationale" (required, public)}, or {"action": "CANCEL", "order_id", "rationale"}.`,
  ].join('\n')
}

// ------------------------------------------------------------------------------------------- the parser (approximation)

interface Block {
  body: string
  line: number
}

function findBlock(text: string): Block | null {
  const lines = text.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const open = /^ {0,3}(`{3,}|~{3,})/.exec(lines[i]!)
    if (!open) continue
    const fence = open[1]!
    let j = i + 1
    while (j < lines.length && !lines[j]!.trim().startsWith(fence)) j++
    const body = lines.slice(i + 1, j).join('\n')
    if (/["']?(orders|actions|trades)["']?\s*:/i.test(body)) return { body, line: i + 2 }
    i = j
  }
  const start = text.indexOf('{')
  const end = text.lastIndexOf('}')
  return start >= 0 && end > start ? { body: text.slice(start, end + 1), line: text.slice(0, start).split('\n').length } : null
}

const norm = (k: string) => k.toLowerCase().replace(/[_\s-]/g, '')
const ALIASES: Record<string, string[]> = {
  action: ['action', 'side'],
  symbol: ['symbol', 'ticker'],
  type: ['type', 'ordertype'],
  qty: ['qty', 'quantity', 'shares'],
  notional: ['notional', 'amount', 'dollars', 'usd'],
  limitPrice: ['limitprice', 'limit', 'lmtprice'],
  stopPrice: ['stopprice', 'stop'],
  trailPercent: ['trailpercent', 'trailpct'],
  trailAmount: ['trailamount'],
  timeInForce: ['timeinforce', 'tif'],
  extendedHours: ['extendedhours', 'extended'],
  rationale: ['rationale', 'reason', 'why'],
  orderId: ['orderid', 'id'],
  acknowledge: ['acknowledge', 'ack'],
}
const TYPES: Record<string, NonNullable<ManualAction['type']>> = {
  MARKET: 'MARKET', MKT: 'MARKET', LIMIT: 'LIMIT', LMT: 'LIMIT', STOP: 'STOP', STP: 'STOP', STOP_LIMIT: 'STOP_LIMIT', STOP_LMT: 'STOP_LIMIT',
  TRAILING_STOP: 'TRAILING_STOP', TRAIL: 'TRAILING_STOP', TRAILING: 'TRAILING_STOP',
}

function pick(o: Record<string, unknown>, name: string): { key: string; value: unknown } | null {
  for (const [k, v] of Object.entries(o)) if (ALIASES[name]!.includes(norm(k)) && v !== null && v !== '') return { key: k, value: v }
  return null
}

function decimal(v: unknown): string | null {
  const s = typeof v === 'number' ? String(v) : typeof v === 'string' ? v.trim().replace(/^\$/, '').replace(/%$/, '').replace(/,/g, '') : ''
  return /^\d+(\.\d+)?$/.test(s) && Number(s) > 0 ? s : null
}

/** A row issue; like the server, its message ends with " (line N)". */
function issue(severity: 'ERROR' | 'WARNING', code: string, message: string, line: number | null, f: string | null = null): ManualIssue {
  return { severity, code, field: f, line, column: null, message: line != null ? `${message} (line ${line})` : message, hint: null }
}

/** The parsed reply: the summary, one row per order (without previews) and the reply-level issues. */
export function parseManualReply(text: string, sessionId: string): { summary: string | null; actions: ManualAction[]; issues: ManualIssue[] } {
  const issues: ManualIssue[] = []
  const clean = text.replace(/^\uFEFF/, '').replace(/[\u200B-\u200D\u2060\uFEFF]/g, '').replace(/\r\n?/g, '\n')
  const block = findBlock(clean)
  if (!block) {
    issues.push({ severity: 'ERROR', code: 'NO_JSON_BLOCK', field: null, line: null, column: null, message: 'Found no JSON block with an "orders" list. The reply must contain one ```json block in the stocks.manual.v1 format.', hint: null })
    return { summary: null, actions: [], issues }
  }
  const body = block.body.replace(/(^|[^:"'\\])\/\/.*$/gm, '$1').replace(/,(\s*[}\]])/g, '$1')
  let parsed: unknown
  try {
    parsed = JSON.parse(body)
  } catch (e) {
    const msg = e instanceof Error ? e.message : 'Invalid JSON'
    const pos = Number(/position (\d+)/.exec(msg)?.[1] ?? NaN)
    const line = Number.isFinite(pos) ? block.line + body.slice(0, pos).split('\n').length - 1 : block.line
    const column = Number.isFinite(pos) ? pos - body.lastIndexOf('\n', pos - 1) : null
    issues.push({ severity: 'ERROR', code: 'JSON_SYNTAX', field: null, line, column, message: `Line ${line}${column ? `, column ${column}` : ''}: ${msg.replace(/ in JSON at position \d+.*$/, '')}`, hint: null })
    return { summary: null, actions: [], issues }
  }
  let envelope: Record<string, unknown>
  if (Array.isArray(parsed)) {
    issues.push({ severity: 'WARNING', code: 'NO_ENVELOPE', field: null, line: block.line, column: null, message: 'The reply is a bare list of orders: no session and no summary.', hint: null })
    envelope = { orders: parsed }
  } else if (parsed && typeof parsed === 'object') {
    envelope = parsed as Record<string, unknown>
  } else {
    issues.push({ severity: 'ERROR', code: 'NO_JSON_BLOCK', field: null, line: block.line, column: null, message: 'The JSON block is not an object with an "orders" list.', hint: null })
    return { summary: null, actions: [], issues }
  }
  const get = (...names: string[]) => Object.entries(envelope).find(([k]) => names.includes(norm(k)))?.[1]
  const session = get('session', 'sessionid')
  if (session == null || session === '') {
    if (!Array.isArray(parsed)) issues.push({ severity: 'WARNING', code: 'SESSION_MISSING', field: 'session', line: block.line, column: null, message: `The reply names no session; it is taken as the answer to ${sessionId}.`, hint: null })
  } else if (String(session) !== sessionId) {
    issues.push(
      String(session).startsWith('mbs_')
        ? { severity: 'ERROR', code: 'SESSION_MISMATCH', field: 'session', line: block.line, column: null, message: `This reply is for session ${String(session)}, not ${sessionId}. Paste the reply to the latest prompt, or make a new prompt.`, hint: null }
        : { severity: 'WARNING', code: 'SESSION_UNEXPECTED', field: 'session', line: block.line, column: null, message: `The reply names session ${String(session)}; it is taken as the answer to ${sessionId}.`, hint: null },
    )
  }
  const summary = get('summary', 'journal', 'journalnote')
  let summaryText: string | null = typeof summary === 'string' && summary.trim() ? summary.replace(/\s+/g, ' ').trim() : null
  if (summaryText && summaryText.length > 500) {
    summaryText = summaryText.slice(0, 500)
    issues.push({ severity: 'WARNING', code: 'SUMMARY_CLIPPED', field: 'summary', line: null, column: null, message: 'The summary was clipped to 500 characters.', hint: null })
  }
  if (!summaryText && !Array.isArray(parsed)) issues.push({ severity: 'WARNING', code: 'SUMMARY_MISSING', field: 'summary', line: null, column: null, message: 'No summary: no journal note will be posted.', hint: null })
  const orders = get('orders', 'actions', 'trades')
  if (!Array.isArray(orders)) {
    issues.push({ severity: 'ERROR', code: 'FIELD_INVALID', field: 'orders', line: block.line, column: null, message: '"orders" must be a list.', hint: null })
    return { summary: summaryText, actions: [], issues }
  }
  const tooMany = orders.length > LIMITS.maxOrders
  if (tooMany) issues.push({ severity: 'ERROR', code: 'TOO_MANY_ORDERS', field: 'orders', line: block.line, column: null, message: `The reply has ${orders.length} orders; at most ${LIMITS.maxOrders}.`, hint: null })
  // each row's line: the lines that open a row, in order (an approximation of the server's streaming pass)
  const bodyLines = block.body.split('\n')
  const rowLines = bodyLines.flatMap((l, i) => (/["']?(action|side)["']?\s*:/i.test(l) ? [block.line + i] : []))
  const actions = orders.slice(0, 15).map((raw, n): ManualAction => {
    const line = rowLines[n] ?? block.line
    const rowIssues: ManualIssue[] = []
    const err = (code: string, message: string, f: string | null = null) => rowIssues.push(issue('ERROR', code, message, line, f))
    const row: ManualAction = {
      index: n + 1, line, action: null, symbol: null, type: null, qty: null, notional: null, limitPrice: null, stopPrice: null, trailAmount: null,
      trailPercent: null, timeInForce: 'DAY', extendedHours: false, acknowledge: [], orderId: null, rationale: null, executable: false, issues: rowIssues,
      estimate: null, result: null,
    }
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
      err('FIELD_INVALID', `order #${n + 1} is not an object`)
      return row
    }
    const o = raw as Record<string, unknown>
    const action = pick(o, 'action')
    const a = String(action?.value ?? '').trim().toUpperCase()
    if (!action) err('FIELD_MISSING', 'action is missing', 'action')
    else if (a === 'BUY' || a === 'SELL' || a === 'CANCEL' || a === 'CANCEL_ORDER') row.action = a === 'CANCEL_ORDER' ? 'CANCEL' : a
    else err('FIELD_INVALID', `action must be BUY, SELL or CANCEL, not "${a}"`, action.key)
    const rationale = pick(o, 'rationale')
    row.rationale = typeof rationale?.value === 'string' ? rationale.value.trim().slice(0, 1000) || null : null
    if (row.action === 'CANCEL') {
      const id = pick(o, 'orderId')
      row.orderId = typeof id?.value === 'string' ? id.value.trim() : null
      if (!row.orderId) err('FIELD_MISSING', 'order_id is missing', 'order_id')
    } else if (row.action) {
      const symbol = pick(o, 'symbol')
      row.symbol = typeof symbol?.value === 'string' ? symbol.value.trim().replace(/^\$/, '').toUpperCase() : null
      if (!row.symbol) err('FIELD_MISSING', 'symbol is missing', 'symbol')
      else if (!SYMBOL.test(row.symbol)) err('FIELD_INVALID', `"${row.symbol}" is not a ticker`, symbol?.key ?? 'symbol')
      for (const name of ['qty', 'notional', 'limitPrice', 'stopPrice', 'trailAmount', 'trailPercent'] as const) {
        const f = pick(o, name)
        if (!f) continue
        const d = decimal(f.value)
        if (d == null) err('FIELD_INVALID', `${f.key} must be a positive number like "10"`, f.key)
        else row[name] = d
      }
      const price = Object.entries(o).find(([k]) => norm(k) === 'price')
      const typeField = pick(o, 'type')
      const t = String(typeField?.value ?? '').trim().toUpperCase().replace(/[-\s]/g, '_')
      row.type = typeField ? (TYPES[t] ?? null) : row.limitPrice || price ? 'LIMIT' : 'MARKET'
      if (typeField && !row.type) err('FIELD_INVALID', `type "${t}" is unknown`, typeField.key)
      if (!typeField) rowIssues.push(issue('WARNING', 'TYPE_ASSUMED', `no type: taken as ${row.type}`, line, 'type'))
      if (price && decimal(price[1]) != null) {
        if (row.type === 'LIMIT' || row.type === 'STOP_LIMIT') row.limitPrice ??= decimal(price[1])
        else if (row.type === 'STOP') row.stopPrice ??= decimal(price[1])
      }
      const tif = String(pick(o, 'timeInForce')?.value ?? 'DAY').toUpperCase()
      row.timeInForce = tif === 'GTC' || tif.startsWith('GOOD_TIL') ? 'GTC' : 'DAY'
      const ext = pick(o, 'extendedHours')?.value
      row.extendedHours = ext === true || ext === 1 || (typeof ext === 'string' && ['true', 'yes', 'y', '1'].includes(ext.toLowerCase()))
      const ack = pick(o, 'acknowledge')?.value
      row.acknowledge = Array.isArray(ack) ? ack.map(String) : []
      if ((row.qty == null) === (row.notional == null)) {
        err(row.qty == null ? 'QTY_OR_NOTIONAL_REQUIRED' : 'FIELD_INVALID', row.qty == null ? 'send qty (shares) or notional (dollars)' : 'send qty or notional, not both', 'qty')
      }
      if (!row.rationale) err('RATIONALE_REQUIRED', 'AI bots must explain each order', 'rationale')
    }
    row.executable = !tooMany && !rowIssues.some((i) => i.severity === 'ERROR')
    return row
  })
  return { summary: summaryText, actions, issues }
}

// ------------------------------------------------------------------------------------------- routes

const sessionsToday = (m: MockManualBot) => m.sessions.filter((s) => etDate(Date.parse(s.createdAt ?? '')) === etDate(Date.now())).length
const noSession = (handle: string, sessionId: string) => problem(404, 'NOT_FOUND', 'Not found', `No session ${sessionId} for @${handle}`)
const listShape = (s: ManualSession): ManualSession => ({ ...s, untrusted: { summary: s.untrusted?.summary ?? null, prompt: null, reply: null } })
/** A row the mock will not place again (its result is final). */
const done = (a: ManualAction) => ['PLACED', 'REPLAYED', 'CANCELLED', 'CANCEL_REQUESTED'].includes(a.result?.status ?? '')

export function registerManualBotRoutes(r: ManualRouteFn, d: ManualMockDeps): void {
  const state = (handle: string): MockManualBot => (d.store()[handle] ??= { instructions: '', updatedAt: null, updatedBy: null, sessions: [] })

  /** §20.8 operator check: the owner or the admin; writes need ACTIVE players. */
  const operate = (rawHandle: string, write: boolean) => {
    const handle = rawHandle.toLowerCase()
    const bot = d.player(handle)
    if (!bot) return problem(404, 'PLAYER_NOT_FOUND', 'Player not found', `No player @${handle}.`)
    if (bot.summary.kind !== 'AI') return problem(403, 'FORBIDDEN', 'Forbidden', 'Only AI bots have a manual console.')
    const me = d.me()
    if (bot.ownerHandle !== me.handle && !me.isAdmin) return problem(403, 'FORBIDDEN', 'Forbidden', `Only @${bot.ownerHandle ?? 'the owner'} (or the admin) can operate @${handle}.`)
    if (write && !me.active) return problem(403, 'PLAYER_FROZEN', 'Frozen', 'Your account is frozen.')
    if (write && bot.status !== 'ACTIVE') return problem(403, bot.status === 'FROZEN' ? 'PLAYER_FROZEN' : 'PLAYER_DISABLED', 'Not active', `@${handle} is ${bot.status.toLowerCase()}.`)
    return { handle, bot }
  }
  const view = (handle: string): ManualBot => {
    const bot = d.player(handle)!
    const m = state(handle)
    return {
      bot: bot.summary,
      ownerHandle: bot.ownerHandle,
      status: bot.status,
      instructions: m.instructions,
      instructionsUpdatedAt: m.updatedAt,
      instructionsUpdatedBy: m.updatedBy,
      activeApiKeys: d.activeKeys(handle),
      activeConnectors: 0,
      sessionsToday: sessionsToday(m),
      sessionsPerDay: SESSIONS_PER_DAY,
      limits: LIMITS,
    }
  }
  const find = (handle: string, sessionId: string) => state(handle).sessions.find((s) => s.sessionId === sessionId)
  /** Open orders placed by earlier sessions (the mock bot's only orders). */
  const openOrder = (handle: string, orderId: string) =>
    state(handle)
      .sessions.flatMap((s) => s.actions ?? [])
      .find((a) => a.result?.orderId === orderId && (a.result.orderStatus === 'WORKING' || a.result.orderStatus === 'PENDING'))

  r('GET', '/me/bots', () => json([...d.owned()].sort().filter((h) => d.player(h)).map(view)))
  r('GET', '/me/bots/{handle}/manual', ({ params }) => {
    const op = operate(params[0]!, false)
    return op instanceof Response ? op : json(view(op.handle))
  })
  r('PUT', '/me/bots/{handle}/manual', ({ params, body }) => {
    const op = operate(params[0]!, true)
    if (op instanceof Response) return op
    const b = (body ?? {}) as { instructions?: string | null; aiModel?: string | null; displayName?: string | null }
    if (b.displayName != null && !/^[A-Za-z0-9 _.-]{2,40}$/.test(b.displayName.trim())) return invalid('2-40 characters: letters, digits, space, _ . -', 'displayName')
    if (b.instructions != null) {
      const text = b.instructions.replace(/\r\n?/g, '\n').trim().replace(KEY_SHAPED_INLINE, '[redacted]')
      if (text.length > LIMITS.maxInstructionsChars) return invalid(`At most ${LIMITS.maxInstructionsChars} characters.`, 'instructions')
      Object.assign(state(op.handle), { instructions: text, updatedAt: new Date().toISOString(), updatedBy: d.me().handle })
    }
    if (b.aiModel != null) {
      const label = b.aiModel.trim()
      if (label.length < 1 || label.length > 24) return invalid('The model label is 1-24 characters.', 'aiModel')
      d.setAiModel(op.handle, label)
    }
    if (b.displayName != null) d.setDisplayName(op.handle, b.displayName.trim())
    return json(view(op.handle))
  })
  r('POST', '/me/bots/{handle}/manual-sessions', ({ params }) => {
    const op = operate(params[0]!, true)
    if (op instanceof Response) return op
    const m = state(op.handle)
    const now = Date.now()
    if (sessionsToday(m) >= SESSIONS_PER_DAY) {
      const midnight = Date.parse(`${etDate(now + 86_400_000)}T00:00:00-04:00`)
      const retry = Math.max(1, Math.ceil((midnight - now) / 1000))
      return problem(429, 'RATE_LIMITED', 'Too many requests', `At most ${SESSIONS_PER_DAY} prompts per day (ET) for @${op.handle}. Retry in ${retry} s.`, undefined, retry)
    }
    const sessionId = `mbs_${ulid()}`
    const clock = d.clock()
    const prompt = buildPrompt(op.bot.summary, op.bot.ownerHandle ?? d.me().handle, sessionId, m.instructions, clock, now)
    const s: ManualSession = {
      sessionId,
      botHandle: op.handle,
      operatorHandle: d.me().handle,
      status: 'PROMPTED',
      aiModel: op.bot.summary.aiModel ?? 'AI',
      marketSession: clock.session,
      createdAt: new Date(now).toISOString(),
      repliedAt: null,
      executedAt: null,
      promptSha256: fakeSha(prompt),
      promptChars: prompt.length,
      replyChars: null,
      untrusted: { summary: null, prompt, reply: null },
      issues: [],
      actions: [],
      journalNoteId: null,
    }
    m.sessions.push(s)
    return json(s, 201)
  })
  r('POST', '/me/bots/{handle}/manual-sessions/{sessionId}/reply', ({ params, body }) => {
    const op = operate(params[0]!, true)
    if (op instanceof Response) return op
    const s = find(op.handle, params[1]!)
    if (!s) return noSession(op.handle, params[1]!)
    const reply = (body as { reply?: string | null } | null)?.reply
    if (!reply?.trim()) return invalid("Paste the AI's whole reply.", 'reply')
    if (reply.length > LIMITS.maxReplyChars) return invalid('The reply is longer than 20,000 characters; paste only the part with the JSON block.', 'reply')
    if (s.executedAt) return invalid('This session already placed orders; make a new prompt.')
    const redacted = reply.replace(KEY_SHAPED_INLINE, '[redacted]')
    const parsed = parseManualReply(redacted, s.sessionId!)
    for (const a of parsed.actions) {
      if (a.action === 'CANCEL') {
        if (a.executable && !openOrder(op.handle, a.orderId ?? '')) {
          a.issues!.push(issue('ERROR', 'ORDER_NOT_FOUND', `No open order ${a.orderId} of @${op.handle}`, a.line, 'order_id'))
          a.executable = false
        }
        continue
      }
      if (!a.executable || !a.action) continue
      const pv = d.preview({
        clientOrderId: 'mbs-preview', symbol: a.symbol, side: a.action, type: a.type, qty: a.qty, notional: a.notional, limitPrice: a.limitPrice,
        stopPrice: a.stopPrice, trailAmount: a.trailAmount, trailPercent: a.trailPercent, timeInForce: a.timeInForce, extendedHours: a.extendedHours,
        rationale: a.rationale, acknowledge: a.acknowledge,
      })
      for (const e of pv.errors ?? []) a.issues!.push({ severity: 'ERROR', code: e.code, field: e.field, line: a.line, column: null, message: e.message, hint: e.hint })
      for (const w of pv.warnings ?? []) a.issues!.push({ severity: 'WARNING', code: w.code, field: null, line: a.line, column: null, message: w.message, hint: null })
      a.estimate = pv.estimate
      a.executable = (pv.errors ?? []).length === 0
    }
    Object.assign(s, {
      status: 'REPLIED',
      repliedAt: new Date().toISOString(),
      replyChars: redacted.length,
      untrusted: { prompt: s.untrusted?.prompt ?? null, summary: parsed.summary, reply: redacted },
      issues: parsed.issues,
      actions: parsed.actions,
    } satisfies Partial<ManualSession>)
    return json(s)
  })
  r('POST', '/me/bots/{handle}/manual-sessions/{sessionId}/execute', ({ params, body }) => {
    const op = operate(params[0]!, true)
    if (op instanceof Response) return op
    const s = find(op.handle, params[1]!)
    if (!s) return noSession(op.handle, params[1]!)
    const indexes = (body as { actions?: number[] | null } | null)?.actions
    if (!Array.isArray(indexes)) return invalid('Required: the rows to place ([] posts the summary only).', 'actions')
    if (s.status === 'PROMPTED') return invalid('Check a reply first.')
    if ((s.issues ?? []).some((i) => i.severity === 'ERROR')) return invalid('Fix the reply first.')
    const rows = s.actions ?? []
    for (const i of indexes) if (!rows.find((a) => a.index === i)?.executable) return invalid(`Row ${i} is unknown or cannot be placed.`, 'actions')
    const now = new Date().toISOString()
    s.executedAt ??= now
    s.status = 'EXECUTED'
    for (const kind of ['CANCEL', 'SELL', 'BUY'] as const) {
      for (const a of rows) {
        if (a.action !== kind || !indexes.includes(a.index) || done(a)) continue
        if (kind === 'CANCEL') {
          const target = openOrder(op.handle, a.orderId ?? '')
          if (target?.result) target.result.orderStatus = 'CANCELLED'
          if (target) d.cancelled(op.handle, a.orderId ?? '', now)
          a.result = target
            ? { status: 'CANCELLED', orderId: a.orderId, orderStatus: 'CANCELLED', code: null, message: null, at: now }
            : { status: 'FAILED', orderId: a.orderId, orderStatus: null, code: 'ORDER_NOT_CANCELLABLE', message: 'The order is no longer open.', at: now }
        } else {
          const orderId = `ord_${ulid()}`
          const orderStatus = d.placed(op.handle, a, s.sessionId ?? '', orderId, now)
          a.result = { status: 'PLACED', orderId, orderStatus, code: null, message: null, at: now }
        }
      }
    }
    if (!s.journalNoteId && s.untrusted?.summary) s.journalNoteId = `jnl_${ulid()}`
    return json(s)
  })
  r('GET', '/players/{handle}/manual-sessions', ({ params, url }) => {
    const handle = params[0]!.toLowerCase()
    const p = d.player(handle)
    if (!p || p.status === 'DISABLED') return problem(404, 'PLAYER_NOT_FOUND', 'Player not found', `No player @${handle}.`)
    const limit = Math.max(1, Math.min(50, Number(url.searchParams.get('limit') ?? 10) || 10))
    const start = Number(url.searchParams.get('cursor') ?? 0) || 0
    const rows = (d.store()[handle]?.sessions ?? []).toReversed()
    return json({ items: rows.slice(start, start + limit).map(listShape), nextCursor: start + limit < rows.length ? String(start + limit) : null })
  })
  r('GET', '/players/{handle}/manual-sessions/{sessionId}', ({ params }) => {
    const handle = params[0]!.toLowerCase()
    const p = d.player(handle)
    if (!p || p.status === 'DISABLED') return problem(404, 'PLAYER_NOT_FOUND', 'Player not found', `No player @${handle}.`)
    const s = d.store()[handle]?.sessions.find((x) => x.sessionId === params[1])
    return s ? json(s) : noSession(handle, params[1]!)
  })
}
