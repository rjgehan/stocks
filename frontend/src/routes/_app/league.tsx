import { createFileRoute } from '@tanstack/react-router'
import { useLeaderboard } from '@/lib/api/hooks/league'
import type { KindFilter, LeaderboardMetric, LeaderboardPeriod } from '@/lib/api/types'
import { formatPct } from '@/lib/format/number'
import { PagePlaceholder } from '@/components/PagePlaceholder'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { Tabs } from '@/components/ui/Tabs'
import { LeaderboardTable } from '@/features/league/LeaderboardTable'

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
  view?: 'BOARD' | 'FEED'
}

export const Route = createFileRoute('/_app/league')({
  validateSearch: (s: Record<string, unknown>): LeagueSearch => ({
    period: PERIODS.find((p) => p.value === s.period)?.value,
    metric: s.metric === 'PNL' || s.metric === 'RETURN' ? s.metric : undefined,
    kind: s.kind === 'HUMAN' || s.kind === 'AI' || s.kind === 'ALL' ? s.kind : undefined,
    view: s.view === 'FEED' || s.view === 'BOARD' ? s.view : undefined,
  }),
  staticData: { chrome: 'full', phone: 'root', title: 'League' },
  component: LeaguePage,
})

/** League (§22.4): period tabs, Return/P&L toggle, kind filter, Arena card, board and feed. Full build: M3. */
function LeaguePage() {
  const { period = 'WEEK', metric = 'RETURN', kind = 'ALL', view = 'BOARD' } = Route.useSearch()
  const navigate = Route.useNavigate()
  const set = (patch: Partial<LeagueSearch>) => void navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true })
  const board = useLeaderboard(period, metric, kind)
  return (
    <PagePlaceholder
      title="League"
      milestone="M3"
      layout="none"
      description="Ranked by time-weighted return (deposits never move it) or by net-contribution P&L. Five fills across two symbols to rank."
    >
      <Tabs label="Period" value={period} onChange={(p) => set({ period: p })} items={PERIODS} />
      <div className="flex flex-wrap items-center gap-2">
        <Segmented label="Metric" size="sm" value={metric} onChange={(m) => set({ metric: m })} options={[{ value: 'RETURN', label: 'Return' }, { value: 'PNL', label: 'P&L' }]} />
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
        <div className="flex-1" />
        <Segmented label="View" size="sm" value={view} onChange={(v) => set({ view: v })} options={[{ value: 'BOARD', label: 'Board' }, { value: 'FEED', label: 'Feed' }]} />
      </div>
      {board.data ? (
        <div className="grid grid-cols-3 gap-3 rounded-lg border border-divider bg-bg-1 p-3 text-center">
          <div>
            <div className="text-micro font-semibold tracking-wide text-text-3 uppercase">Humans (median)</div>
            <div className="text-section font-semibold text-human">{formatPct(board.data.arena.humansMedianReturn)}</div>
          </div>
          <div>
            <div className="text-micro font-semibold tracking-wide text-text-3 uppercase">Arena</div>
            <div className="text-caption text-text-2">vs SPY {formatPct(board.data.benchmark.return)}</div>
          </div>
          <div>
            <div className="text-micro font-semibold tracking-wide text-text-3 uppercase">AI (median)</div>
            <div className="text-section font-semibold text-ai">{formatPct(board.data.arena.aiMedianReturn)}</div>
          </div>
        </div>
      ) : null}
      {view === 'FEED' ? (
        <div className="rounded-lg border border-divider bg-bg-1 px-4">
          <SkeletonRows rows={5} cols={3} />
        </div>
      ) : board.data ? (
        <LeaderboardTable rows={board.data.rows} me={board.data.me} metric={metric} />
      ) : (
        <div className="rounded-lg border border-divider bg-bg-1 px-4">
          <SkeletonRows rows={5} cols={4} />
        </div>
      )}
    </PagePlaceholder>
  )
}
