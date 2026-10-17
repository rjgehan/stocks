/**
 * Market-time helpers for the chart (§22.10): the ET trading date of a bar (VWAP's session anchor) and whether a bar
 * lies inside the regular session of its date, from `GET /market/calendar` (early closes included) or 09:30–16:00 ET.
 */
export interface SessionDay {
  date: string | null
  rthOpenAt: string | null
  rthCloseAt: string | null
}

const etParts = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/New_York',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

interface Et {
  date: string
  minutes: number
}

const cache = new Map<number, Et>()

/** ET calendar date (YYYY-MM-DD) and minutes after ET midnight of an epoch-seconds time. */
export function etOf(sec: number): Et {
  const hit = cache.get(sec)
  if (hit) return hit
  const p: Record<string, string> = {}
  for (const part of etParts.formatToParts(new Date(sec * 1000))) p[part.type] = part.value
  const et = { date: `${p.year}-${p.month}-${p.day}`, minutes: Number(p.hour) * 60 + Number(p.minute) }
  if (cache.size > 20_000) cache.clear()
  cache.set(sec, et)
  return et
}

export const etDateOf = (sec: number): string => etOf(sec).date

const OPEN_MIN = 9 * 60 + 30
const CLOSE_MIN = 16 * 60

/** A crypto pair has no regular session (it trades 24/7): every bar counts as regular, so nothing is shaded. */
export const ALWAYS_REGULAR: (sec: number) => boolean = () => true

/**
 * `isRegular(time)`: true when a bar starting at `time` (epoch seconds) is inside its date's regular session. A
 * calendar day wins (an early close ends at 13:00); a date the calendar does not list falls back to 09:30–16:00 ET.
 */
export function regularSessionTest(days: readonly SessionDay[] | null | undefined): (sec: number) => boolean {
  const byDate = new Map<string, [number, number]>()
  for (const d of days ?? []) {
    if (!d.date || !d.rthOpenAt || !d.rthCloseAt) continue
    const open = Date.parse(d.rthOpenAt) / 1000
    const close = Date.parse(d.rthCloseAt) / 1000
    if (Number.isFinite(open) && Number.isFinite(close)) byDate.set(d.date, [open, close])
  }
  return (sec) => {
    const et = etOf(sec)
    const day = byDate.get(et.date)
    if (day) return sec >= day[0] && sec < day[1]
    return et.minutes >= OPEN_MIN && et.minutes < CLOSE_MIN
  }
}
