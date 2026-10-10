import type { ChartRange } from '@/lib/api/types'
import { cx } from '@/components/ui/cx'

export const CHART_RANGES: readonly ChartRange[] = ['1D', '5D', '1M', '3M', '6M', 'YTD', '1Y', '5Y', 'ALL']

export function RangeChips({ value, onChange }: { value: ChartRange; onChange: (r: ChartRange) => void }) {
  return (
    <div role="radiogroup" aria-label="Chart range" className="scrollbar-none flex gap-1 overflow-x-auto">
      {CHART_RANGES.map((r) => (
        <button
          key={r}
          type="button"
          role="radio"
          aria-checked={r === value}
          onClick={() => onChange(r)}
          className={cx(
            // §22.3: 44 px touch targets on phones
            'h-11 min-w-11 shrink-0 rounded-sm px-2 text-caption font-semibold transition-colors tablet:h-7 tablet:min-w-10',
            r === value ? 'bg-bg-3 text-text-1' : 'text-text-2 hover:bg-bg-2 hover:text-text-1',
          )}
        >
          {r}
        </button>
      ))}
    </div>
  )
}
