import { afterEach, describe, expect, it, vi } from 'vitest'
import type * as Stomp from '@stomp/stompjs'

const created: Array<Record<string, unknown> & { activate: () => void; deactivate: () => Promise<void> }> = []

vi.mock('@stomp/stompjs', async (importOriginal) => {
  const actual = await importOriginal<typeof Stomp>()
  class FakeClient {
    connectHeaders: Record<string, string> = {}
    activate = vi.fn<() => void>()
    deactivate = vi.fn<() => Promise<void>>(async () => undefined)
    constructor(config: Record<string, unknown>) {
      Object.assign(this, config)
      created.push(this as unknown as (typeof created)[number])
    }
  }
  return { ...actual, Client: FakeClient }
})

const { ReconnectionTimeMode } = await import('@stomp/stompjs')
const { MAX_RECONNECT_DELAY_MS, RECONNECT_DELAY_MS, StompDriver } = await import('./stomp')

describe('StompDriver reconnects (§19)', () => {
  afterEach(() => {
    created.length = 0
  })

  it("uses the library's exponential backoff, 1 s doubling to 30 s", async () => {
    const driver = new StompDriver('ws://example.test/ws')
    driver.start({ onMessage: () => undefined, onConnected: () => undefined, onDisconnected: () => undefined })
    const client = created[0]!
    expect(client.reconnectDelay).toBe(RECONNECT_DELAY_MS)
    expect(RECONNECT_DELAY_MS).toBe(1_000)
    expect(client.reconnectTimeMode).toBe(ReconnectionTimeMode.EXPONENTIAL)
    expect(client.maxReconnectDelay).toBe(MAX_RECONNECT_DELAY_MS)
    expect(MAX_RECONNECT_DELAY_MS).toBe(30_000)
    expect(client.activate).toHaveBeenCalledTimes(1)

    // beforeConnect only refreshes the CSRF header (the raw cookie value); it no longer touches the delay
    document.cookie = 'XSRF-TOKEN=raw-token; path=/'
    await (client.beforeConnect as () => Promise<void>)()
    expect(client.connectHeaders).toEqual({ 'X-XSRF-TOKEN': 'raw-token' })
    expect(client.reconnectDelay).toBe(RECONNECT_DELAY_MS)

    driver.stop()
    expect(client.deactivate).toHaveBeenCalledTimes(1)
  })
})
