import { Link, createFileRoute } from '@tanstack/react-router'
import { useMe } from '@/lib/api/hooks/identity'
import { useLeaderboard } from '@/lib/api/hooks/league'
import { useMyManualBots } from '@/lib/api/hooks/manualBots'
import type { KindFilter, LeaderboardMetric, LeaderboardPeriod } from '@/lib/api/types'
import { formatTimeEt } from '@/lib/format/time'
import { readLocal, writeLocal } from '@/lib/prefs/local'
import { EmptyState } from '@/components/ui/EmptyState'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { Tabs } from '@/components/ui/Tabs'
import { IconRobot } from '@/components/ui/icons'
import { ArenaCard } from '@/features/league/ArenaCard'
import { FeedList } from '@/features/league/FeedList'
import { LeaderboardTable } from '@/features/league/LeaderboardTable'
import { MetricInfoPopover } from '@/features/league/MetricInfoPopover'
import { useFeedDelay } from '@/features/league/useFeedDelay'
import { linked } from '@/features/manualbot/PromptsScreen'
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

/** How the board shows its figures (% or $; it always ranks by all-time $ P&L), remembered on this device. */
const UNITS_KEY = 'stocks.league.units'
const savedMetric = (): LeaderboardMetric => (readLocal(UNITS_KEY, 'RETURN') === 'PNL' ? 'PNL' : 'RETURN')

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
 * League (§22.4): the board ranks everyone by all-time dollar P&L (a %/$ toggle only changes how figures show), with
 * the kind filter, Arena card (humans vs AI vs SPY) and the Phase 2 optional stat columns; the race chart (Phase 2,
 * §16.9, with its period tabs) and the feed.
 */
function LeaguePage() {
  const search = Route.useSearch()
  const { period = 'WEEK', kind = 'ALL', view = 'BOARD' } = search
  const metric = search.metric ?? savedMetric()
  const navigate = Route.useNavigate()
  const set = (patch: Partial<LeagueSearch>) => {
    if (patch.metric) writeLocal(UNITS_KEY, patch.metric)
    void navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true })
  }
  // the league's ranking: all time, by dollar P&L
  const board = useLeaderboard('ALL', 'PNL', kind)
  const { data: me } = useMe()
  const feedDelay = useFeedDelay()
  const cols = useBoardColumns()
  const rows = board.data?.rows ?? []
  // the owner of a manual bot gets one place to prompt them all
  const myBots = useMyManualBots(me?.kind === 'HUMAN' && me.authMethod === 'SESSION')
  const prompts = myBots.data?.some((b) => !linked(b)) ? (
    <Link to="/prompts" className="inline-flex h-11 shrink-0 items-center gap-1.5 rounded-md bg-accent px-3 text-caption font-semibold text-white hover:bg-accent-hover tablet:h-8">
      <IconRobot size={16} /> Create prompts
    </Link>
  ) : null
  return (
    <div className="scrollbar-thin h-full overflow-y-auto">
      <div className="mx-auto w-full max-w-6xl space-y-4 px-4 py-4 desktop:px-6">
        <div className="hidden items-center gap-2 tablet:flex">
          <h1 className="text-[20px] leading-7 font-bold tracking-tight">League</h1>
          <MetricInfoPopover align="start" />
          <div className="flex-1" />
          {prompts}
          <Segmented label="View" size="sm" value={view} onChange={(v) => set({ view: v })} options={VIEWS} />
        </div>
        <div className="flex items-center gap-2 tablet:hidden">
          <Segmented label="View" block value={view} onChange={(v) => set({ view: v })} options={VIEWS} />
          <MetricInfoPopover />
        </div>
        {prompts ? <div className="tablet:hidden">{prompts}</div> : null}
        {view === 'FEED' ? (
          <FeedList />
        ) : (
          <>
            {view === 'RACE' ? <Tabs label="Period" value={period} onChange={(p) => set({ period: p })} items={PERIODS} /> : null}
            <div className="flex flex-wrap items-center gap-2">
              {view === 'BOARD' ? (
                <Segmented label="Show as" size="sm" value={metric} onChange={(m) => set({ metric: m })} options={[{ value: 'RETURN', label: '%', title: 'Show returns' }, { value: 'PNL', label: '$', title: 'Show dollars' }]} />
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
              <LeaderboardTable rows={rows} me={board.data?.me ?? null} metric={metric} rankBy="PNL" period="ALL" benchmark={board.data?.benchmarkPlayer} myHandle={me?.handle} extraColumns={cols.columns} phoneStat={cols.phoneStat} />
            )}
              </>
            )}
            <p className="text-micro text-text-3">
              Ranked by all-time dollar P&amp;L: deposits never count as profit. Every player&apos;s portfolio and orders are visible live.
              {feedDelay ? ` Trades show in the feed after ${feedDelay}.` : ''}
            </p>
          </>
        )}
      </div>
    </div>
  )
}
