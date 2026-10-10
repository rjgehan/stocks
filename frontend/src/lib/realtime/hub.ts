/**
 * Transport-independent subscription hub (§19, §22.6).
 *
 * - Reference counting: many components may subscribe to the same destination (e.g. two rows showing
 *   AAPL); the server sees ONE subscription, opened on the first subscriber and closed after the last.
 * - Resubscribe: after a (re)connect every live destination is subscribed again.
 * - Resync: on reconnect, and when the tab becomes visible again, registered callbacks run (the app
 *   invalidates ['account'], ['positions'], ['orders','OPEN'] and ['leaderboard']). Missed messages are
 *   therefore harmless; there is no durable outbox.
 * - Lifetime: the connection belongs to one signed-in session. STOMP authenticates once, at the handshake, so
 *   sign-out and an expired session call `stop()`: it closes the socket (no reconnect loop on /login) and forgets
 *   every handler, so the next sign-in starts a fresh CONNECT under the new player instead of reusing the old one.
 */
export type ConnectionStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting'

export interface DriverHooks {
  onMessage(destination: string, body: unknown): void
  onConnected(): void
  onDisconnected(): void
}

/** The wire: real STOMP (stomp.ts) or the mock-mode fake (lib/api/mock/realtime.ts). */
export interface RealtimeDriver {
  start(hooks: DriverHooks): void
  stop(): void
  subscribe(destination: string): void
  unsubscribe(destination: string): void
}

export type MessageHandler = (body: unknown) => void

export class RealtimeHub {
  private readonly handlers = new Map<string, Set<MessageHandler>>()
  private readonly statusListeners = new Set<(s: ConnectionStatus) => void>()
  private readonly resyncListeners = new Set<() => void>()
  private status: ConnectionStatus = 'idle'
  private connected = false
  private everConnected = false
  private started = false

  constructor(private readonly driver: RealtimeDriver) {}

  /** Opens the connection lazily on the first subscription. */
  start(): void {
    if (this.started) return
    this.started = true
    this.setStatus('connecting')
    this.driver.start({
      onMessage: (destination, body) => {
        const set = this.handlers.get(destination)
        if (!set) return
        for (const h of set) h(body)
      },
      onConnected: () => {
        this.connected = true
        this.setStatus('connected')
        for (const destination of this.handlers.keys()) this.driver.subscribe(destination)
        if (this.everConnected) this.resync()
        this.everConnected = true
      },
      onDisconnected: () => {
        this.connected = false
        if (this.started) this.setStatus('reconnecting')
      },
    })
  }

  /**
   * Closes the connection and forgets every subscription and resync callback (sign-out, 401). Unsubscribe
   * functions handed out before are then no-ops; a later `subscribe` opens a new connection.
   */
  stop(): void {
    this.handlers.clear()
    this.resyncListeners.clear()
    this.everConnected = false
    if (!this.started) return
    this.started = false
    this.connected = false
    this.driver.stop()
    this.setStatus('idle')
  }

  subscribe(destination: string, handler: MessageHandler): () => void {
    let existing = this.handlers.get(destination)
    if (!existing) {
      existing = new Set()
      this.handlers.set(destination, existing)
      if (this.connected) this.driver.subscribe(destination)
    }
    const set = existing
    set.add(handler)
    this.start()
    let active = true
    return () => {
      if (!active) return
      active = false
      // stop() dropped this set; a newer subscription may own the destination now
      if (this.handlers.get(destination) !== set) return
      set.delete(handler)
      if (set.size === 0) {
        this.handlers.delete(destination)
        if (this.connected) this.driver.unsubscribe(destination)
      }
    }
  }

  /** Number of local subscribers for a destination (0 = no server subscription). */
  refCount(destination: string): number {
    return this.handlers.get(destination)?.size ?? 0
  }

  getStatus(): ConnectionStatus {
    return this.status
  }

  onStatus(listener: (s: ConnectionStatus) => void): () => void {
    this.statusListeners.add(listener)
    return () => this.statusListeners.delete(listener)
  }

  onResync(listener: () => void): () => void {
    this.resyncListeners.add(listener)
    return () => this.resyncListeners.delete(listener)
  }

  /** Run resync callbacks (reconnect, or the page became visible again). */
  resync(): void {
    for (const l of this.resyncListeners) l()
  }

  private setStatus(s: ConnectionStatus): void {
    if (s === this.status) return
    this.status = s
    for (const l of this.statusListeners) l(s)
  }
}
