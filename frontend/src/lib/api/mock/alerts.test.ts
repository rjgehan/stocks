import { beforeEach, describe, expect, it } from 'vitest'
import type { Alert, Notification, PushSettings } from '../types'
import { MockServer } from './server'

/** The mock backend's Phase 2 slice (§22.9): `/alerts` with the §19.1 firing rules, and `/me/push`. */

let server: MockServer
let csrf = ''

async function call<T = unknown>(method: string, path: string, body?: unknown): Promise<{ status: number; body: T }> {
  if (!csrf) {
    await server.handle(new Request('http://localhost/api/v1/auth/csrf'))
    csrf = /XSRF-TOKEN=([^;]+)/.exec(document.cookie)?.[1] ?? ''
  }
  const res = await server.handle(
    new Request(`http://localhost/api/v1${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  )
  return { status: res.status, body: (res.status === 204 ? null : await res.json()) as T }
}

beforeEach(() => {
  sessionStorage.clear()
  csrf = ''
  server = new MockServer()
})

describe('mock price alerts', () => {
  it('creates idempotently, validates and lists in status order', async () => {
    const created = await call<Alert>('POST', '/alerts', { symbol: 'aapl', condition: 'PRICE_GTE', threshold: '100000' })
    expect(created.status).toBe(201)
    expect(created.body).toMatchObject({ symbol: 'AAPL', threshold: '100000.0000', status: 'ACTIVE', armed: true, repeat: false })
    const again = await call<Alert>('POST', '/alerts', { symbol: 'AAPL', condition: 'PRICE_GTE', threshold: '100000.00' })
    expect(again.status).toBe(200)
    expect(again.body.id).toBe(created.body.id)
    expect((await call('POST', '/alerts', { symbol: 'ZZZZZZ', condition: 'PRICE_GTE', threshold: '1' })).status).toBe(404)
    expect((await call('POST', '/alerts', { symbol: 'AAPL', condition: 'PRICE_GTE', threshold: '-1' })).status).toBe(400)
    const paused = await call<Alert>('POST', `/alerts/${created.body.id}/pause`)
    expect(paused.body.status).toBe('PAUSED')
    const list = await call<Alert[]>('GET', '/alerts')
    expect(list.body.map((a) => a.status)).toEqual(['PAUSED'])
    expect((await call('DELETE', `/alerts/${created.body.id}`)).status).toBe(204)
    expect((await call('DELETE', `/alerts/${created.body.id}`)).status).toBe(404)
  })

  it('fires once when a market step crosses the threshold and writes a PRICE_ALERT notification', async () => {
    const last = server.market.quote('AAPL').last!
    const created = await call<Alert>('POST', '/alerts', { symbol: 'AAPL', condition: 'PRICE_GTE', threshold: last })
    // the condition already holds: the create evaluated it at once
    expect(created.body.status).toBe('TRIGGERED')
    expect(created.body.triggerCount).toBe(1)
    const notes = await call<{ items: Notification[] }>('GET', '/me/notifications')
    const alertNote = notes.body.items.find((n) => n.kind === 'PRICE_ALERT')
    expect(alertNote?.title).toMatch(/^AAPL ≥ \$/)
    expect(alertNote?.payload).toMatchObject({ alertId: created.body.id, symbol: 'AAPL' })
    server.market.nudge('AAPL', 100)
    server.onMarketStep('AAPL')
    const after = (await call<{ items: Notification[] }>('GET', '/me/notifications')).body.items.filter((n) => n.kind === 'PRICE_ALERT')
    expect(after).toHaveLength(1)
  })

  it('a repeating alert re-arms only after moving back past the band', async () => {
    const last = server.market.quote('NVDA').last!
    const a = (await call<Alert>('POST', '/alerts', { symbol: 'NVDA', condition: 'PRICE_GTE', threshold: last, repeat: true })).body
    expect(a).toMatchObject({ status: 'ACTIVE', armed: false, triggerCount: 1 })
    server.market.nudge('NVDA', -100) // 1% down: past the 0.5% band
    server.onMarketStep('NVDA')
    const rearmed = (await call<Alert[]>('GET', '/alerts')).body[0]!
    expect(rearmed.armed).toBe(true)
    server.market.nudge('NVDA', 300)
    server.onMarketStep('NVDA')
    expect((await call<Alert[]>('GET', '/alerts')).body[0]!.triggerCount).toBe(2)
    expect(server.workingSymbols()).toContain('NVDA')
  })
})

describe('mock push settings', () => {
  it('serves settings, preferences, devices and a rate-limited test', async () => {
    const s = (await call<PushSettings>('GET', '/me/push')).body
    expect(s.available).toBe(true)
    expect(s.vapidPublicKey).toHaveLength(87)
    expect(s.devices).toHaveLength(1)
    expect(JSON.stringify(s)).not.toContain('endpoint')
    expect((await call('PATCH', '/me/push/preferences', { league: false })).body).toMatchObject({ league: false, fills: true })
    const test = await call<{ devices: number; delivered: number }>('POST', '/me/push/test')
    expect(test.body).toMatchObject({ devices: 1, delivered: 1 })
    expect((await call('POST', '/me/push/test')).status).toBe(429)
    expect((await call('DELETE', `/me/push/subscriptions/${s.devices![0]!.id}`)).status).toBe(204)
    expect((await call<PushSettings>('GET', '/me/push')).body.devices).toHaveLength(0)
  })
})
