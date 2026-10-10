/**
 * Fake STOMP broker for mock mode (§22.9): a ticking quote stream for the subscribed symbols plus the
 * user queues published by the mock server. Topic shapes follow §19.
 */
import type { DriverHooks, RealtimeDriver } from '../../realtime/hub'
import { mockServer } from './index'

const QUOTE_RE = /^\/topic\/(quote|tick)\.(.+)$/

export function createMockDriver(): RealtimeDriver {
  const server = mockServer()
  const subscribed = new Set<string>()
  let hooks: DriverHooks | null = null
  let timer: ReturnType<typeof setInterval> | null = null
  let unlisten: (() => void) | null = null
  let lastSession = 0

  const pump = () => {
    if (!hooks) return
    const symbols = new Set<string>()
    for (const d of subscribed) {
      const m = QUOTE_RE.exec(d)
      if (m) symbols.add(m[2]!)
    }
    for (const symbol of symbols) {
      if (Math.random() < 0.55) continue // not every symbol prints every tick
      server.market.step(symbol)
      server.onMarketStep(symbol)
      if (subscribed.has(`/topic/quote.${symbol}`)) hooks.onMessage(`/topic/quote.${symbol}`, server.market.quote(symbol))
      if (subscribed.has(`/topic/tick.${symbol}`)) hooks.onMessage(`/topic/tick.${symbol}`, server.market.tick(symbol))
    }
    if (Date.now() - lastSession > 60_000 && subscribed.has('/topic/market.session')) {
      lastSession = Date.now()
    }
  }

  return {
    start(h) {
      hooks = h
      unlisten = server.subscribe((destination, body) => {
        if (subscribed.has(destination)) hooks?.onMessage(destination, body)
      })
      timer = setInterval(pump, 900)
      setTimeout(() => hooks?.onConnected(), 120)
    },
    stop() {
      if (timer) clearInterval(timer)
      unlisten?.()
      timer = null
      hooks = null
      subscribed.clear()
    },
    subscribe(destination) {
      subscribed.add(destination)
    },
    unsubscribe(destination) {
      subscribed.delete(destination)
    },
  }
}
