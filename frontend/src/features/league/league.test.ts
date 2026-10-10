import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { prependFeedItem } from '@/lib/api/hooks/league'
import { queryKeys } from '@/lib/api/queryKeys'
import type { FeedItem } from '@/lib/api/types'
import { arenaHeadline, pointsGap } from './ArenaCard'
import { FEED_FILTER_KINDS, feedDayLabel } from './FeedList'

const item = (id: string, kind: FeedItem['kind'] = 'FILL'): FeedItem => ({ id, at: '2026-10-09T15:00:00Z', kind, player: null, symbol: 'NVDA', payload: null }) as FeedItem

describe('Arena (§22.4)', () => {
  it('says who leads, in percentage points, without floating point', () => {
    expect(arenaHeadline('0.0036', '0.0010')).toBe('Humans lead by 0.26 pts')
    expect(arenaHeadline('-0.0120', '0.0035')).toBe('AI leads by 1.55 pts')
    expect(arenaHeadline('0.01000', '0.0100')).toBe('Dead even')
    expect(arenaHeadline('0.0100', '0.01004')).toBe('Dead even') // 0.004 pts rounds to 0.00
    expect(arenaHeadline(null, '0.01')).toBe('Waiting for both teams')
    expect(arenaHeadline(null, null)).toBe('No players yet')
    expect(pointsGap('0.1', '-0.05')).toBe('15.00 pts')
  })
})

describe('league feed (§19 /topic/feed)', () => {
  it('prepends a live item once, and only when the feed is loaded', () => {
    const qc = new QueryClient()
    expect(prependFeedItem(qc, item('fde_1'))).toBe(false) // nothing cached yet: the next fetch has it
    qc.setQueryData(queryKeys.feed, { pages: [{ items: [item('fde_0')], nextCursor: null }], pageParams: [undefined] })
    expect(prependFeedItem(qc, item('fde_1'))).toBe(true)
    expect(prependFeedItem(qc, item('fde_1'))).toBe(false) // a re-delivery
    const data = qc.getQueryData<{ pages: Array<{ items: FeedItem[] }> }>(queryKeys.feed)!
    expect(data.pages[0]!.items.map((i) => i.id)).toEqual(['fde_1', 'fde_0'])
  })

  it('groups by ET day and filters by kind', () => {
    const now = Date.parse('2026-10-09T18:00:00Z') // 14:00 ET
    expect(feedDayLabel('2026-10-09T13:00:00Z', now)).toBe('Today')
    expect(feedDayLabel('2026-10-09T03:00:00Z', now)).toBe('Yesterday') // 23:00 ET the day before
    expect(feedDayLabel('2026-10-06T15:00:00Z', now)).toBe('Tue, Oct 6')
    expect(FEED_FILTER_KINDS.TRADES).toEqual(['FILL', 'POSITION_CLOSED'])
    expect(FEED_FILTER_KINDS.ALL).toBeNull()
  })
})
