/** News and analyst-rating helpers (§22.10), pure. */

export interface RatingCounts {
  strongBuy: number
  buy: number
  hold: number
  sell: number
  strongSell: number
}

export type Consensus = 'Strong buy' | 'Buy' | 'Hold' | 'Sell' | 'Strong sell'

/** Weighted mean (strong buy 1 … strong sell 5) → label: ≤ 1.5, ≤ 2.5, ≤ 3.5, ≤ 4.5, else; null without ratings. */
export function consensus(c: RatingCounts): { label: Consensus; score: number } | null {
  const total = c.strongBuy + c.buy + c.hold + c.sell + c.strongSell
  if (total <= 0) return null
  const score = (c.strongBuy * 1 + c.buy * 2 + c.hold * 3 + c.sell * 4 + c.strongSell * 5) / total
  const label: Consensus = score <= 1.5 ? 'Strong buy' : score <= 2.5 ? 'Buy' : score <= 3.5 ? 'Hold' : score <= 4.5 ? 'Sell' : 'Strong sell'
  return { label, score }
}

/** "now", "12m ago", "3h ago", "2d ago", then the ET date ("Oct 2"); `now` in epoch ms. */
export function timeAgo(iso: string | null | undefined, now: number): string {
  if (!iso) return '--'
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return '--'
  const s = Math.max(0, Math.floor((now - t) / 1000))
  if (s < 60) return 'now'
  if (s < 3600) return `${Math.floor(s / 60)}m ago`
  if (s < 86_400) return `${Math.floor(s / 3600)}h ago`
  if (s < 7 * 86_400) return `${Math.floor(s / 86_400)}d ago`
  return new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric' }).format(new Date(t))
}

/** Only absolute http(s) links are ever opened (never javascript:, data: or relative URLs). */
export function safeHttpUrl(url: string | null | undefined): string | null {
  if (!url) return null
  try {
    const u = new URL(url)
    return u.protocol === 'https:' || u.protocol === 'http:' ? u.toString() : null
  } catch {
    return null
  }
}

/** "Oct 2026" for a recommendation period (the first day of the month). */
export function monthLabel(period: string | null | undefined, short = false): string {
  if (!period || !/^\d{4}-\d{2}-\d{2}$/.test(period)) return '--'
  return new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', month: 'short', ...(short ? {} : { year: 'numeric' }) }).format(new Date(`${period}T12:00:00Z`))
}
