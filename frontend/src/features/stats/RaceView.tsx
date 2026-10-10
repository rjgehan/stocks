import { Suspense, lazy, useMemo, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useRace } from '@/lib/api/hooks/stats'
import type { KindFilter, LeaderboardPeriod, RaceSeries } from '@/lib/api/types'
import { formatPct, pctDirection } from '@/lib/format/number'
import { formatTimeEt } from '@/lib/format/time'
import { useThemeStore } from '@/lib/theme/theme'
import { EmptyState } from '@/components/ui/EmptyState'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { AiChip, Avatar } from '@/features/league/PlayerBadge'
import { lastRankChange } from './format'
import { plotPoints, raceStyles } from './race'
import type { RaceLine } from './RaceChart'
import { RankHistory } from './RankHistory'

const RaceChart = lazy(() => import('./RaceChart'))
const SPY = '__spy'

function cssVar(name: string, fallback: string): string {
  if (typeof document === 'undefined') return fallback
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback
}

function LegendRow({ s, color, me, active, onToggle }: { s: RaceSeries; color: string; me: boolean; active: boolean; onToggle: () => void }) {
  const p = s.player
  const change = lastRankChange(s.ranks, s.rank)
  return (
    <li className={cx('flex items-center gap-2 px-3 py-2', active && 'bg-bg-2', me && 'bg-accent-bg')}>
      <button
        type="button"
        aria-pressed={active}
        onClick={onToggle}
        onMouseEnter={onToggle}
        aria-label={`Highlight ${p?.displayName ?? p?.handle}`}
        className="flex min-h-11 min-w-0 flex-1 items-center gap-2 text-left tablet:min-h-9"
      >
        <span className="w-6 shrink-0 text-center text-caption font-semibold text-text-2">{s.rank ?? '–'}</span>
        <span className="h-1 w-4 shrink-0 rounded-full" style={{ background: color }} aria-hidden="true" />
        <Avatar player={p} size={22} />
        <span className="min-w-0 flex-1 truncate font-semibold">
          {p?.displayName ?? p?.handle}
          {me ? <span className="ml-1 text-micro text-accent">You</span> : null}
        </span>
        {p?.kind === 'AI' ? <AiChip model={null} /> : null}
        {change ? (
          <span className={cx('shrink-0 text-micro', change > 0 ? 'text-up' : 'text-down')} aria-label={`${change > 0 ? 'up' : 'down'} ${Math.abs(change)} since the last close`}>
            {change > 0 ? '▲' : '▼'}
            {Math.abs(change)}
          </span>
        ) : null}
        <span className={cx('w-16 shrink-0 text-right font-semibold tabular-nums', DIRECTION_TEXT[pctDirection(s.return)])}>{formatPct(s.return)}</span>
      </button>
      <Link to="/players/$handle" params={{ handle: p?.handle ?? '' }} className="hidden shrink-0 desktop:block" aria-label={`Rank history of ${p?.displayName ?? p?.handle}`}>
        <RankHistory ranks={s.ranks} width={64} height={22} />
      </Link>
    </li>
  )
}

/**
 * Race chart (§16.9, §22.4 Phase 2): everyone's unit-value curve normalized to 0% at the period start (deposits never
 * move a line), humans in blue shades, AI players in violet shades, SPY dashed, the viewer's line thicker. The legend
 * lists players by rank with their return, rank arrow and rank history; hovering or tapping a row highlights its line.
 */
export function RaceView({ period, kind, myHandle }: { period: LeaderboardPeriod; kind: KindFilter; myHandle?: string | null }) {
  const race = useRace(period, kind)
  const theme = useThemeStore((s) => s.theme)
  const [highlight, setHighlight] = useState<string | null>(null)
  const series = useMemo(() => race.data?.series ?? [], [race.data])
  // the family colors are CSS variables that change with the theme
  const bases = useMemo(() => ({ theme, human: cssVar('--human', '#4c82ff'), ai: cssVar('--ai', '#a78bfa') }), [theme])
  const styles = useMemo(() => raceStyles(series, bases.human, bases.ai, myHandle), [series, bases, myHandle])
  const lines = useMemo<RaceLine[]>(() => {
    const out: RaceLine[] = series.map((s, i) => ({ id: s.player?.handle ?? String(i), color: styles[i]!.color, width: styles[i]!.width, data: plotPoints(s.points) }))
    const spy = race.data?.benchmark
    if (spy?.points?.length) out.push({ id: SPY, color: '', width: 1, dashed: true, data: plotPoints(spy.points) })
    return out
  }, [series, styles, race.data])

  if (race.isError) return <EmptyState title="The race chart is unavailable right now" body="It refreshes with the leaderboard." />
  return (
    <section aria-label="Race chart" className="grid gap-3 desktop:grid-cols-[1fr_340px]">
      <div className="rounded-lg border border-divider bg-bg-1 p-3">
        <div className="mb-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-micro text-text-3">
          <span className="inline-flex items-center gap-1">
            <span className="h-1 w-4 rounded-full bg-human" aria-hidden="true" /> Humans
          </span>
          <span className="inline-flex items-center gap-1">
            <span className="h-1 w-4 rounded-full bg-ai" aria-hidden="true" /> AI players
          </span>
          <span className="inline-flex items-center gap-1">
            <span className="w-4 border-t border-dashed border-text-3" aria-hidden="true" /> {race.data?.benchmark?.symbol ?? 'SPY'}{' '}
            <span className={DIRECTION_TEXT[pctDirection(race.data?.benchmark?.return)]}>{formatPct(race.data?.benchmark?.return)}</span>
          </span>
          {race.data?.asOf ? <span className="ml-auto">Updated {formatTimeEt(race.data.asOf)}</span> : null}
        </div>
        <div className="h-64 tablet:h-80" onMouseLeave={() => setHighlight(null)}>
          {race.isPending ? (
            <Skeleton className="h-full w-full" />
          ) : series.length === 0 ? (
            <div className="flex h-full items-center justify-center text-caption text-text-3">No players in this view yet.</div>
          ) : (
            <Suspense fallback={<Skeleton className="h-full w-full" />}>
              <RaceChart lines={lines} highlight={highlight} intraday={period === 'TODAY' || period === 'WEEK'} />
            </Suspense>
          )}
        </div>
        <p className="mt-1 text-micro text-text-3">Every line starts at 0% at the period start and moves only with performance: deposits never move a line.</p>
      </div>
      <div className="rounded-lg border border-divider bg-bg-1">
        <h2 className="px-3 pt-2.5 pb-1 text-caption font-semibold text-text-2">Standings</h2>
        {race.isPending ? (
          <div className="space-y-2 p-3">
            <Skeleton className="h-8" />
            <Skeleton className="h-8" />
          </div>
        ) : (
          <ul aria-label="Race standings" className="divide-y divide-divider">
            {series.map((s, i) => {
              const id = s.player?.handle ?? String(i)
              return (
                <LegendRow
                  key={id}
                  s={s}
                  color={styles[i]!.color}
                  me={Boolean(myHandle && s.player?.handle === myHandle)}
                  active={highlight === id}
                  onToggle={() => setHighlight((h) => (h === id ? h : id))}
                />
              )
            })}
          </ul>
        )}
        {highlight ? (
          <div className="flex justify-end border-t border-divider p-2">
            <button type="button" onClick={() => setHighlight(null)} className="h-11 px-2 text-caption text-accent tablet:h-7">
              Show all lines
            </button>
          </div>
        ) : null}
      </div>
    </section>
  )
}
