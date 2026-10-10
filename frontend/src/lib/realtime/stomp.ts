/**
 * STOMP over native WebSocket at `/ws` (§19): same origin, no SockJS, heartbeats 10 s / 10 s (keeps the
 * socket under Cloudflare's ~100 s idle limit), automatic reconnect with the library's exponential backoff
 * (1 s doubling to 30 s; a successful CONNECT resets it), and the RAW `XSRF-TOKEN` cookie value sent as
 * `X-XSRF-TOKEN` in the CONNECT frame (§17.1 chain 3).
 *
 * `realtime()` returns the app-wide singleton hub. In mock mode (§22.9) it is backed by the in-memory fake
 * quote stream instead; that branch is constant-folded out of normal builds.
 */
import { Client, ReconnectionTimeMode, type StompSubscription } from '@stomp/stompjs'
import { CSRF_HEADER, CSRF_PATH, readCsrfToken } from '../api/csrf'
import { type DriverHooks, type RealtimeDriver, RealtimeHub } from './hub'

export function brokerUrl(location: Pick<Location, 'protocol' | 'host'> = window.location): string {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`
}

export const RECONNECT_DELAY_MS = 1_000
export const MAX_RECONNECT_DELAY_MS = 30_000

export class StompDriver implements RealtimeDriver {
  private client: Client | null = null
  private readonly subscriptions = new Map<string, StompSubscription>()

  constructor(private readonly url: string = brokerUrl()) {}

  start(hooks: DriverHooks): void {
    const client = new Client({
      brokerURL: this.url,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      // stompjs schedules reconnects from its own delay (copied from reconnectDelay on activate and after each
      // CONNECT), so the backoff must be the library's: 1 s, 2 s, 4 s … capped at 30 s.
      reconnectDelay: RECONNECT_DELAY_MS,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: MAX_RECONNECT_DELAY_MS,
      // Called before every (re)connect: make sure the CSRF cookie exists and send its raw value.
      beforeConnect: async () => {
        if (!readCsrfToken()) {
          await fetch(CSRF_PATH, { credentials: 'same-origin' }).catch(() => undefined)
        }
        const token = readCsrfToken()
        client.connectHeaders = token ? { [CSRF_HEADER]: token } : {}
      },
      onConnect: () => {
        this.subscriptions.clear()
        hooks.onConnected()
      },
      onWebSocketClose: () => {
        this.subscriptions.clear()
        hooks.onDisconnected()
      },
      onStompError: () => {
        // e.g. CSRF rejected: the socket closes and the client reconnects with a fresh token.
      },
    })
    this.client = client
    this.onMessage = hooks.onMessage
    client.activate()
  }

  private onMessage: DriverHooks['onMessage'] = () => {}

  stop(): void {
    void this.client?.deactivate()
    this.client = null
    this.subscriptions.clear()
  }

  subscribe(destination: string): void {
    const client = this.client
    if (!client?.connected || this.subscriptions.has(destination)) return
    const sub = client.subscribe(destination, (message) => {
      let body: unknown = message.body
      try {
        body = JSON.parse(message.body)
      } catch {
        /* non-JSON payloads are passed through as text */
      }
      this.onMessage(destination, body)
    })
    this.subscriptions.set(destination, sub)
  }

  unsubscribe(destination: string): void {
    this.subscriptions.get(destination)?.unsubscribe()
    this.subscriptions.delete(destination)
  }
}

let hub: RealtimeHub | null = null

function createDriver(): RealtimeDriver {
  if (import.meta.env.VITE_API_MOCK === '1') {
    // Lazy proxy: the mock module loads on first use, keeping it out of the main chunk.
    let inner: RealtimeDriver | null = null
    const pending: Array<(d: RealtimeDriver) => void> = []
    void import('../api/mock/realtime').then(({ createMockDriver }) => {
      inner = createMockDriver()
      for (const p of pending.splice(0)) p(inner)
    })
    const withDriver = (fn: (d: RealtimeDriver) => void) => (inner ? fn(inner) : pending.push(fn))
    return {
      start: (hooks) => withDriver((d) => d.start(hooks)),
      stop: () => withDriver((d) => d.stop()),
      subscribe: (dest) => withDriver((d) => d.subscribe(dest)),
      unsubscribe: (dest) => withDriver((d) => d.unsubscribe(dest)),
    }
  }
  return new StompDriver()
}

/** The app-wide realtime hub (created on first use; connects on the first subscription). */
export function realtime(): RealtimeHub {
  if (!hub) {
    hub = new RealtimeHub(createDriver())
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') hub?.resync()
      })
    }
  }
  return hub
}

/**
 * Ends the realtime connection of the signed-in session (sign-out, or a 401 from the API): closes the socket and
 * drops every subscription, so nothing keeps reconnecting on /login and the next sign-in opens a fresh STOMP
 * session for the new player. A no-op when the hub was never created.
 */
export function stopRealtime(): void {
  hub?.stop()
}

/** Test seam: replace the singleton. */
export function setRealtimeForTests(next: RealtimeHub | null): void {
  hub = next
}
