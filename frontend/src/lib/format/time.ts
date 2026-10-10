/** Time formatting. Market times are shown in ET with an "ET" suffix (§22.7). */
export const MARKET_TZ = 'America/New_York'

const timeEt = new Intl.DateTimeFormat('en-US', { timeZone: MARKET_TZ, hour: 'numeric', minute: '2-digit' })
const dateTimeEt = new Intl.DateTimeFormat('en-US', {
  timeZone: MARKET_TZ,
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
})
const monthDayYear = new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', month: 'short', day: 'numeric', year: 'numeric' })

function toDate(ts: string | number | Date): Date | null {
  const d = ts instanceof Date ? ts : new Date(ts)
  return Number.isNaN(d.getTime()) ? null : d
}

/** "4:12 PM ET" */
export function formatTimeEt(ts: string | number | Date | null | undefined): string {
  const d = ts == null ? null : toDate(ts)
  return d ? `${timeEt.format(d)} ET` : '--'
}

/** "Oct 9, 4:12 PM ET" */
export function formatDateTimeEt(ts: string | number | Date | null | undefined): string {
  const d = ts == null ? null : toDate(ts)
  return d ? `${dateTimeEt.format(d)} ET` : '--'
}

/** An ET trading date "2026-10-13" → "Oct 13, 2026" (no timezone shift: it is already a calendar date). */
export function formatDateEt(date: string | null | undefined): string {
  if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return '--'
  return monthDayYear.format(new Date(`${date}T12:00:00Z`))
}

/** Countdown "2:14:07" (h:mm:ss) or "14:07" under an hour; never negative. */
export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}
