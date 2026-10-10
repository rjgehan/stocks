/**
 * Formatting for the league stats (§16.8, §22.4 Phase 2): hold times, ratios (Sharpe, profit factor) and the
 * "needs 20 days" note. Inputs are server decimal strings; nothing here does money arithmetic.
 */
import { type Dec, isDecimal, sign } from '@/lib/format/decimal'
import { MISSING, type Maybe, formatDecimal } from '@/lib/format/number'

/** "45m", "3h 20m", "2d 4h", "12d"; under a minute "<1m"; missing "--". */
export function formatHold(seconds: Maybe<number>): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) return MISSING
  const s = Math.round(seconds)
  if (s < 60) return '<1m'
  const minutes = Math.floor(s / 60)
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return minutes % 60 ? `${hours}h ${minutes % 60}m` : `${hours}h`
  const days = Math.floor(hours / 24)
  return days < 10 && hours % 24 ? `${days}d ${hours % 24}h` : `${days}d`
}

/** A ratio (Sharpe, profit factor) at 2 decimals with the app's minus sign: "1.23", "−0.40"; missing "--". */
export function formatRatio(value: Maybe<Dec>, scale = 2): string {
  return formatDecimal(value, { scale })
}

/**
 * Profit factor (§16.8): the ratio; "∞" when there are winning sells but no losing one (the server sends null);
 * "--" without closing sells.
 */
export function profitFactorLabel(profitFactor: Maybe<Dec>, grossProfit: Maybe<Dec>, closingSells: Maybe<number>): string {
  if (profitFactor != null && isDecimal(profitFactor)) return formatRatio(profitFactor)
  if (!closingSells) return MISSING
  return grossProfit != null && isDecimal(grossProfit) && sign(grossProfit) > 0 ? '∞' : MISSING
}

/** Sharpe (§16.8): the value, or why there is none yet ("needs 20 days · 12/20"). */
export function sharpeLabel(sharpe: Maybe<Dec>, dailyReturns: Maybe<number>, minDays: Maybe<number>): { value: string; note: string | null } {
  if (sharpe != null && isDecimal(sharpe)) return { value: formatRatio(sharpe), note: null }
  const need = minDays ?? 20
  const have = dailyReturns ?? 0
  if (have < need) return { value: MISSING, note: `needs ${need} days · ${have}/${need}` }
  return { value: MISSING, note: 'no variation yet' }
}

/** The rank change between the last two EOD ranks: positive = moved up (rank number went down). */
export function lastRankChange(ranks: ReadonlyArray<{ rank: number | null }> | null | undefined, current?: number | null): number | null {
  const list = (ranks ?? []).filter((r) => r.rank != null)
  if (current != null && list.length >= 1) return list[list.length - 1]!.rank! - current
  if (list.length < 2) return null
  return list[list.length - 2]!.rank! - list[list.length - 1]!.rank!
}
