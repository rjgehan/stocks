import { TickMarkType, type Time } from 'lightweight-charts'

const etTime = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', hour: 'numeric', minute: '2-digit' })
const etDay = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', day: 'numeric' })
const etMonth = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', month: 'short', year: '2-digit' })

/**
 * Time-axis labels in market time (ET), never the browser's zone or lightweight-charts' UTC default: "10:05 AM" for
 * intraday ticks, "Oct 9" for days, "Oct 26" (month and year) above that. Shared by the price chart and the line
 * charts, so an EOD point at 20:20 ET stays on its own day.
 */
export function etTickLabel(time: Time, type: TickMarkType): string {
  const d = new Date((time as number) * 1000)
  if (type === TickMarkType.Year || type === TickMarkType.Month) return etMonth.format(d)
  if (type === TickMarkType.DayOfMonth) return etDay.format(d)
  return etTime.format(d)
}
