/**
 * The Claude plan panel's numbers (Admin → Bots): the claude-trader reads the owner's plan meters (the 5-hour session
 * and the weekly limit) before and after each check-in and each round of check-ins. These helpers turn the snapshots
 * (newest first, as the API sends them) into the latest reading, rounds with their check-ins, and days.
 */
import type { PlanUsage } from '@/lib/api/types'

/** One meter at one moment: percent 0-100 and when its window resets. */
export interface Meter {
  percent: number | null
  resetsAt: string | null
}

export interface Reading {
  at: string
  session: Meter
  weekly: Meter
}

/** before → after of one meter; `reset` when the window rolled over in between (the change means nothing then). */
export interface Change {
  before: number | null
  after: number | null
  delta: number | null
  reset: boolean
}

export interface CheckIn {
  bot: string
  runRef: string
  at: string
  session: Change
  weekly: Change
  tokens: { input: number; cacheWrite: number; cacheRead: number; output: number } | null
  costUsd: number | null
}

export interface Round {
  ref: string
  startedAt: string
  endedAt: string | null
  session: Change
  weekly: Change
  checkIns: CheckIn[]
  costUsd: number
}

export interface Day {
  date: string
  weekly: Change
  checkIns: number
  costUsd: number
}

const num = (v: string | number | null | undefined): number | null => (v == null || v === '' ? null : Number(v))

const reading = (s: PlanUsage): Reading => ({
  at: s.takenAt ?? '',
  session: { percent: num(s.sessionPercent), resetsAt: s.sessionResetsAt ?? null },
  weekly: { percent: num(s.weeklyPercent), resetsAt: s.weeklyResetsAt ?? null },
})

/** before → after; a different reset time means the window rolled over in between. */
export function change(before: Meter | null | undefined, after: Meter | null | undefined): Change {
  const b = before?.percent ?? null
  const a = after?.percent ?? null
  const reset = Boolean(before?.resetsAt && after?.resetsAt && before.resetsAt !== after.resetsAt)
  return { before: b, after: a, delta: b != null && a != null && !reset ? Math.round((a - b) * 10) / 10 : null, reset }
}

const byTime = (a: PlanUsage, b: PlanUsage) => (a.takenAt ?? '').localeCompare(b.takenAt ?? '')

/** The newest reading of either meter. */
export function latest(rows: readonly PlanUsage[]): Reading | null {
  const newest = [...rows].sort(byTime).at(-1)
  return newest ? reading(newest) : null
}

/** Rounds, newest first: each with its before/after and its check-ins (each with its own before/after). */
export function rounds(rows: readonly PlanUsage[]): Round[] {
  const groups = new Map<string, PlanUsage[]>()
  for (const s of rows) {
    if (!s.roundRef) continue
    const g = groups.get(s.roundRef) ?? []
    g.push(s)
    groups.set(s.roundRef, g)
  }
  const out: Round[] = []
  for (const [ref, list] of groups) {
    const sorted = [...list].sort(byTime)
    const begin = sorted.find((s) => s.kind === 'ROUND_BEFORE')
    const end = sorted.toReversed().find((s) => s.kind === 'ROUND_AFTER')
    const runs = new Map<string, { before?: PlanUsage; after?: PlanUsage }>()
    for (const s of sorted) {
      if (!s.runRef || (s.kind !== 'CHECKIN_BEFORE' && s.kind !== 'CHECKIN_AFTER')) continue
      const r = runs.get(s.runRef) ?? {}
      if (s.kind === 'CHECKIN_BEFORE') r.before = s
      else r.after = s
      runs.set(s.runRef, r)
    }
    const checkIns: CheckIn[] = [...runs.entries()].map(([runRef, r]) => {
      const any = (r.after ?? r.before)!
      const t = r.after?.tokens
      return {
        bot: any.botHandle ?? '',
        runRef,
        at: (r.before ?? r.after)!.takenAt ?? '',
        session: change(r.before && reading(r.before).session, r.after && reading(r.after).session),
        weekly: change(r.before && reading(r.before).weekly, r.after && reading(r.after).weekly),
        tokens: t ? { input: t.input, cacheWrite: t.cacheWrite, cacheRead: t.cacheRead, output: t.output } : null,
        costUsd: num(r.after?.costUsd),
      }
    })
    const first = begin ?? sorted[0]!
    const last = end ?? null
    out.push({
      ref,
      startedAt: first.takenAt ?? '',
      endedAt: last?.takenAt ?? null,
      session: change(reading(begin ?? sorted[0]!).session, last ? reading(last).session : null),
      weekly: change(reading(begin ?? sorted[0]!).weekly, last ? reading(last).weekly : null),
      checkIns,
      costUsd: checkIns.reduce((sum, c) => sum + (c.costUsd ?? 0), 0),
    })
  }
  return out.sort((a, b) => b.startedAt.localeCompare(a.startedAt))
}

/**
 * Days (ET), newest first: the weekly meter at the day's first reading → its last, the check-ins and their estimated
 * cost. (The 5-hour meter resets during a day, so only the weekly one says what a day used.)
 */
export function days(rows: readonly PlanUsage[]): Day[] {
  const byDate = new Map<string, PlanUsage[]>()
  for (const s of rows) {
    if (!s.tradeDate) continue
    const g = byDate.get(s.tradeDate) ?? []
    g.push(s)
    byDate.set(s.tradeDate, g)
  }
  return [...byDate.entries()]
    .map(([date, list]) => {
      const sorted = [...list].sort(byTime)
      const after = sorted.filter((s) => s.kind === 'CHECKIN_AFTER')
      return {
        date,
        weekly: change(reading(sorted[0]!).weekly, reading(sorted.at(-1)!).weekly),
        checkIns: after.length,
        costUsd: after.reduce((sum, s) => sum + (num(s.costUsd) ?? 0), 0),
      }
    })
    .sort((a, b) => b.date.localeCompare(a.date))
}

/** "10%", "–" when unknown. */
export const pct = (v: number | null | undefined): string => (v == null ? '–' : `${Number.isInteger(v) ? v : v.toFixed(1)}%`)

/** "10% → 12% (+2)", "10% → 12% (reset)", "10% → …" while it runs. */
export function changeText(c: Change, running = false): string {
  if (c.after == null) return `${pct(c.before)} → ${running ? '…' : '–'}`
  const tail = c.reset ? ' (reset)' : c.delta == null ? '' : ` (${c.delta > 0 ? '+' : c.delta < 0 ? '−' : '±'}${Math.abs(c.delta)})`
  return `${pct(c.before)} → ${pct(c.after)}${tail}`
}
