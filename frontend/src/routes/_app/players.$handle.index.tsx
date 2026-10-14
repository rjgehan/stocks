import { Suspense, lazy, useMemo, useState } from 'react'
import { Link, createFileRoute } from '@tanstack/react-router'
import { usePlayerHistory, usePlayerPortfolio, usePlayerProfile, usePlayerTrades } from '@/lib/api/hooks/league'
import type { HistoryRange, PublicTrade } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatPct, formatPrice, formatQty, moneyDirection, pctDirection } from '@/lib/format/number'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { Pill } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { HISTORY_RANGES, rebase } from '@/features/account/EquityCurve'
import { BotActivityCard } from '@/features/agents/BotActivityCard'
import { JournalSection } from '@/features/agents/JournalSection'
import { topHoldings } from '@/features/league/PublicPortfolio'
import { useFeedDelay } from '@/features/league/useFeedDelay'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { PlayerStatsSection } from '@/features/stats/StatsSections'

const LineChart = lazy(() => import('@/features/account/LineChart'))

export const Route = createFileRoute('/_app/players/$handle/')({
  validateSearch: (s: Record<string, unknown>): { range?: HistoryRange } => ({
    range: HISTORY_RANGES.find((r) => r === s.range),
  }),
  component: PlayerOverview,
})

const PERIOD_LABEL = { TODAY: 'Today', WEEK: 'Week', MONTH: 'Month', YTD: 'YTD', ALL: 'All time' } as const

function TradeRow({ t }: { t: PublicTrade }) {
  return (
    <li className="px-4 py-3">
      <div className="flex items-center justify-between gap-3 text-table">
        <span>
          <span className={cx('font-semibold', t.side === 'SELL' ? 'text-down' : 'text-up')}>{t.side === 'SELL' ? 'Sold' : 'Bought'}</span> {formatQty(t.qty)}{' '}
          <Link to="/stock/$symbol" params={{ symbol: t.symbol ?? '' }} className="font-semibold hover:text-accent">
            {t.symbol}
          </Link>{' '}
          @ {formatPrice(t.price)}
          {t.viaApi ? (
            <Pill tone="neutral" className="ml-1.5">
              via API
            </Pill>
          ) : null}
        </span>
        {t.realizedPnl ? <span className={cx('shrink-0 font-semibold', DIRECTION_TEXT[moneyDirection(t.realizedPnl)])}>{formatMoney(t.realizedPnl, { signed: true })}</span> : null}
      </div>
      {t.untrusted?.rationale ? <p className="mt-1 rounded-sm bg-bg-2 px-2 py-1 text-caption break-words text-text-2">“{t.untrusted.rationale}”</p> : null}
      <p className="mt-0.5 text-micro text-text-3">{formatDateTimeEt(t.executedAt)}</p>
    </li>
  )
}

function TopHoldings({ handle }: { handle: string }) {
  const q = usePlayerPortfolio(handle)
  const top = topHoldings(q.data?.positions ?? [])
  return (
    <section className="rounded-lg border border-divider bg-bg-1 p-4">
      <div className="mb-2 flex items-baseline justify-between gap-2">
        <h2 className="text-section font-semibold">Top holdings (live)</h2>
        <Link to="/players/$handle/portfolio" params={{ handle }} className="text-caption font-semibold text-accent">
          Full portfolio
        </Link>
      </div>
      {q.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : top.length ? (
        <ul className="space-y-2" aria-label="Top holdings">
          {top.map((h) => (
            <li key={h.symbol}>
              <div className="flex justify-between gap-2 text-table">
                <Link to="/stock/$symbol" params={{ symbol: h.symbol ?? '' }} className="font-semibold hover:text-accent">
                  {h.symbol}
                </Link>
                <span>
                  {formatPct(h.weight, { signed: false, scale: 1 })}
                  <span className={cx('ml-2 text-caption', DIRECTION_TEXT[pctDirection(h.unrealizedReturn)])}>{formatPct(h.unrealizedReturn)}</span>
                </span>
              </div>
              <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-bg-3" aria-hidden="true">
                <div className="h-full rounded-full bg-accent" style={{ width: `${Math.min(100, Math.max(0, Number(h.weight ?? 0) * 100))}%` }} />
              </div>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-caption text-text-3">{q.isError ? 'Holdings are unavailable right now.' : 'No positions: all cash.'}</p>
      )}
    </section>
  )
}

/** Profile → Overview (§22.4): per-period stats, league stats, the unit-value curve, live top holdings, journal and fills. */
function PlayerOverview() {
  const { handle } = Route.useParams()
  const { range = '1M' } = Route.useSearch()
  const navigate = Route.useNavigate()
  const profile = usePlayerProfile(handle)
  const history = usePlayerHistory(handle, range)
  const trades = usePlayerTrades(handle)
  const feedDelay = useFeedDelay()
  const [showAllTrades, setShowAllTrades] = useState(false)
  const lines = useMemo(() => [{ id: 'u', color: '--accent', points: rebase((history.data?.points ?? []).map((p) => ({ t: p.t, v: p.unitValue }))) }], [history.data])
  const p = profile.data
  const player = p?.player
  const allTrades = trades.data?.pages.flatMap((pg) => pg.items ?? []) ?? p?.recentTrades ?? []
  const shownTrades = showAllTrades ? allTrades : allTrades.slice(0, 8)

  return (
    <div className="space-y-4">
      <section aria-label="Stats" className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-divider bg-divider tablet:grid-cols-5">
        {(Object.keys(PERIOD_LABEL) as Array<keyof typeof PERIOD_LABEL>).map((k) => {
          const s = p?.stats?.[k]
          return (
            <div key={k} className={cx('bg-bg-1 p-3', k === 'ALL' && 'col-span-2 tablet:col-span-1')}>
              <div className="text-caption text-text-2">{PERIOD_LABEL[k]}</div>
              {p ? (
                <>
                  <div className={cx('text-section font-semibold', DIRECTION_TEXT[pctDirection(s?.return)])}>{formatPct(s?.return)}</div>
                  <div className="text-caption text-text-3">
                    {formatMoney(s?.pnl, { signed: true })}
                    {s?.rank ? ` · #${s.rank}` : ''}
                  </div>
                </>
              ) : (
                <Skeleton className="mt-1 h-5 w-16" />
              )}
            </div>
          )
        })}
      </section>

      {p ? <PlayerStatsSection handle={handle} /> : null}

      <div className="grid gap-4 desktop:grid-cols-[1fr_320px]">
        <section className="rounded-lg border border-divider bg-bg-1 p-4">
          <h2 className="mb-2 text-section font-semibold">Return</h2>
          <div className="h-56">
            {history.isError ? (
              <p className="flex h-full items-center justify-center text-caption text-text-3">The return curve appears after the first end-of-day snapshot.</p>
            ) : history.isPending ? (
              <Skeleton className="h-full w-full" />
            ) : (
              <Suspense fallback={<Skeleton className="h-full w-full" />}>
                <LineChart lines={lines} intraday={range === '1D' || range === '1W'} format="percent" />
              </Suspense>
            )}
          </div>
          <div role="radiogroup" aria-label="Range" className="scrollbar-none mt-2 flex gap-1 overflow-x-auto">
            {HISTORY_RANGES.map((r) => (
              <button
                key={r}
                type="button"
                role="radio"
                aria-checked={r === range}
                onClick={() => void navigate({ search: { range: r === '1M' ? undefined : r }, replace: true })}
                className={cx('h-11 min-w-10 shrink-0 rounded-sm px-2 text-caption font-semibold tablet:h-7', r === range ? 'bg-bg-3 text-text-1' : 'text-text-2 hover:bg-bg-2')}
              >
                {r}
              </button>
            ))}
          </div>
        </section>
        <TopHoldings handle={handle} />
      </div>

      {player?.kind === 'AI' ? (
        // Phase 2 (§20.6, §20.7): the bot's public journal and run activity
        <div className="grid gap-4 desktop:grid-cols-[1fr_320px]">
          <JournalSection handle={handle} />
          <BotActivityCard handle={handle} />
        </div>
      ) : null}

      <section className="rounded-lg border border-divider bg-bg-1">
        <div className="flex items-baseline justify-between gap-2 px-4 pt-3">
          <h2 className="text-section font-semibold">Recent trades</h2>
          <Link to="/players/$handle/history" params={{ handle }} className="text-caption font-semibold text-accent">
            Full history
          </Link>
        </div>
        <p className="px-4 text-micro text-text-3">{feedDelay ? `Fills show after ${feedDelay}. Notes are public.` : 'Every fill as it happens. Notes are public.'}</p>
        {allTrades.length ? (
          <ul className="divide-y divide-divider">
            {shownTrades.map((t, i) => (
              <TradeRow key={`${t.executedAt}-${i}`} t={t} />
            ))}
          </ul>
        ) : (
          <p className="px-4 py-4 text-caption text-text-3">No public trades yet.</p>
        )}
        {allTrades.length > shownTrades.length || trades.hasNextPage ? (
          <div className="flex justify-center p-3">
            <Button size="sm" onClick={() => (showAllTrades ? void trades.fetchNextPage() : setShowAllTrades(true))} disabled={trades.isFetchingNextPage}>
              Show more
            </Button>
          </div>
        ) : null}
      </section>
    </div>
  )
}
