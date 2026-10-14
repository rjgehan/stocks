import { createFileRoute } from '@tanstack/react-router'
import { useMe } from '@/lib/api/hooks/identity'
import { useLeaderboard } from '@/lib/api/hooks/league'
import type { KindFilter, LeaderboardMetric, LeaderboardPeriod } from '@/lib/api/types'
import { formatTimeEt } from '@/lib/format/time'
import { EmptyState } from '@/components/ui/EmptyState'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { Tabs } from '@/components/ui/Tabs'
import { ArenaCard } from '@/features/league/ArenaCard'
import { FeedList } from '@/features/league/FeedList'
import { LeaderboardTable } from '@/features/league/LeaderboardTable'
import { MetricInfoPopover } from '@/features/league/MetricInfoPopover'
import { useFeedDelay } from '@/features/league/useFeedDelay'
import { useBoardColumns } from '@/features/stats/columns'
import { ColumnsPicker } from '@/features/stats/ExtraStat'
import { RaceView } from '@/features/stats/RaceView'

const PERIODS: ReadonlyArray<{ value: LeaderboardPeriod; label: string }> = [
  { value: 'TODAY', label: 'Today' },
  { value: 'WEEK', label: 'Week' },
  { value: 'MONTH', label: 'Month' },
  { value: 'YTD', label: 'YTD' },
  { value: 'SEASON', label: 'Season' },
  { value: 'ALL', label: 'All' },
]

interface LeagueSearch {
  period?: LeaderboardPeriod
  metric?: LeaderboardMetric
  kind?: KindFilter
  view?: 'BOARD' | 'RACE' | 'FEED'
}

const VIEWS: ReadonlyArray<{ value: NonNullable<LeagueSearch['view']>; label: string }> = [
  { value: 'BOARD', label: 'Board' },
  { value: 'RACE', label: 'Race' },
  { value: 'FEED', label: 'Feed' },
]

export const Route = createFileRoute('/_app/league')({
  validateSearch: (s: Record<string, unknown>): LeagueSearch => ({
    period: PERIODS.find((p) => p.value === s.period)?.value,
    metric: s.metric === 'PNL' || s.metric === 'RETURN' ? s.metric : undefined,
    kind: s.kind === 'HUMAN' || s.kind === 'AI' || s.kind === 'ALL' ? s.kind : undefined,
    view: s.view === 'FEED' || s.view === 'BOARD' || s.view === 'RACE' ? s.view : undefined,
  }),
  staticData: { chrome: 'full', phone: 'root', title: 'League' },
  component: LeaguePage,
})

/**
 * League (§22.4): period tabs, Return/P&L toggle, kind filter, Arena card (humans vs AI vs SPY), the board (with the
 * Phase 2 optional stat columns), the race chart (Phase 2, §16.9) and the feed.
 */
function LeaguePage() {
  const { period = 'WEEK', metric = 'RETURN', kind = 'ALL', view = 'BOARD' } = Route.useSearch()
  const navigate = Route.useNavigate()
  const set = (patch: Partial<LeagueSearch>) => void navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true })
  const board = useLeaderboard(period, metric, kind)
  const { data: me } = useMe()
  const feedDelay = useFeedDelay()
  const cols = useBoardColumns()
  const rows = board.data?.rows ?? []
  return (
    <div className="scrollbar-thin h-full overflow-y-auto">
      <div className="mx-auto w-full max-w-6xl space-y-4 px-4 py-4 desktop:px-6">
        <div className="hidden items-center gap-2 tablet:flex">
          <h1 className="text-[20px] leading-7 font-bold tracking-tight">League</h1>
          <MetricInfoPopover align="start" />
          <div className="flex-1" />
          <Segmented label="View" size="sm" value={view} onChange={(v) => set({ view: v })} options={VIEWS} />
        </div>
        <div className="flex items-center gap-2 tablet:hidden">
          <Segmented label="View" block value={view} onChange={(v) => set({ view: v })} options={VIEWS} />
          <MetricInfoPopover />
        </div>
        {view === 'FEED' ? (
          <FeedList />
        ) : (
          <>
            <Tabs label="Period" value={period} onChange={(p) => set({ period: p })} items={PERIODS} />
            <div className="flex flex-wrap items-center gap-2">
              {view === 'BOARD' ? (
                <Segmented label="Rank by" size="sm" value={metric} onChange={(m) => set({ metric: m })} options={[{ value: 'RETURN', label: 'Return' }, { value: 'PNL', label: 'P&L ($)' }]} />
              ) : null}
              <Segmented
                label="Players"
                size="sm"
                value={kind}
                onChange={(k) => set({ kind: k })}
                options={[
                  { value: 'ALL', label: 'All' },
                  { value: 'HUMAN', label: 'Humans' },
                  { value: 'AI', label: 'AI' },
                ]}
              />
              {view === 'BOARD' ? (
                <ColumnsPicker columns={cols.columns} onToggle={cols.toggle} phoneStat={cols.phoneStat} onPhoneStat={cols.setPhoneStat} />
              ) : null}
              {view === 'BOARD' && board.data?.asOf ? <span className="ml-auto text-micro text-text-3">Updated {formatTimeEt(board.data.asOf)}</span> : null}
            </div>
            {view === 'RACE' ? (
              <RaceView period={period} kind={kind} myHandle={me?.handle} />
            ) : (
              <>
            {metric === 'PNL' ? <p className="text-caption text-text-3">Ranking by dollar P&amp;L favours bigger accounts; deposits never count as profit.</p> : null}
            {/* the arena compares both teams: under a Humans or AI filter its counts and best players would come from one
                side while the medians (computed over everyone) still cover both, so it shows on All only */}
            {board.data && kind === 'ALL' ? <ArenaCard board={board.data} /> : null}
            {board.isPending ? (
              <div className="rounded-lg border border-divider bg-bg-1 px-4">
                <SkeletonRows rows={5} cols={4} />
              </div>
            ) : board.isError ? (
              <EmptyState title="The leaderboard is unavailable right now" body="It refreshes automatically every few seconds." />
            ) : rows.length === 0 ? (
              <EmptyState title="No players yet" body="Invite friends from the admin console, or create an AI player." />
            ) : (
              <LeaderboardTable rows={rows} me={board.data?.me ?? null} metric={metric} myHandle={me?.handle} extraColumns={cols.columns} phoneStat={cols.phoneStat} />
            )}
              </>
            )}
            <p className="text-micro text-text-3">
              Return is time-weighted: deposits and withdrawals never move it. Net contributions are public; every player&apos;s portfolio and orders are visible live.
              {feedDelay ? ` Trades show in the feed after ${feedDelay}.` : ''}
            </p>
          </>
        )}
      </div>
    </div>
  )
}
