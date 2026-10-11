import { QueryClient } from '@tanstack/react-query'
import { afterEach, describe, expect, it } from 'vitest'
import { applyFeedReactions, isFeedReactions } from '@/lib/api/hooks/stats'
import { groupReactions, mockPnlCalendar, mockRace, setReaction } from '@/lib/api/mock/stats'
import { queryKeys } from '@/lib/api/queryKeys'
import type { FeedItem, FeedReaction, Leaderboard, PnlCalendarCell, RaceSeries, Reactor } from '@/lib/api/types'
import { symbolBars } from './bySymbol'
import { HEAT_CLAMP, etMonth, heatColor, heatIsStrong, monthGrid, monthLabel, shiftMonth } from './calendar'
import { loadColumns, loadPhoneStat, saveColumns, savePhoneStat, toggleColumn } from './columns'
import { formatHold, lastRankChange, profitFactorLabel, sharpeLabel } from './format'
import { plotPoints, raceStyles, shade } from './race'
import { myReaction, reactorsLabel, toggleReaction } from './reactions'

const bob: Reactor = { handle: 'bob', displayName: 'Bob', kind: 'HUMAN' }
const claude: Reactor = { handle: 'claude', displayName: 'Claude', kind: 'AI' }
const me: Reactor = { handle: 'demo', displayName: 'Demo', kind: 'HUMAN' }
const who = (h: string): Reactor | null => (h === 'gone' ? null : { handle: h, displayName: h, kind: 'HUMAN' })
const series = (handle: string, kind: 'HUMAN' | 'AI'): RaceSeries =>
  ({ player: { handle, kind }, rank: 1, return: '0.01', rebased: false, partialPeriod: false, points: [], ranks: [] }) as unknown as RaceSeries

describe('league stats formatting (§16.8)', () => {
  it('formats hold times', () => {
    expect(formatHold(null)).toBe('--')
    expect(formatHold(30)).toBe('<1m')
    expect(formatHold(45 * 60)).toBe('45m')
    expect(formatHold(3 * 3600)).toBe('3h')
    expect(formatHold(3 * 3600 + 20 * 60)).toBe('3h 20m')
    expect(formatHold(2 * 86_400 + 4 * 3600)).toBe('2d 4h')
    expect(formatHold(12 * 86_400 + 4 * 3600)).toBe('12d')
  })

  it('shows ∞ for a profit factor without a losing sell and -- without sells', () => {
    expect(profitFactorLabel('3.5853', '44.96', 2)).toBe('3.59')
    expect(profitFactorLabel(null, '24.00', 1)).toBe('∞')
    expect(profitFactorLabel(null, '0.00', 1)).toBe('--')
    expect(profitFactorLabel(null, '0.00', 0)).toBe('--')
    expect(profitFactorLabel('0.0000', '0.00', 1)).toBe('0.00')
  })

  it('explains a missing Sharpe ratio', () => {
    expect(sharpeLabel('6.9135', 24, 20)).toEqual({ value: '6.91', note: null })
    expect(sharpeLabel('-0.4000', 24, 20).value).toBe('−0.40')
    expect(sharpeLabel(null, 12, 20)).toEqual({ value: '--', note: 'needs 20 days · 12/20' })
    expect(sharpeLabel(null, 25, 20)).toEqual({ value: '--', note: 'no variation yet' })
  })

  it('compares ranks: positive is up', () => {
    expect(lastRankChange([{ rank: 3 }, { rank: 2 }])).toBe(1)
    expect(lastRankChange([{ rank: 1 }, { rank: null }, { rank: 4 }])).toBe(-3)
    expect(lastRankChange([{ rank: 4 }], 2)).toBe(2)
    expect(lastRankChange([{ rank: 4 }])).toBeNull()
    expect(lastRankChange(null, 1)).toBeNull()
  })
})

describe('feed reactions (§16.11)', () => {
  const start: FeedReaction[] = [
    { reaction: 'ROCKET', count: 2, players: [bob, claude] },
    { reaction: 'SKULL', count: 1, players: [me] },
  ]

  it('moves my reaction to another emoji, keeps the fixed order and drops empty emojis', () => {
    expect(myReaction(start, 'demo')).toBe('SKULL')
    const { next, set } = toggleReaction(start, me, 'FIRE')
    expect(set).toBe('FIRE')
    expect(next.map((r) => r.reaction)).toEqual(['ROCKET', 'FIRE'])
    expect(next[1]).toEqual({ reaction: 'FIRE', count: 1, players: [me] })
  })

  it('removes mine when I pick it again, and joins the end of a list', () => {
    expect(toggleReaction(start, me, 'SKULL')).toEqual({ next: [start[0]], set: null })
    const joined = toggleReaction(start, me, 'ROCKET')
    expect(joined.next[0]!.players!.map((p) => p.handle)).toEqual(['bob', 'claude', 'demo'])
    expect(joined.next[0]!.count).toBe(3)
    expect(toggleReaction(null, me, 'GEM').next).toEqual([{ reaction: 'GEM', count: 1, players: [me] }])
  })

  it('names who reacted', () => {
    expect(reactorsLabel([bob])).toBe('Bob')
    expect(reactorsLabel([bob, claude])).toBe('Bob and Claude')
    expect(reactorsLabel([bob, claude, me, { handle: 'x', displayName: 'X', kind: 'HUMAN' }, { handle: 'y', displayName: null, kind: 'AI' }])).toBe('Bob, Claude, Demo and 2 others')
  })

  it('updates the cached feed item in place from a /topic/feed FeedReactions message', () => {
    const qc = new QueryClient()
    const item = { id: 'fde_1', at: '2026-10-09T15:00:00Z', kind: 'FILL', player: null, symbol: 'NVDA', payload: null, reactions: [] } as FeedItem
    expect(applyFeedReactions(qc, 'fde_1', start)).toBe(false)
    qc.setQueryData(queryKeys.feed, { pages: [{ items: [item], nextCursor: null }], pageParams: [undefined] })
    expect(applyFeedReactions(qc, 'fde_1', start)).toBe(true)
    const data = qc.getQueryData<{ pages: Array<{ items: FeedItem[] }> }>(queryKeys.feed)!
    expect(data.pages[0]!.items).toHaveLength(1)
    expect(data.pages[0]!.items[0]!.reactions).toEqual(start)
    expect(isFeedReactions({ itemId: 'fde_1', reactions: [] })).toBe(true)
    expect(isFeedReactions(item)).toBe(false)
    expect(isFeedReactions(null)).toBe(false)
  })

  it('mock mode keeps one reaction per player, oldest first', () => {
    let rows = setReaction(undefined, 'bob', 'ROCKET', 1)
    rows = setReaction(rows, 'demo', 'ROCKET', 2)
    rows = setReaction(rows, 'bob', 'ROCKET', 3) // same emoji: keeps its time
    rows = setReaction(rows, 'claude', 'SKULL', 4)
    expect(groupReactions(rows, who).map((r) => [r.reaction, r.players?.map((p) => p.handle)])).toEqual([
      ['ROCKET', ['bob', 'demo']],
      ['SKULL', ['claude']],
    ])
    rows = setReaction(rows, 'demo', null, 5)
    expect(groupReactions(rows, who)[0]!.count).toBe(1)
    expect(groupReactions([{ handle: 'gone', reaction: 'GEM', at: 1 }], who)).toEqual([])
  })
})

describe('P&L calendar (§16.10)', () => {
  it('lays a month out Mon–Fri with blanks before the 1st', () => {
    const cells: PnlCalendarCell[] = [{ date: '2026-10-05', pnl: '20.00', return: '0.02', flows: '0.00', live: false }]
    const weeks = monthGrid('2026-10', cells) // Oct 1 2026 is a Thursday
    expect(weeks[0]!.map((d) => d?.date ?? null)).toEqual([null, null, null, '2026-10-01', '2026-10-02'])
    expect(weeks[1]![0]).toEqual({ date: '2026-10-05', cell: cells[0] })
    expect(weeks.flat().filter(Boolean)).toHaveLength(22) // trading weekdays of October 2026
    expect(weeks.every((w) => w.length === 5)).toBe(true)
    const feb = monthGrid('2026-02', []) // Feb 1 2026 is a Sunday
    expect(feb[0]![0]!.date).toBe('2026-02-02')
  })

  it('navigates months and names them', () => {
    expect(shiftMonth('2026-10', 1)).toBe('2026-11')
    expect(shiftMonth('2026-12', 1)).toBe('2027-01')
    expect(shiftMonth('2026-01', -1)).toBe('2025-12')
    expect(monthLabel('2026-10')).toBe('October 2026')
    expect(etMonth(Date.parse('2026-11-01T02:00:00Z'))).toBe('2026-10') // still Oct 31 in New York
  })

  it('colors by the P&L sign, clamps at ±3% and never colors a flat day', () => {
    expect(heatColor('0.00', '0')).toBe('var(--bg-2)')
    expect(heatColor(null, null)).toBe('var(--bg-2)')
    expect(heatColor('12.00', '0.06')).toBe('color-mix(in srgb, var(--up-strong) 90%, var(--bg-3))')
    expect(heatColor('-5.00', '-0.015')).toBe('color-mix(in srgb, var(--down-strong) 54%, var(--bg-3))')
    expect(heatColor('0.01', '0')).toContain('18%')
    expect(HEAT_CLAMP).toBe(0.03)
    expect(heatIsStrong('0.02')).toBe(true)
    expect(heatIsStrong('0.01')).toBe(false)
  })

  it('mock calendar: deposits are flows, today is live, months sum their days', () => {
    const now = Date.parse('2026-10-14T16:00:00Z')
    const day = mockPnlCalendar('DAY', '2026-10', 2026, { openedAtMs: Date.parse('2026-10-05T14:00:00Z'), dayPnl: '1.23', dayReturn: '0.002', deposits: [{ date: '2026-10-07', amount: '100.00' }] }, now)
    expect(day.cells!.map((c) => c.date)).toEqual(['2026-10-05', '2026-10-06', '2026-10-07', '2026-10-08', '2026-10-09', '2026-10-12', '2026-10-13', '2026-10-14'])
    expect(day.cells!.at(-1)).toMatchObject({ pnl: '1.23', live: true })
    expect(day.cells!.find((c) => c.date === '2026-10-07')!.flows).toBe('100.00')
    const year = mockPnlCalendar('MONTH', '2026-10', 2026, { openedAtMs: Date.parse('2026-10-05T14:00:00Z'), dayPnl: '1.23', dayReturn: '0.002', deposits: [] }, now)
    expect(year.cells).toHaveLength(1)
    expect(year.cells![0]!.pnl).toBe(day.total!.pnl)
  })
})

describe('race chart (§16.9)', () => {
  it('gives humans and AI players their own shade families and the viewer a thicker line', () => {
    const styles = raceStyles([series('a', 'HUMAN'), series('claude', 'AI'), series('demo', 'HUMAN')], '#4c82ff', '#a78bfa', 'demo')
    expect(styles[0]!.color).toBe('#4c82ff')
    expect(styles[1]!.color).toBe('#a78bfa')
    expect(styles[2]!.color).toBe(shade('#4c82ff', 0.35))
    expect(styles.map((s) => s.width)).toEqual([2, 2, 3])
    expect(shade('#000000', 0.5)).toBe('#808080')
    expect(shade('#ffffff', -1)).toBe('#000000')
    expect(shade('rgb(1,2,3)', 0.5)).toBe('rgb(1,2,3)')
  })

  it('plots strictly increasing times and skips gaps', () => {
    expect(plotPoints([{ t: '2026-10-09T14:00:00Z', return: '0' }, { t: '2026-10-09T14:00:00Z', return: '0.1' }, { t: null, return: '0.2' }, { t: '2026-10-09T15:00:00Z', return: '-0.0123' }])).toEqual([
      { time: Date.parse('2026-10-09T14:00:00Z') / 1000, value: 0 },
      { time: Date.parse('2026-10-09T15:00:00Z') / 1000, value: -0.0123 },
    ])
  })

  it('mock race lines end at each row return', () => {
    const board = {
      period: 'WEEK',
      kind: 'ALL',
      benchmark: { symbol: 'SPY', return: '0.0080' },
      rows: [{ rank: 1, return: '0.0420', rebased: false, partialPeriod: false, player: { handle: 'claude', kind: 'AI' } }],
    } as unknown as Leaderboard
    const race = mockRace(board, Date.parse('2026-10-09T18:00:00Z'))
    expect(race.series![0]!.points!.at(-1)!.return).toBe('0.0420')
    expect(race.series![0]!.points![0]!.return).toBe('0.0000000000')
    expect(race.benchmark!.points!.at(-1)!.return).toBe('0.0080000000')
  })
})

describe('leaderboard columns (§22.4 Phase 2)', () => {
  afterEach(() => localStorage.clear())

  it('remembers the chosen columns in canonical order', () => {
    expect(loadColumns()).toEqual([])
    const c = toggleColumn(toggleColumn([], 'WIN_RATE'), 'SHARPE')
    expect(c).toEqual(['SHARPE', 'WIN_RATE'])
    saveColumns(c)
    expect(loadColumns()).toEqual(['SHARPE', 'WIN_RATE'])
    expect(toggleColumn(c, 'SHARPE')).toEqual(['WIN_RATE'])
    localStorage.setItem('stocks.league.columns', '["BOGUS","MAX_DRAWDOWN"]')
    expect(loadColumns()).toEqual(['MAX_DRAWDOWN'])
    localStorage.setItem('stocks.league.columns', '{not json')
    expect(loadColumns()).toEqual([])
    savePhoneStat('PROFIT_FACTOR')
    expect(loadPhoneStat()).toBe('PROFIT_FACTOR')
    savePhoneStat(null)
    expect(loadPhoneStat()).toBeNull()
  })
})

describe('P&L by symbol (§16.10)', () => {
  it('scales bars to the largest move and adds Other for corrections', () => {
    const bars = symbolBars(
      [
        { symbol: 'AAA', realized: '20.00', unrealized: '36.00', dividends: '0.00', total: '56.00', held: true },
        { symbol: 'CCC', realized: '0.00', unrealized: '-30.00', dividends: '2.00', total: '-28.00', held: true },
      ],
      '5.00',
    )
    expect(bars.map((b) => [b.symbol, b.positive, b.width])).toEqual([
      ['AAA', true, 1],
      ['CCC', false, 0.5],
      ['Other', true, 5 / 56],
    ])
    expect(symbolBars([], '0.00')).toEqual([])
    expect(symbolBars(null, null)).toEqual([])
  })
})
