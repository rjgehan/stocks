import type { ChartRange, Timeframe } from '@/lib/api/types'

/** The bar interval each chart range uses when none is picked (§15.5 BarService table). */
export const DEFAULT_TIMEFRAME: Record<ChartRange, Timeframe> = {
  '1D': '1Min',
  '5D': '5Min',
  '1M': '30Min',
  '3M': '1Day',
  '6M': '1Day',
  YTD: '1Day',
  '1Y': '1Day',
  '5Y': '1Week',
  ALL: '1Month',
}

/** Intervals that give a readable number of bars (≤ 1000, §18.2) for each range. */
export const INTERVALS: Record<ChartRange, readonly Timeframe[]> = {
  '1D': ['1Min', '5Min', '15Min', '30Min'],
  '5D': ['5Min', '15Min', '30Min', '1Hour'],
  '1M': ['30Min', '1Hour', '1Day'],
  '3M': ['1Day', '1Week'],
  '6M': ['1Day', '1Week'],
  YTD: ['1Day', '1Week'],
  '1Y': ['1Day', '1Week'],
  '5Y': ['1Week', '1Month'],
  ALL: ['1Week', '1Month'],
}

const LABEL: Record<Timeframe, string> = {
  '1Min': '1 min',
  '5Min': '5 min',
  '15Min': '15 min',
  '30Min': '30 min',
  '1Hour': '1 hour',
  '1Day': 'Daily',
  '1Week': 'Weekly',
  '1Month': 'Monthly',
}

const ALL_TIMEFRAMES = Object.keys(LABEL) as Timeframe[]

/** Parses a `tf` search param (§22.2) case-insensitively. */
export const parseTimeframe = (v: unknown): Timeframe | undefined =>
  typeof v === 'string' ? ALL_TIMEFRAMES.find((t) => t.toLowerCase() === v.toLowerCase()) : undefined

/** The interval to request: the picked one when it suits the range, otherwise none (the server's default). */
export const effectiveTimeframe = (range: ChartRange, tf: Timeframe | undefined): Timeframe | undefined =>
  tf && tf !== DEFAULT_TIMEFRAME[range] && INTERVALS[range].includes(tf) ? tf : undefined

/** Interval picker (§22.4): a compact select beside the range chips; "Auto" is the range's default interval. */
export function IntervalPicker({ range, value, onChange }: { range: ChartRange; value: Timeframe | undefined; onChange: (tf: Timeframe | undefined) => void }) {
  const current = effectiveTimeframe(range, value) ?? DEFAULT_TIMEFRAME[range]
  return (
    <label className="relative inline-flex shrink-0 items-center">
      <span className="sr-only">Interval</span>
      <select
        value={current}
        onChange={(e) => {
          const tf = e.target.value as Timeframe
          onChange(tf === DEFAULT_TIMEFRAME[range] ? undefined : tf)
        }}
        className="h-7 cursor-pointer appearance-none rounded-sm bg-bg-2 pr-2 pl-2 text-caption font-semibold text-text-2 outline-none hover:text-text-1 focus-visible:ring-2 focus-visible:ring-accent"
      >
        {INTERVALS[range].map((tf) => (
          <option key={tf} value={tf}>
            {LABEL[tf]}
          </option>
        ))}
      </select>
    </label>
  )
}
