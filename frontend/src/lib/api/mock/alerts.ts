/**
 * Mock mode (§22.9) for the Phase 2 slice: price alerts (`/alerts`, §19.1) and Web Push settings (`/me/push`, §19.2).
 * Alerts are evaluated on every fake market step of their symbol, with the backend's rules (live price, one firing
 * per trigger, re-arm 0.5% back for repeating alerts) and write a PRICE_ALERT notification. The push half has a
 * seeded device and answers the settings, preference, device and test calls; the mock registers no service worker,
 * so "Enable on this device" only works against a real backend.
 */
import { type Dec, add, compare, gte, isDecimal, lte, mul, shift, sign, stripTrailingZeros, sub } from '../../format/decimal'
import { formatPct, formatPrice } from '../../format/number'
import type { Alert, AlertCondition, AlertStatus, Notification, PushDevice, PushPreferences, Quote } from '../types'

type Handler = (ctx: { body: unknown; params: string[]; url: URL }) => Response | Promise<Response>
type Route = (method: string, pattern: string, handler: Handler) => void

export interface AlertsHost {
  quote: (symbol: string) => Quote
  known: (symbol: string) => boolean
  notify: (kind: NonNullable<Notification['kind']>, title: string, body: string, payload: Record<string, unknown> | null) => void
}

interface P2State {
  v: 1
  alerts: Alert[]
  preferences: PushPreferences
  devices: (PushDevice & { endpoint: string | null })[]
  lastTest: number
}

const KEY = 'stocks.mock.p2alerts.v1'
const MAX_LIVE = 50
const BAND = '0.005'
/** A well-formed (but fake) P-256 public key, so the settings page has something to show. */
const MOCK_VAPID = 'BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM'

const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const noContent = (): Response => new Response(null, { status: 204 })
function problem(status: number, code: string, title: string, detail: string, field?: string): Response {
  return new Response(
    JSON.stringify({
      type: `/errors/${code}`,
      title,
      status,
      code,
      detail,
      hint: null,
      fields: field ? [{ field, code, message: detail }] : [],
      retryAfterS: code === 'RATE_LIMITED' ? 10 : null,
      requestId: `req_mock${Date.now()}`,
    }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

const iso = (ms = Date.now()) => new Date(ms).toISOString()
let seq = 0
const newId = (prefix: string) => `${prefix}_01MOCK${String(Date.now()).slice(-8)}${String(++seq).padStart(4, '0')}`.slice(0, 30)

function load(): P2State {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (raw) {
      const s = JSON.parse(raw) as P2State
      if (s.v === 1) return s
    }
  } catch {
    /* fresh */
  }
  const day = 86_400_000
  return {
    v: 1,
    alerts: [],
    preferences: { fills: true, alerts: true, money: true, account: true, league: true },
    devices: [
      { id: 'psh_01MOCKSEEDEDIPHONE0000000', label: 'Safari on iPhone', pushService: 'web.push.apple.com', current: false, createdAt: iso(Date.now() - 3 * day), lastSuccessAt: iso(Date.now() - day), lastFailureAt: null, endpoint: null },
    ],
    lastTest: 0,
  }
}

const isPrice = (c: AlertCondition) => c === 'PRICE_GTE' || c === 'PRICE_LTE'
const isAbove = (c: AlertCondition) => c === 'PRICE_GTE' || c === 'CHANGE_PCT_GTE'
const CONDITIONS: AlertCondition[] = ['PRICE_GTE', 'PRICE_LTE', 'CHANGE_PCT_GTE', 'CHANGE_PCT_LTE']

function scaled(c: AlertCondition, t: Dec): Dec {
  const s = stripTrailingZeros(t)
  const [int, frac = ''] = s.split('.')
  const scale = isPrice(c) ? 4 : 6
  return `${int}.${frac.padEnd(scale, '0').slice(0, scale)}`
}

function thresholdProblem(c: AlertCondition, t: unknown): string | null {
  if (typeof t !== 'string' && typeof t !== 'number') return 'threshold is required.'
  const d = String(t)
  if (!isDecimal(d)) return 'threshold must be a decimal string.'
  if (isPrice(c)) return sign(d) <= 0 || gte(d, '1000000') ? 'A price threshold must be above 0 and below 1,000,000.' : null
  return lte(d, '-1') || compare(d, '10') > 0 ? 'A change threshold is a fraction above -1 and at most 10.' : null
}

/** Registers the mock routes; returns the evaluation hooks the fake market calls. */
export function registerAlertsAndPush(route: Route, host: AlertsHost) {
  const s = load()
  const save = () => {
    try {
      sessionStorage.setItem(KEY, JSON.stringify(s))
    } catch {
      /* memory only */
    }
  }
  const live = () => s.alerts.filter((a) => a.status === 'ACTIVE' || a.status === 'PAUSED').length
  const find = (id: string | undefined) => s.alerts.find((a) => a.id === id)

  // ---------------------------------------------------------------- alerts
  route('GET', '/alerts', ({ url }) => {
    const status = url.searchParams.get('status')?.toUpperCase()
    const symbol = url.searchParams.get('symbol')?.toUpperCase()
    const order = { ACTIVE: 0, PAUSED: 1, TRIGGERED: 2, EXPIRED: 3 } as Record<string, number>
    return json(
      s.alerts
        .filter((a) => (!status || a.status === status) && (!symbol || a.symbol === symbol))
        .sort((a, b) => (order[a.status ?? ''] ?? 9) - (order[b.status ?? ''] ?? 9) || (b.createdAt ?? '').localeCompare(a.createdAt ?? '')),
    )
  })
  route('POST', '/alerts', ({ body }) => {
    const b = (body ?? {}) as { symbol?: string; condition?: AlertCondition; threshold?: string; repeat?: boolean | null; expiresAt?: string | null; note?: string | null }
    const symbol = b.symbol?.trim().toUpperCase() ?? ''
    if (!symbol) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'symbol is required.', 'symbol')
    if (!host.known(symbol)) return problem(404, 'SYMBOL_NOT_FOUND', 'Symbol not found', `Unknown symbol ${symbol}.`, 'symbol')
    if (!b.condition || !CONDITIONS.includes(b.condition)) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'condition is required.', 'condition')
    const bad = thresholdProblem(b.condition, b.threshold)
    if (bad) return problem(400, 'VALIDATION_FAILED', 'Validation failed', bad, 'threshold')
    const threshold = scaled(b.condition, String(b.threshold))
    const repeat = Boolean(b.repeat)
    const same = s.alerts.find((a) => a.symbol === symbol && a.condition === b.condition && a.threshold === threshold && a.repeat === repeat && (a.status === 'ACTIVE' || a.status === 'PAUSED'))
    if (same) return json(same, 200)
    if (live() >= MAX_LIVE) return problem(422, 'TOO_MANY_ALERTS', 'Too many alerts', `You already have ${MAX_LIVE} active or paused alerts.`)
    const now = iso()
    const alert: Alert = {
      id: newId('alr'), symbol, condition: b.condition, threshold, repeat, status: 'ACTIVE', armed: true,
      note: b.note?.trim() || null, expiresAt: b.expiresAt ?? null, triggerCount: 0, lastTriggeredAt: null,
      lastTriggerPrice: null, createdAt: now, updatedAt: now,
    }
    s.alerts.push(alert)
    save()
    evaluate(symbol)
    return json(find(alert.id!) ?? alert, 201)
  })
  route('PUT', '/alerts/{alertId}', ({ params, body }) => {
    const a = find(params[0])
    if (!a) return problem(404, 'NOT_FOUND', 'Not found', 'No such alert.')
    const b = (body ?? {}) as { condition?: AlertCondition; threshold?: string; repeat?: boolean | null; expiresAt?: string | null; note?: string | null }
    if (!b.condition || !CONDITIONS.includes(b.condition)) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'condition is required.', 'condition')
    const bad = thresholdProblem(b.condition, b.threshold)
    if (bad) return problem(400, 'VALIDATION_FAILED', 'Validation failed', bad, 'threshold')
    if (a.status !== 'ACTIVE' && a.status !== 'PAUSED' && live() >= MAX_LIVE) return problem(422, 'TOO_MANY_ALERTS', 'Too many alerts', 'Delete an alert first.')
    Object.assign(a, {
      condition: b.condition, threshold: scaled(b.condition, String(b.threshold)), repeat: Boolean(b.repeat), expiresAt: b.expiresAt ?? null,
      note: b.note?.trim() || null, armed: true, status: a.status === 'PAUSED' ? 'PAUSED' : 'ACTIVE', updatedAt: iso(),
    } satisfies Partial<Alert>)
    save()
    evaluate(a.symbol ?? '')
    return json(a)
  })
  const transition = (id: string | undefined, from: AlertStatus, to: AlertStatus) => {
    const a = find(id)
    if (!a) return problem(404, 'NOT_FOUND', 'Not found', 'No such alert.')
    if (a.status === from) Object.assign(a, { status: to, armed: true, updatedAt: iso() })
    save()
    if (to === 'ACTIVE') evaluate(a.symbol ?? '')
    return json(a)
  }
  route('POST', '/alerts/{alertId}/pause', ({ params }) => transition(params[0], 'ACTIVE', 'PAUSED'))
  route('POST', '/alerts/{alertId}/resume', ({ params }) => transition(params[0], 'PAUSED', 'ACTIVE'))
  route('DELETE', '/alerts/{alertId}', ({ params }) => {
    const i = s.alerts.findIndex((a) => a.id === params[0])
    if (i < 0) return problem(404, 'NOT_FOUND', 'Not found', 'No such alert.')
    s.alerts.splice(i, 1)
    save()
    return noContent()
  })

  // ---------------------------------------------------------------- push
  const settings = () => ({
    available: true,
    vapidPublicKey: MOCK_VAPID,
    preferences: s.preferences,
    devices: s.devices.map(({ endpoint: _e, ...d }) => d),
  })
  route('GET', '/me/push', () => json(settings()))
  route('PATCH', '/me/push/preferences', ({ body }) => {
    const b = (body ?? {}) as Partial<Record<keyof PushPreferences, boolean | null>>
    for (const k of Object.keys(s.preferences) as (keyof PushPreferences)[]) {
      const v = b[k]
      if (typeof v === 'boolean') s.preferences[k] = v
    }
    save()
    return json(s.preferences)
  })
  route('POST', '/me/push/subscriptions', ({ body }) => {
    const b = (body ?? {}) as { endpoint?: string }
    if (!b.endpoint) return problem(400, 'VALIDATION_FAILED', 'Validation failed', 'endpoint is required.', 'endpoint')
    let d = s.devices.find((x) => x.endpoint === b.endpoint)
    if (!d) {
      d = { id: newId('psh'), label: 'This browser', pushService: new URL(b.endpoint).host, current: true, createdAt: iso(), lastSuccessAt: null, lastFailureAt: null, endpoint: b.endpoint }
      s.devices.push(d)
    }
    save()
    const { endpoint: _e, ...out } = d
    return json(out, 201)
  })
  route('POST', '/me/push/sync', ({ body }) => {
    const d = s.devices.find((x) => x.endpoint === (body as { endpoint?: string } | null)?.endpoint)
    if (!d) return json({ registered: false, device: null })
    const { endpoint: _e, ...out } = d
    return json({ registered: true, device: out })
  })
  route('POST', '/me/push/unsubscribe', ({ body }) => {
    const endpoint = (body as { endpoint?: string } | null)?.endpoint
    s.devices = s.devices.filter((x) => x.endpoint !== endpoint)
    save()
    return noContent()
  })
  route('DELETE', '/me/push/subscriptions/{deviceId}', ({ params }) => {
    const before = s.devices.length
    s.devices = s.devices.filter((x) => x.id !== params[0])
    save()
    return before === s.devices.length ? problem(404, 'NOT_FOUND', 'Not found', 'No such device.') : noContent()
  })
  route('POST', '/me/push/test', () => {
    if (Date.now() - s.lastTest < 10_000) return problem(429, 'RATE_LIMITED', 'Rate limited', 'One test notification per 10 seconds.')
    s.lastTest = Date.now()
    for (const d of s.devices) d.lastSuccessAt = iso()
    save()
    return json({ devices: s.devices.length, delivered: s.devices.length, failed: 0, removed: 0 })
  })

  // ---------------------------------------------------------------- evaluation (§19.1 rules)
  function evaluate(symbol: string): void {
    const mine = s.alerts.filter((a) => a.symbol === symbol && a.status === 'ACTIVE')
    if (mine.length === 0) return
    const q = host.quote(symbol)
    if (!q.last || q.quality === 'NONE' || q.quality === 'STALE' || q.quality === 'SYNTHETIC' || q.halted) return
    const now = Date.now()
    let changed = false
    for (const a of mine) {
      if (!a.condition || !a.threshold) continue
      if (a.expiresAt && Date.parse(a.expiresAt) <= now) {
        Object.assign(a, { status: 'EXPIRED', armed: false, updatedAt: iso() })
        changed = true
        continue
      }
      const value = isPrice(a.condition) ? q.last : q.changePct
      if (!value) continue
      if (!a.armed) {
        const level = isPrice(a.condition)
          ? mul(a.threshold, isAbove(a.condition) ? shift('99.5', -2) : shift('100.5', -2))
          : isAbove(a.condition) ? sub(a.threshold, BAND) : add(a.threshold, BAND)
        const back = isAbove(a.condition) ? lte(value, level) : gte(value, level)
        if (!back) continue
        a.armed = true
        changed = true
      }
      const holds = isAbove(a.condition) ? gte(value, a.threshold) : lte(value, a.threshold)
      if (!holds) continue
      a.triggerCount += 1
      Object.assign(a, { armed: false, status: a.repeat ? 'ACTIVE' : 'TRIGGERED', lastTriggeredAt: iso(), lastTriggerPrice: q.last, updatedAt: iso() })
      changed = true
      const op = isAbove(a.condition) ? '≥' : '≤'
      const shown = isPrice(a.condition) ? `$${formatPrice(a.threshold)}` : formatPct(a.threshold)
      host.notify(
        'PRICE_ALERT',
        `${symbol}${isPrice(a.condition) ? '' : ' day change'} ${op} ${shown}`,
        `${symbol} is at $${formatPrice(q.last)} (${formatPct(q.changePct)} today). Your alert: ${isPrice(a.condition) ? 'price' : 'day change'} ${isAbove(a.condition) ? 'at or above' : 'at or below'} ${shown}.${a.note ? ` Note: ${a.note}` : ''}`,
        { alertId: a.id, symbol, condition: a.condition, threshold: a.threshold, price: q.last, changePct: q.changePct },
      )
    }
    if (changed) save()
  }

  return {
    evaluate,
    /** Symbols with an ACTIVE alert: the fake market keeps stepping them off screen. */
    symbols: (): string[] => [...new Set(s.alerts.filter((a) => a.status === 'ACTIVE' && a.symbol).map((a) => a.symbol!))],
  }
}
