import { isDecimal } from '@/lib/format/decimal'
import { cx } from './cx'

/**
 * A tiny SVG line (leaderboard rows). Values are decimal strings converted to numbers FOR PLOTTING ONLY.
 * The colour follows the direction of the last point against the first.
 */
export function Sparkline({ values, width = 72, height = 24, className }: { values: ReadonlyArray<string | null> | null | undefined; width?: number; height?: number; className?: string }) {
  const nums = (values ?? []).filter((v): v is string => v != null && isDecimal(v)).map(Number)
  if (nums.length < 2) return <span className={cx('inline-block', className)} style={{ width, height }} aria-hidden="true" />
  const lo = Math.min(...nums)
  const hi = Math.max(...nums)
  const span = hi - lo || 1
  const pad = 2
  const pts = nums
    .map((v, i) => `${((i / (nums.length - 1)) * (width - pad * 2) + pad).toFixed(1)},${(height - pad - ((v - lo) / span) * (height - pad * 2)).toFixed(1)}`)
    .join(' ')
  const up = nums[nums.length - 1]! >= nums[0]!
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className={cx('shrink-0', className)} aria-hidden="true">
      <polyline points={pts} fill="none" stroke={up ? 'var(--up)' : 'var(--down)'} strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  )
}
