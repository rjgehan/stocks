import { Link } from '@tanstack/react-router'
import type { ClosedLot, LeaderboardPeriod, PlayerStats } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatPct, moneyDirection, pctDirection } from '@/lib/format/number'
import { formatDateEt } from '@/lib/format/time'
import { Segmented } from '@/components/ui/Segmented'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { useFeedDelay } from '@/features/league/useFeedDelay'
import { formatHold, profitFactorLabel, sharpeLabel } from './format'
import { RankHistory } from './RankHistory'

export const STATS_PERIODS: ReadonlyArray<{ value: LeaderboardPeriod; label: string }> = [
  { value: 'WEEK', label: 'Week' },
  { value: 'MONTH', label: 'Month' },
  { value: 'YTD', label: 'YTD' },
  { value: 'ALL', label: 'All' },
]

function Stat({ label, value, note, tone, title }: { label: string; value: React.ReactNode; note?: React.ReactNode; tone?: string; title?: string }) {
  return (
    <div className="min-w-0 bg-bg-1 p-3" title={title}>
      <dt className="text-micro tracking-wide text-text-3 uppercase">{label}</dt>
      <dd className={cx('text-section font-semibold tabular-nums', tone)}>{value}</dd>
      {note ? <dd className="text-micro text-text-3">{note}</dd> : null}
    </div>
  )
}

function Trade({ label, lot }: { label: string; lot: ClosedLot | null | undefined }) {
  return (
    <div className="min-w-0 bg-bg-1 p-3 tablet:col-span-2">
      <dt className="text-micro tracking-wide text-text-3 uppercase">{label}</dt>
      {lot ? (
        <>
          <dd className="flex items-baseline gap-1.5">
            <Link to="/stock/$symbol" params={{ symbol: lot.symbol ?? '' }} className="font-semibold hover:text-accent">
              {lot.symbol}
            </Link>
            <span className={cx('font-semibold tabular-nums', DIRECTION_TEXT[moneyDirection(lot.realizedPnl)])}>{formatMoney(lot.realizedPnl, { signed: true })}</span>
          </dd>
          <dd className="text-micro text-text-3">
            {formatPct(lot.return)} · held {formatHold(lot.holdSeconds)} · {formatDateEt(lot.closedAt ? new Date(lot.closedAt).toLocaleDateString('en-CA', { timeZone: 'America/New_York' }) : null)}
          </dd>
        </>
      ) : (
        <dd className="text-section font-semibold text-text-3">--</dd>
      )}
    </div>
  )
}

/**
 * Player stats card (§16.8, §22.4 Phase 2): Sharpe ("needs 20 days · 12/20" below the minimum), volatility, profit
 * factor (∞ without a losing sell), win rate with wins/sells, average hold, max drawdown, best and worst trade, and the
 * rank history. Period chips Week · Month · YTD · All. Used on profiles and on the P&L Analysis page.
 */
export function PlayerStatsCard({
  stats,
  isPending,
  isError,
  period,
  onPeriod,
  title = 'League stats',
  delayed = false,
}: {
  stats: PlayerStats | undefined
  isPending: boolean
  isError: boolean
  period: LeaderboardPeriod
  onPeriod: (p: LeaderboardPeriod) => void
  title?: string
  /** Another player's stats: best and worst trades only after the feed delay, if the admin sets one. */
  delayed?: boolean
}) {
  const feedDelay = useFeedDelay()
  const s = stats
  const sharpe = sharpeLabel(s?.sharpe, s?.dailyReturns, s?.sharpeMinDays)
  return (
    <section aria-label={title} className="rounded-lg border border-divider bg-bg-1">
      <div className="flex flex-wrap items-center justify-between gap-2 px-4 pt-3 pb-2">
        <h2 className="text-section font-semibold">{title}</h2>
        <Segmented label="Stats period" size="sm" value={period} onChange={onPeriod} options={STATS_PERIODS} />
      </div>
      {isError ? (
        <p className="px-4 pb-4 text-caption text-text-3">Stats appear once the account has a run on the board.</p>
      ) : isPending || !s ? (
        <div className="grid grid-cols-2 gap-2 px-4 pb-4 tablet:grid-cols-4">
          {Array.from({ length: 8 }, (_, i) => (
            <Skeleton key={i} className="h-12" />
          ))}
        </div>
      ) : (
        <>
          <dl className="grid grid-cols-2 gap-px border-y border-divider bg-divider tablet:grid-cols-4">
            <Stat label="Return" value={formatPct(s.return)} tone={DIRECTION_TEXT[pctDirection(s.return)]} note={formatMoney(s.pnl, { signed: true })} />
            <Stat label="Sharpe" value={sharpe.value} note={sharpe.note ?? 'annualized, risk-free 0'} title="Mean ÷ standard deviation of daily unit-value returns × √252; needs 20 daily returns" />
            <Stat label="Volatility" value={s.volatility ? formatPct(s.volatility, { signed: false }) : '--'} note="annualized" />
            <Stat label="Max drawdown" value={s.maxDrawdown ? formatPct(s.maxDrawdown, { signed: false }) : '--'} />
            <Stat
              label="Win rate"
              value={s.winRate ? formatPct(s.winRate, { signed: false, scale: 0 }) : '--'}
              note={`${s.wins}/${s.closingSells} sells`}
            />
            <Stat
              label="Profit factor"
              value={profitFactorLabel(s.profitFactor, s.grossProfit, s.closingSells)}
              note={`${formatMoney(s.grossProfit)} won / ${formatMoney(s.grossLoss)} lost`}
              title="Gross profit ÷ gross loss of closing sells"
            />
            <Stat label="Avg hold" value={formatHold(s.avgHoldSeconds)} note={`${s.closedLots} closed lot${s.closedLots === 1 ? '' : 's'}`} />
            <div className="min-w-0 bg-bg-1 p-3">
              <dt className="text-micro tracking-wide text-text-3 uppercase">Rank</dt>
              <dd className="mt-1">
                <RankHistory ranks={s.rankHistory} current={s.rank} />
              </dd>
            </div>
            <Trade label="Best trade" lot={s.bestTrade} />
            <Trade label="Worst trade" lot={s.worstTrade} />
          </dl>
          <p className="px-4 py-2 text-micro text-text-3">
            Same numbers as the leaderboard for this period{s.partialPeriod ? ' (joined during it)' : ''}.{' '}
            {delayed && feedDelay ? (feedDelay.startsWith('the ') ? `Best and worst trades appear after ${feedDelay}.` : `Best and worst trades appear ${feedDelay} after the sell.`) : ''}
          </p>
        </>
      )}
    </section>
  )
}
