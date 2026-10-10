import { Link } from '@tanstack/react-router'
import type { LeaderboardMetric, LeaderboardRow } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { direction, formatPct } from '@/lib/format/number'
import { Pill } from '@/components/ui/Pill'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'

const MEDAL = ['bg-gold', 'bg-silver', 'bg-bronze'] as const

function RankCell({ row }: { row: LeaderboardRow }) {
  if (row.rank === null) return <span className="text-text-3">–</span>
  const medal = row.rank <= 3 ? MEDAL[row.rank - 1] : null
  return (
    <span className="inline-flex items-center gap-1">
      {medal ? (
        <span className={cx('inline-flex size-5 items-center justify-center rounded-full text-micro font-bold text-black/80', medal)}>{row.rank}</span>
      ) : (
        <span className="w-5 text-center font-semibold">{row.rank}</span>
      )}
      {row.rankChange ? (
        <span className={cx('text-micro', row.rankChange > 0 ? 'text-up' : 'text-down')} aria-label={`rank ${row.rankChange > 0 ? 'up' : 'down'} ${Math.abs(row.rankChange)}`}>
          {row.rankChange > 0 ? '▲' : '▼'}
          {Math.abs(row.rankChange)}
        </span>
      ) : null}
    </span>
  )
}

/**
 * Leaderboard rows (§22.4): medals for 1–3, rank arrows, AI chip, return and P&L, unranked rows greyed with
 * their eligibility note, my row pinned at the bottom. Sparklines, deposit chips and formula popovers: M3.
 */
export function LeaderboardTable({ rows, me, metric }: { rows: LeaderboardRow[]; me: LeaderboardRow | null; metric: LeaderboardMetric }) {
  const render = (r: LeaderboardRow, pinned = false) => (
    <li
      key={`${pinned ? 'me-' : ''}${r.player.handle}`}
      className={cx('flex items-center gap-3 px-4 py-2.5 text-table', !r.eligible && 'opacity-55', pinned && 'border-t-2 border-accent/40 bg-accent-bg')}
    >
      <span className="w-12 shrink-0">
        <RankCell row={r} />
      </span>
      <Link to="/players/$handle" params={{ handle: r.player.handle }} className="min-w-0 flex-1 hover:text-accent">
        <span className="flex items-center gap-1.5">
          <span className="truncate font-semibold">{r.player.displayName}</span>
          {r.player.kind === 'AI' ? <Pill tone="ai">AI{r.player.aiModel ? ` · ${r.player.aiModel}` : ''}</Pill> : null}
        </span>
        <span className="block truncate text-caption text-text-3">
          @{r.player.handle}
          {r.eligibilityNote ? ` · ${r.eligibilityNote}` : ` · ${r.fills} fills · ${r.symbolsTraded} symbols`}
          {r.topHolding ? ` · top ${r.topHolding}` : ''}
        </span>
      </Link>
      <span className={cx('w-20 text-right font-semibold', DIRECTION_TEXT[direction(metric === 'RETURN' ? r.return : r.pnl)])}>
        {metric === 'RETURN' ? formatPct(r.return) : formatMoney(r.pnl, { signed: true })}
      </span>
      <span className="hidden w-24 text-right text-text-2 tablet:inline">
        {metric === 'RETURN' ? formatMoney(r.pnl, { signed: true }) : formatPct(r.return)}
      </span>
    </li>
  )
  return (
    <ul className="divide-y divide-divider rounded-lg border border-divider bg-bg-1">
      {rows.map((r) => render(r))}
      {me ? render(me, true) : null}
    </ul>
  )
}
