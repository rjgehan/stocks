import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { DriverHooks, RealtimeDriver } from '../../realtime/hub'
import { RealtimeHub } from '../../realtime/hub'
import { setRealtimeForTests } from '../../realtime/stomp'
import type { FeedItem } from '../types'
import { LIVE_REFRESH_THROTTLE_MS, usePlayerLiveRefresh } from './league'

let hooks: DriverHooks | null = null
const driver: RealtimeDriver = {
  start: (h) => {
    hooks = h
  },
  stop: () => {},
  subscribe: () => {},
  unsubscribe: () => {},
}

const item = (handle: string, kind: FeedItem['kind'] = 'FILL'): FeedItem =>
  ({ id: `fde_${Math.random()}`, at: '2026-10-13T15:00:00Z', kind, player: { handle }, symbol: 'NVDA', payload: null, reactions: [] }) as unknown as FeedItem

function setup() {
  const qc = new QueryClient()
  const spy = vi.spyOn(qc, 'invalidateQueries')
  const { unmount } = renderHook(() => usePlayerLiveRefresh('claude'), { wrapper: ({ children }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider> })
  hooks!.onConnected()
  const send = (body: unknown) => hooks!.onMessage('/topic/feed', body)
  const keys = () => spy.mock.calls.map(([f]) => JSON.stringify(f?.queryKey) + (f?.exact ? ' exact' : ''))
  return { spy, send, keys, unmount }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
  vi.setSystemTime(Date.parse('2026-10-13T15:00:00Z'))
  setRealtimeForTests(new RealtimeHub(driver))
})

afterEach(() => {
  setRealtimeForTests(null)
  vi.useRealTimers()
})

describe('usePlayerLiveRefresh (public mode: no topic of its own, §19)', () => {
  it("refreshes the open profile's live keys on this player's feed item, never the stats or history ranges", () => {
    const { send, keys } = setup()
    send(item('claude'))
    expect(keys()).toEqual([
      '["player","claude"] exact',
      '["player","claude","trades"] exact',
      '["player","claude","portfolio"] exact',
      '["player","claude","orders"]',
      '["player","claude","activity"]',
    ])
    expect(keys().some((k) => k.includes('"stats"') || k.includes('"history"') || k === '["player","claude"]')).toBe(false)
  })

  it('ignores reaction updates and other players', () => {
    const { spy, send } = setup()
    send({ itemId: 'fde_1', reactions: [] })
    send(item('alice'))
    expect(spy).not.toHaveBeenCalled()
  })

  it('refreshes at once, then coalesces the items of the next 2 s into one trailing refresh (with the journal on a note)', () => {
    const { spy, send, keys } = setup()
    send(item('claude'))
    expect(spy).toHaveBeenCalledTimes(5)
    vi.advanceTimersByTime(300)
    send(item('claude', 'JOURNAL')) // the note a fill's request posts a few hundred ms later
    vi.advanceTimersByTime(500)
    send(item('claude'))
    expect(spy).toHaveBeenCalledTimes(5)
    vi.advanceTimersByTime(LIVE_REFRESH_THROTTLE_MS - 800)
    expect(spy).toHaveBeenCalledTimes(11)
    expect(keys().at(-1)).toBe('["player","claude","journal"] exact')
    vi.advanceTimersByTime(LIVE_REFRESH_THROTTLE_MS * 2)
    expect(spy).toHaveBeenCalledTimes(11) // nothing pending
    send(item('claude')) // after a quiet window: at once again, without the journal
    expect(spy).toHaveBeenCalledTimes(16)
    expect(keys().at(-1)).toBe('["player","claude","activity"]')
  })

  it('drops a pending refresh on unmount', () => {
    const { spy, send, unmount } = setup()
    send(item('claude'))
    send(item('claude', 'JOURNAL'))
    unmount()
    vi.advanceTimersByTime(LIVE_REFRESH_THROTTLE_MS * 2)
    expect(spy).toHaveBeenCalledTimes(5)
  })
})
