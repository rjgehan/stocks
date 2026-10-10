import { describe, expect, it, vi } from 'vitest'
import { type DriverHooks, type MessageHandler, type RealtimeDriver, RealtimeHub } from './hub'
import { useQuoteStore } from './quoteStore'
import type { Quote } from '../api/types'

function fakeDriver() {
  let hooks: DriverHooks | null = null
  const driver: RealtimeDriver & { hooks: () => DriverHooks } = {
    start: vi.fn<(h: DriverHooks) => void>((h) => {
      hooks = h
    }),
    stop: vi.fn<() => void>(),
    subscribe: vi.fn<(destination: string) => void>(),
    unsubscribe: vi.fn<(destination: string) => void>(),
    hooks: () => hooks!,
  }
  return driver
}

describe('RealtimeHub (§19 ref-counted subscriptions)', () => {
  it('opens one server subscription per destination and closes it after the last subscriber', () => {
    const driver = fakeDriver()
    const hub = new RealtimeHub(driver)
    const a = vi.fn<MessageHandler>()
    const b = vi.fn<MessageHandler>()
    const offA = hub.subscribe('/topic/tick.AAPL', a)
    const offB = hub.subscribe('/topic/tick.AAPL', b)
    expect(driver.start).toHaveBeenCalledTimes(1)
    driver.hooks().onConnected()
    expect(driver.subscribe).toHaveBeenCalledTimes(1)
    expect(hub.refCount('/topic/tick.AAPL')).toBe(2)

    driver.hooks().onMessage('/topic/tick.AAPL', { last: '1.00' })
    expect(a).toHaveBeenCalledWith({ last: '1.00' })
    expect(b).toHaveBeenCalledWith({ last: '1.00' })

    offA()
    offA() // idempotent
    expect(driver.unsubscribe).not.toHaveBeenCalled()
    offB()
    expect(driver.unsubscribe).toHaveBeenCalledWith('/topic/tick.AAPL')
    expect(hub.refCount('/topic/tick.AAPL')).toBe(0)
  })

  it('resubscribes everything and resyncs after a reconnect', () => {
    const driver = fakeDriver()
    const hub = new RealtimeHub(driver)
    const resync = vi.fn<() => void>()
    hub.onResync(resync)
    hub.subscribe('/topic/quote.NVDA', vi.fn<MessageHandler>())
    hub.subscribe('/user/queue/orders', vi.fn<MessageHandler>())
    driver.hooks().onConnected()
    expect(resync).not.toHaveBeenCalled() // first connect: nothing missed yet
    driver.hooks().onDisconnected()
    expect(hub.getStatus()).toBe('reconnecting')
    vi.mocked(driver.subscribe).mockClear()
    driver.hooks().onConnected()
    expect(vi.mocked(driver.subscribe).mock.calls.map((c) => c[0]).sort()).toEqual(['/topic/quote.NVDA', '/user/queue/orders'])
    expect(resync).toHaveBeenCalledTimes(1)
  })
})

describe('quoteStore', () => {
  const base: Quote = {
    symbol: 'AAPL', bid: '227.39', bidSize: 300, ask: '227.41', askSize: 200, last: '227.40', lastSize: 100,
    lastAt: null, prevClose: '224.37', change: '3.03', changePct: '0.0135', dayOpen: null, dayHigh: null, dayLow: null,
    volume: 1, volumeIsPartial: false, extended: null, asOf: null, ageMs: null, quality: 'SIMULATED', source: 'sim',
    halted: false, session: 'REGULAR',
  }

  it('applies ticks onto a seeded quote and ignores ticks for unknown symbols', () => {
    const s = useQuoteStore.getState()
    s.applyTick({ symbol: 'MSFT', last: '1', change: '0', changePct: '0', extended: null, asOf: null, quality: 'SIMULATED' })
    expect(useQuoteStore.getState().quotes.MSFT).toBeUndefined()
    s.upsertQuote(base)
    s.applyTick({ symbol: 'AAPL', last: '227.55', change: '3.18', changePct: '0.0142', extended: null, asOf: 'x', quality: 'SIMULATED' })
    const q = useQuoteStore.getState().quotes.AAPL!
    expect(q.last).toBe('227.55')
    expect(q.bid).toBe('227.39') // bid/ask come only from full quotes
  })
})
