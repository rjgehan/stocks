import { Link } from '@tanstack/react-router'
import type { LeaderboardMetric, LeaderboardRow } from '@/lib/api/types'
import { abs, isDecimal, isZero, sign } from '@/lib/format/decimal'
import { formatMoney, formatMoneyCompact } from '@/lib/format/money'
import { formatPct, moneyDirection, pctDirection } from '@/lib/format/number'
import { Pill } from '@/components/ui/Pill'
import { Sparkline } from '@/components/ui/Sparkline'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { AiChip, Avatar } from './PlayerBadge'

const MEDAL = ['bg-gold', 'bg-silver', 'bg-bronze'] as const

function RankCell({ row }: { row: LeaderboardRow }) {
  if (row.rank === null) return <span className="text-text-3">–</span>
  const medal = row.rank <= 3 ? MEDAL[row.rank - 1] : null
  return (
    <span className="inline-flex items-center gap-1">
      {medal ? (
        <span className={cx('inline-flex size-6 items-center justify-center rounded-full text-micro font-bold text-black/80', medal)} aria-label={`Rank ${row.rank}`}>
          {row.rank}
        </span>
      ) : (
        <span className="w-6 text-center font-semibold" aria-label={`Rank ${row.rank}`}>
          {row.rank}
        </span>
      )}
      {row.rankChange ? (
        <span className={cx('text-micro', row.rankChange > 0 ? 'text-up' : 'text-down')} aria-label={`${row.rankChange > 0 ? 'up' : 'down'} ${Math.abs(row.rankChange)} since yesterday`}>
          {row.rankChange > 0 ? '▲' : '▼'}
          {Math.abs(row.rankChange)}
        </span>
      ) : null}
    </span>
  )
}

/** "+$500 deposited" when money moved in or out during the period (§16.7 charter item 2: deposits are public). */
export function FlowChip({ flows }: { flows: string | null | undefined }) {
  if (!flows || !isDecimal(flows) || isZero(flows)) return null
  const dep = sign(flows) > 0
  return (
    <Pill tone={dep ? 'accent' : 'neutral'} title="Net deposits and withdrawals in this period. They never change the return.">
      {dep ? '+' : '−'}
      {formatMoneyCompact(abs(flows))} {dep ? 'deposited' : 'withdrawn'}
    </Pill>
  )
}

function Metric({ row, metric }: { row: LeaderboardRow; metric: LeaderboardMetric }) {
  const value = metric === 'RETURN' ? row.return : row.pnl
  return (
    <span className={cx('font-semibold', DIRECTION_TEXT[metric === 'RETURN' ? pctDirection(value) : moneyDirection(value)])}>{metric === 'RETURN' ? formatPct(value) : formatMoney(value, { signed: true })}</span>
  )
}

function Name({ row, me }: { row: LeaderboardRow; me: boolean }) {
  const p = row.player
  return (
    <Link to="/players/$handle" params={{ handle: p?.handle ?? '' }} className="flex min-w-0 items-center gap-2.5 hover:text-accent">
      <Avatar player={p} size={30} />
      <span className="min-w-0">
        <span className="flex flex-wrap items-center gap-1.5">
          <span className="truncate font-semibold">{p?.displayName ?? p?.handle}</span>
          {me ? <Pill tone="accent">You</Pill> : null}
          {p?.kind === 'AI' ? <AiChip model={p.aiModel} /> : row.apiTrader ? <Pill tone="neutral" title="Placed trades through an API key">API</Pill> : null}
          {row.rebased ? <Pill tone="down" title="The account was wiped out and restarted in this window">Rebased</Pill> : null}
        </span>
        <span className="block truncate text-caption text-text-3">
          @{p?.handle}
          {row.eligibilityNote ? ` · ${row.eligibilityNote}` : ` · ${row.fills} fills · ${row.symbolsTraded} symbols`}
          {row.topHolding ? ` · top ${row.topHolding}` : ''}
        </span>
      </span>
    </Link>
  )
}

/**
 * Leaderboard (§22.4): medals for 1–3, rank arrows, AI chip, return, P&L, vs SPY, net contributions with a deposit chip,
 * fills, sparkline; unranked rows greyed with their eligibility note; my row pinned at the bottom. Cards on phones.
 */
export function LeaderboardTable({ rows, me, metric, myHandle }: { rows: LeaderboardRow[]; me: LeaderboardRow | null; metric: LeaderboardMetric; myHandle?: string | null }) {
  const isMe = (r: LeaderboardRow) => Boolean(myHandle && r.player?.handle === myHandle)
  // My row is pinned at the bottom when the board does not already list it (§22.4).
  const pinned = me && !rows.some(isMe) ? me : null
  const shown = rows

  const card = (r: LeaderboardRow, pin = false) => (
    <li key={`${pin ? 'pin-' : ''}${r.player?.handle}`} className={cx('flex items-center gap-3 px-3 py-3', !r.eligible && 'opacity-55', (pin || isMe(r)) && 'bg-accent-bg')}>
      <span className="w-9 shrink-0">
        <RankCell row={r} />
      </span>
      <div className="min-w-0 flex-1">
        <Name row={r} me={isMe(r)} />
        <div className="mt-1 flex flex-wrap items-center gap-1.5 pl-[40px]">
          <FlowChip flows={r.flowsInPeriod} />
        </div>
      </div>
      <div className="shrink-0 text-right">
        <div className="text-body">
          <Metric row={r} metric={metric} />
        </div>
        <div className="text-caption text-text-3">{metric === 'RETURN' ? formatMoney(r.pnl, { signed: true }) : formatPct(r.return)}</div>
      </div>
    </li>
  )

  const tr = (r: LeaderboardRow, pin = false) => (
    <tr key={`${pin ? 'pin-' : ''}${r.player?.handle}`} className={cx('h-14', !r.eligible && 'opacity-55', (pin || isMe(r)) && 'bg-accent-bg', pin && 'border-t-2 border-accent/40')}>
      <td className="w-16 pl-4">
        <RankCell row={r} />
      </td>
      <td className="max-w-0 py-2 pr-2">
        <Name row={r} me={isMe(r)} />
      </td>
      <td className={cx('px-2 text-right font-semibold', metric === 'RETURN' && 'text-body', DIRECTION_TEXT[pctDirection(r.return)])}>{formatPct(r.return)}</td>
      <td className="px-2 text-right">
        <span className={cx('font-semibold', DIRECTION_TEXT[moneyDirection(r.pnl)])}>{formatMoney(r.pnl, { signed: true })}</span>
      </td>
      <td className="hidden px-2 text-right desktop:table-cell">
        <span className={DIRECTION_TEXT[pctDirection(r.excessVsBenchmark)]}>{formatPct(r.excessVsBenchmark)}</span>
      </td>
      <td className="px-2 text-right">
        <div className="flex flex-col items-end gap-0.5">
          <span>{formatMoney(r.netContributions)}</span>
          <FlowChip flows={r.flowsInPeriod} />
        </div>
      </td>
      <td className="hidden px-2 text-right wide:table-cell">{r.equity ? formatMoney(r.equity) : <span className="text-text-3">hidden</span>}</td>
      <td className="hidden px-2 text-right tablet:table-cell">{r.fills}</td>
      <td className="hidden pr-4 pl-2 desktop:table-cell">
        <Sparkline values={r.spark} />
      </td>
    </tr>
  )

  return (
    <>
      <ul className="divide-y divide-divider rounded-lg border border-divider bg-bg-1 tablet:hidden" aria-label="Leaderboard">
        {shown.map((r) => card(r))}
        {pinned ? card(pinned, true) : null}
      </ul>
      <div className="hidden overflow-x-auto rounded-lg border border-divider bg-bg-1 tablet:block">
        <table className="w-full text-table" aria-label="Leaderboard">
          <thead className="text-left text-micro tracking-wide text-text-3 uppercase">
            <tr className="h-9 border-b border-divider">
              <th className="pl-4 font-medium">Rank</th>
              <th className="w-[30%] min-w-52 font-medium">Player</th>
              <th className={cx('px-2 text-right font-medium', metric === 'RETURN' && 'text-text-1')}>Return</th>
              <th className={cx('px-2 text-right font-medium', metric === 'PNL' && 'text-text-1')}>P&amp;L</th>
              <th className="hidden px-2 text-right font-medium desktop:table-cell">vs SPY</th>
              <th className="px-2 text-right font-medium">Net contrib.</th>
              <th className="hidden px-2 text-right font-medium wide:table-cell">Equity</th>
              <th className="hidden px-2 text-right font-medium tablet:table-cell">Fills</th>
              <th className="hidden pr-4 pl-2 font-medium desktop:table-cell">Trend</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-divider">
            {shown.map((r) => tr(r))}
            {pinned ? tr(pinned, true) : null}
          </tbody>
        </table>
      </div>
    </>
  )
}
