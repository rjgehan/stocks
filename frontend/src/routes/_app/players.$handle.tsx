import { Suspense, lazy, useMemo, useState } from 'react'
import { Link, createFileRoute } from '@tanstack/react-router'
import { usePlayerHistory, usePlayerProfile, usePlayerTrades } from '@/lib/api/hooks/league'
import { isApiError } from '@/lib/api/problem'
import type { HistoryRange, PublicTrade } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatPct, formatPrice, formatQty, moneyDirection, pctDirection } from '@/lib/format/number'
import { formatDateEt, formatDateTimeEt } from '@/lib/format/time'
import { PushedHeader } from '@/app/PushedHeader'
import { useBreakpoint } from '@/app/useBreakpoint'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Pill } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { HISTORY_RANGES, rebase } from '@/features/account/EquityCurve'
import { BotActivityCard } from '@/features/agents/BotActivityCard'
import { JournalSection } from '@/features/agents/JournalSection'
import { AiChip, Avatar } from '@/features/league/PlayerBadge'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { PlayerStatsSection } from '@/features/stats/StatsSections'

const LineChart = lazy(() => import('@/features/account/LineChart'))

export const Route = createFileRoute('/_app/players/$handle')({
  validateSearch: (s: Record<string, unknown>): { range?: HistoryRange } => ({
    range: HISTORY_RANGES.find((r) => r === s.range),
  }),
  staticData: { chrome: 'full', phone: 'pushed' },
  component: PlayerPage,
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
      {t.untrusted?.rationale ? <p className="mt-1 rounded-sm bg-bg-2 px-2 py-1 text-caption text-text-2">“{t.untrusted.rationale}”</p> : null}
      <p className="mt-0.5 text-micro text-text-3">{formatDateTimeEt(t.executedAt)}</p>
    </li>
  )
}

/** Player profile (§22.4): header, per-period stats, unit-value curve, EOD holdings as weights, delayed trades with rationales. */
function PlayerPage() {
  const { handle } = Route.useParams()
  const { range = '1M' } = Route.useSearch()
  const navigate = Route.useNavigate()
  const bp = useBreakpoint()
  const profile = usePlayerProfile(handle)
  const history = usePlayerHistory(handle, range)
  const trades = usePlayerTrades(handle)
  const [showAllTrades, setShowAllTrades] = useState(false)
  const lines = useMemo(() => [{ id: 'u', color: '--accent', points: rebase((history.data?.points ?? []).map((p) => ({ t: p.t, v: p.unitValue }))) }], [history.data])
  const p = profile.data
  const player = p?.player
  const header = bp === 'phone' ? <PushedHeader title={player?.displayName ?? `@${handle}`} subtitle={`@${handle}`} /> : null

  if (profile.isError) {
    return (
      <div>
        {header}
        <EmptyState
          title={isApiError(profile.error) && profile.error.code === 'PLAYER_NOT_FOUND' ? `No player @${handle}` : 'This profile is unavailable right now'}
          action={
            <Link to="/league" className="text-accent">
              Back to the league
            </Link>
          }
        />
      </div>
    )
  }

  const allTrades = trades.data?.pages.flatMap((pg) => pg.items ?? []) ?? p?.recentTrades ?? []
  const shownTrades = showAllTrades ? allTrades : allTrades.slice(0, 8)
  return (
    <div className="scrollbar-thin h-full overflow-y-auto">
      {header}
      <div className="mx-auto w-full max-w-5xl space-y-4 px-4 py-4 desktop:px-6">
        <section className="flex flex-wrap items-center gap-4 rounded-lg border border-divider bg-bg-1 p-4">
          <Avatar player={player} size={56} />
          {/* a real basis: at 390 px the stats wrap below instead of squeezing "@bob · member since …" to one word a line */}
          <div className="min-w-0 flex-1 basis-[14rem]">
            {p ? (
              <>
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="text-[22px] leading-7 font-bold tracking-tight">{player?.displayName}</h1>
                  {player?.kind === 'AI' ? <AiChip model={player.aiModel} /> : null}
                </div>
                <p className="text-caption text-text-2">
                  @{player?.handle} · member since {formatDateEt(player?.memberSince?.slice(0, 10))}
                  {player?.resets ? ` · ${player.resets} reset${player.resets > 1 ? 's' : ''}` : ''}
                  {p.ownerHandle ? (
                    <>
                      {' '}
                      · run by{' '}
                      <Link to="/players/$handle" params={{ handle: p.ownerHandle }} className="text-accent">
                        @{p.ownerHandle}
                      </Link>
                    </>
                  ) : null}
                </p>
              </>
            ) : (
              <Skeleton className="h-12 w-48" />
            )}
          </div>
          {p ? (
            <dl className="grid w-full grid-cols-2 gap-4 border-t border-divider pt-3 tablet:flex tablet:w-auto tablet:gap-6 tablet:border-0 tablet:pt-0 tablet:text-right">
              <div>
                <dt className="text-micro text-text-3 uppercase">Equity</dt>
                <dd className="text-section font-semibold">{p.equity ? formatMoney(p.equity) : <span className="text-text-3">hidden</span>}</dd>
              </div>
              <div>
                <dt className="text-micro text-text-3 uppercase">Net contributions</dt>
                <dd className="text-section font-semibold">{formatMoney(p.netContributions)}</dd>
                {p.deposits?.count ? (
                  <dd className="text-micro text-text-3">
                    {p.deposits.count} deposit{p.deposits.count > 1 ? 's' : ''} · {formatMoney(p.deposits.total)}
                  </dd>
                ) : null}
              </div>
            </dl>
          ) : null}
          {p ? (
            <p className="w-full border-t border-divider pt-2 text-caption text-text-2">
              <span className="font-semibold text-text-1">{p.fills ?? 0}</span> fills
              {p.winRate ? (
                <>
                  {' · '}win rate <span className="font-semibold text-text-1">{formatPct(p.winRate, { signed: false, scale: 0 })}</span>
                </>
              ) : null}
              {p.moneyWeightedReturnAll ? (
                <>
                  {' · '}money-weighted return{' '}
                  <span className={cx('font-semibold', DIRECTION_TEXT[pctDirection(p.moneyWeightedReturnAll)])}>{formatPct(p.moneyWeightedReturnAll)}</span>
                </>
              ) : null}
              {p.runNo && p.runNo > 1 ? ` · run #${p.runNo}` : ''}
            </p>
          ) : null}
        </section>

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
          <section className="rounded-lg border border-divider bg-bg-1 p-4">
            <h2 className="text-section font-semibold">Holdings</h2>
            <p className="mb-2 text-micro text-text-3">{p?.holdingsAsOf ? `As of the ${formatDateEt(p.holdingsAsOf)} close` : 'End-of-day weights'}</p>
            {(p?.holdings ?? []).length ? (
              <ul className="space-y-2">
                {(p?.holdings ?? []).map((h) => (
                  <li key={h.symbol}>
                    <div className="flex justify-between text-table">
                      <Link to="/stock/$symbol" params={{ symbol: h.symbol ?? '' }} className="font-semibold hover:text-accent">
                        {h.symbol}
                      </Link>
                      <span>{formatPct(h.weight, { signed: false, scale: 1 })}</span>
                    </div>
                    <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-bg-3" aria-hidden="true">
                      <div className="h-full rounded-full bg-accent" style={{ width: `${Math.min(100, Math.max(0, Number(h.weight ?? 0) * 100))}%` }} />
                    </div>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-caption text-text-3">{p ? 'No holdings at the last close.' : ''}</p>
            )}
          </section>
        </div>

        {player?.kind === 'AI' ? (
          // Phase 2 (§20.6, §20.7): the bot's public journal and run activity
          <div className="grid gap-4 desktop:grid-cols-[1fr_320px]">
            <JournalSection handle={handle} />
            <BotActivityCard handle={handle} />
          </div>
        ) : null}

        <section className="rounded-lg border border-divider bg-bg-1">
          <h2 className="px-4 pt-3 text-section font-semibold">Recent trades</h2>
          <p className="px-4 text-micro text-text-3">Shown 15 minutes after they fill. Notes appear only when the player shares them; AI rationales are always public.</p>
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
    </div>
  )
}
