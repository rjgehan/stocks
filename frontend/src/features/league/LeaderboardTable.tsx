import { Link } from '@tanstack/react-router'
import type { BenchmarkPlayer, LeaderboardMetric, LeaderboardPeriod, LeaderboardRow } from '@/lib/api/types'
import { type Dec, compare, isDecimal } from '@/lib/format/decimal'
import { formatMoney, formatMoneyCompact } from '@/lib/format/money'
import { formatPct, moneyDirection, pctDirection } from '@/lib/format/number'
import { Pill } from '@/components/ui/Pill'
import { Sparkline } from '@/components/ui/Sparkline'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import type { ExtraColumn } from '@/features/stats/columns'
import { ExtraStatValue, columnLabel } from '@/features/stats/ExtraStat'
import { FormulaPopover } from './MetricInfoPopover'
import { AiChip, Avatar } from './PlayerBadge'

const MEDAL = ['bg-gold', 'bg-silver', 'bg-bronze'] as const
/** Room for "+$1,234,567.89" whichever unit shows, so the %/$ toggle never resizes the board. */
const FIGURE_COL = 'w-32 min-w-32'
const FIGURES_PHONE = 'w-[8.5rem]'

export const PERIOD_LABEL: Record<LeaderboardPeriod, string> = { TODAY: 'Today', WEEK: 'Week', MONTH: 'Month', YTD: 'YTD', SEASON: 'Season', ALL: 'All time' }

/** One figure of a row: the period's (what the board ranks by), today's or the all-time one. */
type Span = 'period' | 'day' | 'total'

/** What a board line needs: a player's row or SPY as a player. */
type Figures = Pick<LeaderboardRow, 'return' | 'pnl' | 'dayReturn' | 'dayPnl' | 'totalReturn' | 'totalPnl'>

function figure(f: Figures, span: Span, metric: LeaderboardMetric): Dec | null | undefined {
  if (metric === 'RETURN') return span === 'day' ? f.dayReturn : span === 'total' ? f.totalReturn : f.return
  return span === 'day' ? f.dayPnl : span === 'total' ? f.totalPnl : f.pnl
}

/** "+1.23%" or "+$6.15", coloured; the other unit in the tooltip. */
function Value({ f, span, metric, strong }: { f: Figures; span: Span; metric: LeaderboardMetric; strong?: boolean }) {
  const v = figure(f, span, metric)
  const other = figure(f, span, metric === 'RETURN' ? 'PNL' : 'RETURN')
  const pct = metric === 'RETURN'
  return (
    <span title={other != null ? (pct ? formatMoney(other, { signed: true }) : formatPct(other)) : undefined} className={cx(strong && 'font-semibold', DIRECTION_TEXT[pct ? pctDirection(v) : moneyDirection(v)])}>
      {pct ? formatPct(v) : formatMoney(v, { signed: true })}
    </span>
  )
}

/** The columns after the player: the period's (unless Day or Total already is it), Day, Total. */
function spans(period: LeaderboardPeriod): Span[] {
  return period === 'TODAY' || period === 'ALL' ? ['day', 'total'] : ['period', 'day', 'total']
}

/** The column the board ranks by (bold). */
const primary = (period: LeaderboardPeriod): Span => (period === 'TODAY' ? 'day' : period === 'ALL' ? 'total' : 'period')

function spanLabel(span: Span, period: LeaderboardPeriod): string {
  return span === 'day' ? 'Day' : span === 'total' ? 'Total' : PERIOD_LABEL[period]
}

/**
 * Where SPY goes among the rows: after the last ranked row that beats or ties it on the board's metric (never among
 * the unranked rows below). -1 when SPY's figure is unknown.
 */
export function benchmarkIndex(rows: LeaderboardRow[], b: BenchmarkPlayer | null | undefined, metric: LeaderboardMetric): number {
  const v = b ? (metric === 'RETURN' ? b.return : b.pnl) : null
  if (!v || !isDecimal(v)) return -1
  let at = 0
  rows.forEach((r, i) => {
    const rv = metric === 'RETURN' ? r.return : r.pnl
    if (r.rank != null && rv && isDecimal(rv) && compare(rv, v) >= 0) at = i + 1
  })
  return at
}

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
        <span className={cx('text-micro', row.rankChange > 0 ? 'text-up' : 'text-down')} aria-label={`${row.rankChange > 0 ? 'up' : 'down'} ${Math.abs(row.rankChange)} since the last snapshot`}>
          {row.rankChange > 0 ? '▲' : '▼'}
          {Math.abs(row.rankChange)}
        </span>
      ) : null}
    </span>
  )
}

function Name({ row, me }: { row: LeaderboardRow; me: boolean }) {
  const p = row.player
  return (
    <Link to="/players/$handle" params={{ handle: p?.handle ?? '' }} className="flex min-h-11 min-w-0 items-center gap-2.5 hover:text-accent">
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

function SpyName({ b }: { b: BenchmarkPlayer }) {
  return (
    <Link to="/stock/$symbol" params={{ symbol: b.symbol ?? 'SPY' }} className="flex min-h-11 min-w-0 items-center gap-2.5 hover:text-accent">
      <span aria-hidden="true" className="inline-flex size-[30px] shrink-0 items-center justify-center rounded-full bg-bg-3 text-[10px] font-bold text-text-1">
        {b.symbol ?? 'SPY'}
      </span>
      <span className="min-w-0">
        <span className="flex flex-wrap items-center gap-1.5">
          <span className="truncate font-semibold">S&amp;P 500</span>
          <Pill tone="neutral" title="Not a player: the index, bought and held. It takes no rank.">
            Benchmark
          </Pill>
        </span>
        <span className="block truncate text-caption text-text-3">
          {b.symbol ?? 'SPY'} · {b.startingCash ? `${formatMoneyCompact(b.startingCash)} bought and held` : 'bought and held'}
        </span>
      </span>
    </Link>
  )
}

/**
 * Leaderboard (§22.4): medals for 1–3, rank arrows, AI chip, the period's figure plus Day and Total (as % or $ by
 * the board's metric), equity, fills, sparkline; unranked rows greyed with their eligibility note; SPY as an
 * unranked benchmark line among the ranked rows; my row pinned at the bottom. Cards on phones.
 */
export function LeaderboardTable({
  rows,
  me,
  metric,
  rankBy = metric,
  period = 'WEEK',
  benchmark = null,
  myHandle,
  extraColumns = [],
  phoneStat = null,
}: {
  rows: LeaderboardRow[]
  me: LeaderboardRow | null
  /** RETURN shows every figure as a percentage, PNL in dollars. */
  metric: LeaderboardMetric
  /** What the rows are ordered by (where SPY slots in); default the shown metric. */
  rankBy?: LeaderboardMetric
  period?: LeaderboardPeriod
  /** SPY as a player, shown where it would place (never ranked). */
  benchmark?: BenchmarkPlayer | null
  myHandle?: string | null
  /** Phase 2 (§16.8): optional stat columns on tablet and desktop. */
  extraColumns?: readonly ExtraColumn[]
  /** Phase 2: one optional stat on each phone card. */
  phoneStat?: ExtraColumn | null
}) {
  const isMe = (r: LeaderboardRow) => Boolean(myHandle && r.player?.handle === myHandle)
  // My row is pinned at the bottom when the board does not already list it (§22.4).
  const pinned = me && !rows.some(isMe) ? me : null
  const spyAt = benchmarkIndex(rows, benchmark, rankBy)
  const cols = spans(period)
  const main = primary(period)
  const secondary = cols.filter((s) => s !== main)

  const card = (r: LeaderboardRow, pin = false) => (
    <li key={`${pin ? 'pin-' : ''}${r.player?.handle}`} className={cx('flex items-center gap-3 px-3 py-3', !r.eligible && 'opacity-55', (pin || isMe(r)) && 'bg-accent-bg')}>
      <span className="w-9 shrink-0">
        <RankCell row={r} />
      </span>
      <div className="min-w-0 flex-1">
        <Name row={r} me={isMe(r)} />
      </div>
      <div className={cx('shrink-0 text-right tabular-nums', FIGURES_PHONE)}>
        <div className="text-body">
          <Value f={r} span={main} metric={metric} strong />
        </div>
        <div className="text-caption text-text-3">
          {secondary.map((s) => (
            <span key={s} className="block whitespace-nowrap">
              {spanLabel(s, period)} <Value f={r} span={s} metric={metric} />
            </span>
          ))}
        </div>
        {phoneStat ? (
          <div className="text-micro text-text-3">
            {columnLabel(phoneStat)} <ExtraStatValue row={r} column={phoneStat} />
          </div>
        ) : null}
      </div>
    </li>
  )

  const spyCard = (b: BenchmarkPlayer) => (
    <li key="benchmark" aria-label="Benchmark" className="flex items-center gap-3 bg-bg-2/60 px-3 py-3">
      <span className="w-9 shrink-0 text-text-3">–</span>
      <div className="min-w-0 flex-1">
        <SpyName b={b} />
      </div>
      <div className={cx('shrink-0 text-right tabular-nums', FIGURES_PHONE)}>
        <div className="text-body">
          <Value f={b} span={main} metric={metric} strong />
        </div>
        <div className="text-caption text-text-3">
          {secondary.map((s) => (
            <span key={s} className="block whitespace-nowrap">
              {spanLabel(s, period)} <Value f={b} span={s} metric={metric} />
            </span>
          ))}
        </div>
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
      {cols.map((s) => (
        <td key={s} className={cx('px-2 text-right whitespace-nowrap tabular-nums', FIGURE_COL, s === main && 'text-body')}>
          <Value f={r} span={s} metric={metric} strong={s === main} />
        </td>
      ))}
      <td className="hidden w-36 px-2 text-right whitespace-nowrap tabular-nums wide:table-cell">{r.equity ? formatMoney(r.equity) : <span className="text-text-3">—</span>}</td>
      <td className="hidden px-2 text-right tablet:table-cell">{r.fills}</td>
      {extraColumns.map((c) => (
        <td key={c} className="hidden px-2 text-right tablet:table-cell">
          <ExtraStatValue row={r} column={c} />
        </td>
      ))}
      <td className="hidden pr-4 pl-2 desktop:table-cell">
        <Sparkline values={r.spark} />
      </td>
    </tr>
  )

  const spyTr = (b: BenchmarkPlayer) => (
    <tr key="benchmark" aria-label="Benchmark" className="h-14 bg-bg-2/60">
      <td className="w-16 pl-4 text-text-3">–</td>
      <td className="max-w-0 py-2 pr-2">
        <SpyName b={b} />
      </td>
      {cols.map((s) => (
        <td key={s} className={cx('px-2 text-right whitespace-nowrap tabular-nums', FIGURE_COL, s === main && 'text-body')}>
          <Value f={b} span={s} metric={metric} strong={s === main} />
        </td>
      ))}
      <td className="hidden w-36 px-2 text-right whitespace-nowrap tabular-nums wide:table-cell">{b.equity ? formatMoney(b.equity) : <span className="text-text-3">—</span>}</td>
      <td className="hidden px-2 text-right text-text-3 tablet:table-cell">—</td>
      {extraColumns.map((c) => (
        <td key={c} className="hidden px-2 text-right text-text-3 tablet:table-cell">
          —
        </td>
      ))}
      <td className="hidden pr-4 pl-2 text-text-3 desktop:table-cell">—</td>
    </tr>
  )

  /** The rows with SPY slotted in. */
  function lines<T>(row: (r: LeaderboardRow) => T, spy: (b: BenchmarkPlayer) => T): T[] {
    const out = rows.map((r) => row(r))
    if (benchmark && spyAt >= 0) out.splice(spyAt, 0, spy(benchmark))
    return out
  }

  return (
    <>
      <ul className="divide-y divide-divider rounded-lg border border-divider bg-bg-1 tablet:hidden" aria-label="Leaderboard">
        {lines(card, spyCard)}
        {pinned ? card(pinned, true) : null}
      </ul>
      <div className="hidden overflow-x-auto rounded-lg border border-divider bg-bg-1 tablet:block">
        <table className="w-full text-table" aria-label="Leaderboard">
          <thead className="text-left text-micro tracking-wide text-text-3 uppercase">
            <tr className="h-9 border-b border-divider">
              <th className="pl-4 font-medium">
                <span className="inline-flex items-center gap-0.5">
                  Rank <FormulaPopover topic="RANK_CHANGE" align="start" />
                </span>
              </th>
              <th className="w-[30%] min-w-52 font-medium">
                <span className="inline-flex items-center gap-0.5">
                  Player <FormulaPopover topic="RANKED" align="start" />
                </span>
              </th>
              {cols.map((s) => (
                <th key={s} className={cx('px-2 text-right font-medium', FIGURE_COL, s === main && 'text-text-1')}>
                  <span className="inline-flex items-center gap-0.5">
                    {spanLabel(s, period)}
                    {s === main ? <FormulaPopover topic={metric === 'RETURN' ? 'RETURN' : 'PNL'} /> : null}
                  </span>
                </th>
              ))}
              <th className="hidden w-36 px-2 text-right font-medium wide:table-cell">Equity</th>
              <th className="hidden px-2 text-right font-medium tablet:table-cell">Fills</th>
              {extraColumns.map((c) => (
                <th key={c} className="hidden px-2 text-right font-medium tablet:table-cell">
                  {columnLabel(c)}
                </th>
              ))}
              <th className="hidden pr-4 pl-2 font-medium desktop:table-cell">Trend</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-divider">
            {lines(tr, spyTr)}
            {pinned ? tr(pinned, true) : null}
          </tbody>
        </table>
      </div>
    </>
  )
}
