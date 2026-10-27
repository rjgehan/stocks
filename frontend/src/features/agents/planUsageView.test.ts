import { describe, expect, it } from 'vitest'
import type { PlanUsage } from '@/lib/api/types'
import { change, changeText, days, latest, rounds } from './planUsageView'

const S_RESET = '2026-10-27T18:30:00Z'
const W_RESET = '2026-10-29T02:00:00Z'

function snap(over: Partial<PlanUsage>): PlanUsage {
  return {
    botHandle: 'claude',
    kind: 'CHECKIN_AFTER',
    source: 'USAGE',
    takenAt: '2026-10-27T13:30:00Z',
    tradeDate: '2026-10-27',
    roundRef: 'round-1',
    runRef: null,
    sessionPercent: '10.0',
    sessionResetsAt: S_RESET,
    weeklyPercent: '80.0',
    weeklyResetsAt: W_RESET,
    tokens: null,
    costUsd: null,
    ...over,
  }
}

// newest first, as the API sends them
const ROWS: PlanUsage[] = [
  snap({ kind: 'ROUND_AFTER', takenAt: '2026-10-27T13:36:00Z', sessionPercent: '16.0', weeklyPercent: '81.5' }),
  snap({ kind: 'CHECKIN_AFTER', botHandle: 'tech', runRef: 'tech-1', takenAt: '2026-10-27T13:35:00Z', sessionPercent: '16.0', weeklyPercent: '81.5', tokens: { input: 1, cacheWrite: 10, cacheRead: 20, output: 5 }, costUsd: '0.2000' }),
  snap({ kind: 'CHECKIN_AFTER', runRef: 'claude-1', takenAt: '2026-10-27T13:33:00Z', sessionPercent: '13.0', weeklyPercent: '80.8', costUsd: '0.1500' }),
  snap({ kind: 'CHECKIN_BEFORE', botHandle: 'tech', runRef: 'tech-1', takenAt: '2026-10-27T13:30:05Z' }),
  snap({ kind: 'CHECKIN_BEFORE', runRef: 'claude-1', takenAt: '2026-10-27T13:30:04Z' }),
  snap({ kind: 'ROUND_BEFORE', takenAt: '2026-10-27T13:30:00Z' }),
  // the day before: one round
  snap({ kind: 'ROUND_AFTER', roundRef: 'round-0', tradeDate: '2026-10-26', takenAt: '2026-10-26T19:55:00Z', weeklyPercent: '78.0' }),
  snap({ kind: 'CHECKIN_AFTER', roundRef: 'round-0', runRef: 'claude-0', tradeDate: '2026-10-26', takenAt: '2026-10-26T19:54:00Z', weeklyPercent: '78.0', costUsd: '0.1000' }),
  snap({ kind: 'ROUND_BEFORE', roundRef: 'round-0', tradeDate: '2026-10-26', takenAt: '2026-10-26T19:50:00Z', weeklyPercent: '77.0' }),
]

describe('the Claude plan panel numbers', () => {
  it('reads the latest meters', () => {
    expect(latest(ROWS)).toEqual({ at: '2026-10-27T13:36:00Z', session: { percent: 16, resetsAt: S_RESET }, weekly: { percent: 81.5, resetsAt: W_RESET } })
    expect(latest([])).toBeNull()
  })

  it('groups rounds, newest first, each with its check-ins', () => {
    const [today, yesterday] = rounds(ROWS)
    expect(today!.ref).toBe('round-1')
    expect(today!.session).toEqual({ before: 10, after: 16, delta: 6, reset: false })
    expect(today!.weekly.delta).toBe(1.5)
    expect(today!.checkIns.map((c) => [c.bot, c.session.delta, c.weekly.delta])).toEqual([
      ['claude', 3, 0.8],
      ['tech', 6, 1.5],
    ])
    expect(today!.checkIns[1]!.tokens).toEqual({ input: 1, cacheWrite: 10, cacheRead: 20, output: 5 })
    expect(today!.costUsd).toBeCloseTo(0.35)
    expect(yesterday!.weekly).toEqual({ before: 77, after: 78, delta: 1, reset: false })
  })

  it('a round still running has no after', () => {
    const running = rounds(ROWS.filter((s) => s.kind !== 'ROUND_AFTER' || s.roundRef !== 'round-1'))[0]!
    expect(running.endedAt).toBeNull()
    expect(changeText(running.session, true)).toBe('10% → …')
  })

  it('sums days by the weekly meter', () => {
    expect(days(ROWS)).toEqual([
      { date: '2026-10-27', weekly: { before: 80, after: 81.5, delta: 1.5, reset: false }, checkIns: 2, costUsd: expect.closeTo(0.35) },
      { date: '2026-10-26', weekly: { before: 77, after: 78, delta: 1, reset: false }, checkIns: 1, costUsd: expect.closeTo(0.1) },
    ])
  })

  it('a window that reset in between has no delta', () => {
    const c = change({ percent: 95, resetsAt: S_RESET }, { percent: 3, resetsAt: '2026-10-27T23:30:00Z' })
    expect(c).toEqual({ before: 95, after: 3, delta: null, reset: true })
    expect(changeText(c)).toBe('95% → 3% (reset)')
    expect(changeText({ before: 10, after: 12.5, delta: 2.5, reset: false })).toBe('10% → 12.5% (+2.5)')
    expect(changeText({ before: 10, after: 10, delta: 0, reset: false })).toBe('10% → 10% (±0)')
  })
})
