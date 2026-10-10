import type { RankPoint } from '@/lib/api/types'
import { cx } from '@/components/ui/cx'

/**
 * Rank history (§16.8): a small inverted sparkline of the latest EOD ranks (rank 1 at the top) with a ▲/▼ marker on
 * every day the rank changed, and the latest change as text. Unranked days leave a gap. SVG numbers are display only.
 */
export function RankHistory({ ranks, current, width = 96, height = 28, className }: { ranks: ReadonlyArray<RankPoint> | null | undefined; current?: number | null; width?: number; height?: number; className?: string }) {
  const points = (ranks ?? []).map((r) => r.rank)
  if (current != null) points.push(current)
  const ranked = points.filter((r): r is number => r != null)
  if (ranked.length < 2) {
    return <span className={cx('text-micro text-text-3', className)}>{ranked.length ? `#${ranked[0]}` : 'No history yet'}</span>
  }
  const lo = Math.min(...ranked)
  const hi = Math.max(...ranked)
  const span = hi - lo || 1
  const pad = 4
  const x = (i: number) => (points.length === 1 ? width / 2 : pad + (i / (points.length - 1)) * (width - pad * 2))
  const y = (rank: number) => pad + ((rank - lo) / span) * (height - pad * 2)      // rank 1 (best) at the top
  const segments: string[] = []
  let path = ''
  points.forEach((r, i) => {
    if (r == null) {
      if (path) segments.push(path)
      path = ''
      return
    }
    path += `${path ? ' L' : 'M'}${x(i).toFixed(1)},${y(r).toFixed(1)}`
  })
  if (path) segments.push(path)
  const markers: Array<{ i: number; up: boolean }> = []
  let prev: number | null = null
  points.forEach((r, i) => {
    if (r != null && prev != null && r !== prev) markers.push({ i, up: r < prev })
    if (r != null) prev = r
  })
  const last = ranked[ranked.length - 1]!
  const before = ranked[ranked.length - 2]!
  const change = before - last
  return (
    <span className={cx('inline-flex items-center gap-1.5', className)}>
      <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} aria-hidden="true" className="shrink-0">
        {segments.map((d, i) => (
          <path key={i} d={d} fill="none" stroke="var(--text-3)" strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
        ))}
        {markers.map((m) => (
          <text key={m.i} x={x(m.i)} y={y(points[m.i]!) + (m.up ? -1 : 3)} fontSize={7} textAnchor="middle" fill={m.up ? 'var(--up)' : 'var(--down)'}>
            {m.up ? '▲' : '▼'}
          </text>
        ))}
      </svg>
      <span className="text-micro whitespace-nowrap text-text-2" aria-label={`Rank ${last}${change ? `, ${change > 0 ? 'up' : 'down'} ${Math.abs(change)}` : ''}`}>
        #{last}
        {change ? <span className={change > 0 ? 'text-up' : 'text-down'}> {change > 0 ? '▲' : '▼'}{Math.abs(change)}</span> : null}
      </span>
    </span>
  )
}
