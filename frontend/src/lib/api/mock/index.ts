/**
 * Mock mode entry point (§22.9): `VITE_API_MOCK=1` (npm run dev:mock) routes the REST client and the
 * realtime hub here. No extra dependencies; never bundled into normal builds.
 */
import { MockServer } from './server'

let server: MockServer | null = null

/**
 * Test hooks for Playwright in mock mode (`window.stocksMock`): move a price through a stop trigger and publish a
 * league feed event without waiting for the random walk or the feed timer. Mock builds only.
 */
export interface MockHooks {
  nudge(symbol: string, bps: number): void
  last(symbol: string): string | null
  emitFeed(kind?: Parameters<MockServer['emitFeed']>[0], handle?: string): string | null
  /** Phase 2 (§16.11): a bot reacts to the newest feed item; returns the item id. */
  botReact(handle?: string, reaction?: Parameters<MockServer['botReact']>[1]): string | null
  expireRecentAuth(): void
}

declare global {
  interface Window {
    stocksMock?: MockHooks
  }
}

export function mockServer(): MockServer {
  if (!server) {
    const s = new MockServer()
    server = s
    if (typeof window !== 'undefined') {
      window.stocksMock = {
        nudge: (symbol, bps) => {
          s.market.nudge(symbol, bps)
          s.onMarketStep(symbol)
        },
        last: (symbol) => s.market.quote(symbol).last,
        emitFeed: (kind, handle) => s.emitFeed(kind, handle)?.id ?? null,
        botReact: (handle, reaction) => s.botReact(handle, reaction),
        expireRecentAuth: () => s.expireRecentAuth(),
      }
    }
  }
  return server
}

export function mockFetch(request: Request): Promise<Response> {
  return mockServer().handle(request)
}

export { createMockDriver } from './realtime'
